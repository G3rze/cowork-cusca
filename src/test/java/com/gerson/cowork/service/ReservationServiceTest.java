package com.gerson.cowork.service;

import com.gerson.cowork.dto.reservation.ReservationCreateRequest;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.ReservationStatus;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.event.ReservationCancelledEvent;
import com.gerson.cowork.exception.InvalidReservationTransitionException;
import com.gerson.cowork.exception.OverlappingReservationException;
import com.gerson.cowork.exception.ResourceNotFoundException;
import com.gerson.cowork.mapper.ReservationMapper;
import com.gerson.cowork.pricing.PriceCalculator;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.repository.SpaceRepository;
import com.gerson.cowork.repository.UserRepository;
import com.gerson.cowork.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

	private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");

	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private SpaceRepository spaceRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private PriceCalculator priceCalculator;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private ReservationService service;
	private User owner;
	private Space space;
	private AuthenticatedUser ownerPrincipal;

	@BeforeEach
	void setUp() {
		service = new ReservationService(reservationRepository, spaceRepository, userRepository, priceCalculator,
				new ReservationMapper(), new ReservationAccess(), eventPublisher);
		owner = User.builder().id(UUID.randomUUID()).name("Owner").email("owner@test.com").role(Role.USER).build();
		space = Space.builder().id(UUID.randomUUID()).name("Room").hourlyRate(BigDecimal.TEN).build();
		ownerPrincipal = new AuthenticatedUser(owner.getId(), owner.getEmail(), Role.USER);
	}

	private Reservation reservation(ReservationStatus status) {
		return Reservation.builder().id(UUID.randomUUID()).space(space).user(owner).startAt(START)
				.endAt(START.plusSeconds(3600)).status(status).totalPrice(BigDecimal.TEN).build();
	}

	private ReservationCreateRequest createRequest() {
		return new ReservationCreateRequest(space.getId(), START, START.plusSeconds(7200));
	}

	private void stubCreateLookups() {
		when(spaceRepository.findByIdForUpdate(space.getId())).thenReturn(Optional.of(space));
		when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
		when(priceCalculator.calculate(any(), any(), any())).thenReturn(new BigDecimal("20.00"));
	}

	@Test
	void createStartsPendingPaymentWithTheCalculatedPrice() {
		stubCreateLookups();
		when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ReservationResponse response = service.create(ownerPrincipal, createRequest());

		assertThat(response.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
		assertThat(response.totalPrice()).isEqualByComparingTo("20.00");
		assertThat(response.userId()).isEqualTo(owner.getId());
	}

	@Test
	void createLocksTheSpaceInsteadOfJustReadingIt() {
		stubCreateLookups();
		when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.create(ownerPrincipal, createRequest());

		verify(spaceRepository).findByIdForUpdate(space.getId());
		verify(spaceRepository, never()).findById(any());
	}

	@Test
	void exclusionConstraintViolationBecomesOverlappingReservation() {
		stubCreateLookups();
		when(reservationRepository.saveAndFlush(any(Reservation.class))).thenThrow(new DataIntegrityViolationException(
				"insert failed", new RuntimeException(
						"ERROR: conflicting key value violates exclusion constraint \"no_overlapping_active_reservations\"")));

		assertThatThrownBy(() -> service.create(ownerPrincipal, createRequest()))
				.isInstanceOf(OverlappingReservationException.class);
	}

	@Test
	void otherIntegrityViolationsAreNotDisguisedAsOverlaps() {
		stubCreateLookups();
		DataIntegrityViolationException other = new DataIntegrityViolationException("insert failed",
				new RuntimeException("ERROR: insert or update violates foreign key constraint"));
		when(reservationRepository.saveAndFlush(any(Reservation.class))).thenThrow(other);

		assertThatThrownBy(() -> service.create(ownerPrincipal, createRequest())).isSameAs(other);
	}

	@Test
	void createFailsForUnknownSpace() {
		when(spaceRepository.findByIdForUpdate(space.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(ownerPrincipal, createRequest()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void userListsOnlyTheirOwnReservations() {
		Pageable pageable = PageRequest.of(0, 20);
		when(reservationRepository.findByUserId(owner.getId(), pageable))
				.thenReturn(new PageImpl<>(List.of(reservation(ReservationStatus.CONFIRMED))));

		assertThat(service.findAll(ownerPrincipal, pageable)).hasSize(1);
		verify(reservationRepository, never()).findAll(any(Pageable.class));
	}

	@Test
	void adminListsEveryReservation() {
		Pageable pageable = PageRequest.of(0, 20);
		AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "admin@test.com", Role.ADMIN);
		when(reservationRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(reservation(ReservationStatus.CONFIRMED))));

		assertThat(service.findAll(admin, pageable)).hasSize(1);
		verify(reservationRepository, never()).findByUserId(any(), any());
	}

	@Test
	void userCannotSeeSomeoneElsesReservationAndGetsNotFound() {
		Reservation reservation = reservation(ReservationStatus.PENDING_PAYMENT);
		when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
		AuthenticatedUser intruder = new AuthenticatedUser(UUID.randomUUID(), "x@test.com", Role.USER);

		assertThatThrownBy(() -> service.findById(intruder, reservation.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void adminCanCancelAnyReservationAndPublishesAnEvent() {
		Reservation reservation = reservation(ReservationStatus.CONFIRMED);
		when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
		AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "admin@test.com", Role.ADMIN);

		ReservationResponse response = service.cancel(admin, reservation.getId());

		assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
		verify(eventPublisher).publishEvent(new ReservationCancelledEvent(reservation.getId(), space.getId()));
	}

	@Test
	void cancellingATerminalReservationIsRejectedWithoutPublishingEvents() {
		Reservation reservation = reservation(ReservationStatus.CANCELLED);
		when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

		assertThatThrownBy(() -> service.cancel(ownerPrincipal, reservation.getId()))
				.isInstanceOf(InvalidReservationTransitionException.class);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	void anotherUserCannotCancelAndNothingIsPublished() {
		Reservation reservation = reservation(ReservationStatus.PENDING_PAYMENT);
		when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
		AuthenticatedUser intruder = new AuthenticatedUser(UUID.randomUUID(), "x@test.com", Role.USER);

		assertThatThrownBy(() -> service.cancel(intruder, reservation.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}
}
