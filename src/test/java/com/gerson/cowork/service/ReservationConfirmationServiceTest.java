package com.gerson.cowork.service;

import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.ReservationStatus;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.event.ReservationConfirmedEvent;
import com.gerson.cowork.exception.InvalidReservationTransitionException;
import com.gerson.cowork.exception.PaymentDeclinedException;
import com.gerson.cowork.exception.ResourceNotFoundException;
import com.gerson.cowork.mapper.ReservationMapper;
import com.gerson.cowork.payment.PaymentGateway;
import com.gerson.cowork.payment.PaymentResult;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationConfirmationServiceTest {

	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private PaymentGateway paymentGateway;
	@Mock
	private PlatformTransactionManager transactionManager;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private ReservationConfirmationService service;
	private Reservation reservation;
	private AuthenticatedUser owner;

	@BeforeEach
	void setUp() {
		service = new ReservationConfirmationService(reservationRepository, new ReservationAccess(),
				new ReservationMapper(), paymentGateway, transactionManager, eventPublisher);
		User user = User.builder().id(UUID.randomUUID()).name("Ana").email("ana@test.com").role(Role.USER).build();
		Space space = Space.builder().id(UUID.randomUUID()).name("Room A").build();
		reservation = Reservation.builder().id(UUID.randomUUID()).user(user).space(space)
				.startAt(Instant.parse("2030-01-01T10:00:00Z")).endAt(Instant.parse("2030-01-01T11:00:00Z"))
				.totalPrice(new BigDecimal("20.00")).build();
		owner = new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER);
		when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
	}

	@Test
	void approvedPaymentConfirmsStoresTheReferenceAndPublishesTheEvent() {
		when(paymentGateway.validate(reservation.getId(), new BigDecimal("20.00"), "CARD_OK"))
				.thenReturn(PaymentResult.approved("PAY-1"));

		ConfirmationResult result = service.confirm(owner, reservation.getId(), "CARD_OK");

		assertThat(result.confirmed()).isTrue();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(reservation.getPaymentReference()).isEqualTo("PAY-1");
		ArgumentCaptor<ReservationConfirmedEvent> event = ArgumentCaptor.forClass(ReservationConfirmedEvent.class);
		verify(eventPublisher).publishEvent(event.capture());
		assertThat(event.getValue().userEmail()).isEqualTo("ana@test.com");
		assertThat(event.getValue().spaceName()).isEqualTo("Room A");
	}

	@Test
	void paymentIsValidatedBetweenTheTwoTransactions() {
		when(paymentGateway.validate(any(), any(), anyString())).thenReturn(PaymentResult.approved("PAY-1"));

		service.confirm(owner, reservation.getId(), "CARD_OK");

		// begin tx (read) -> commit -> payment call -> begin tx (write) -> commit: the call is outside both
		InOrder order = inOrder(transactionManager, paymentGateway);
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionManager).commit(any());
		order.verify(paymentGateway).validate(any(), any(), anyString());
		order.verify(transactionManager).getTransaction(any());
		order.verify(transactionManager).commit(any());
	}

	@Test
	void declinedPaymentFailsAndLeavesTheReservationPending() {
		when(paymentGateway.validate(any(), any(), anyString())).thenReturn(PaymentResult.declined("No funds"));

		assertThatThrownBy(() -> service.confirm(owner, reservation.getId(), "CARD_DECLINED"))
				.isInstanceOf(PaymentDeclinedException.class)
				.hasMessage("No funds");

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	void unavailablePaymentKeepsThePendingStatusInsteadOfFailing() {
		when(paymentGateway.validate(any(), any(), anyString())).thenReturn(PaymentResult.unavailable("timeout"));

		ConfirmationResult result = service.confirm(owner, reservation.getId(), "CARD_OK");

		assertThat(result.confirmed()).isFalse();
		assertThat(result.reservation().status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
		assertThat(reservation.getPaymentReference()).isNull();
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	void doesNotChargeWhenTheReservationCannotBeConfirmedAnymore() {
		reservation.setStatus(ReservationStatus.CANCELLED);

		assertThatThrownBy(() -> service.confirm(owner, reservation.getId(), "CARD_OK"))
				.isInstanceOf(InvalidReservationTransitionException.class);
		verify(paymentGateway, never()).validate(any(), any(), anyString());
	}

	@Test
	void doesNotChargeForSomeoneElsesReservation() {
		AuthenticatedUser intruder = new AuthenticatedUser(UUID.randomUUID(), "x@test.com", Role.USER);

		assertThatThrownBy(() -> service.confirm(intruder, reservation.getId(), "CARD_OK"))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(paymentGateway, never()).validate(any(), any(), anyString());
	}

	@Test
	void reservationCancelledWhileThePaymentWasInFlightIsNotConfirmed() {
		when(paymentGateway.validate(any(), any(), anyString())).thenAnswer(invocation -> {
			reservation.setStatus(ReservationStatus.CANCELLED); // someone cancels during the remote call
			return PaymentResult.approved("PAY-1");
		});

		assertThatThrownBy(() -> service.confirm(owner, reservation.getId(), "CARD_OK"))
				.isInstanceOf(InvalidReservationTransitionException.class);
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}
}
