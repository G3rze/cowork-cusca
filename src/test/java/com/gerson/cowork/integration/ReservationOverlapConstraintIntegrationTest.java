package com.gerson.cowork.integration;

import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.ReservationStatus;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.enums.SpaceType;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.repository.SpaceRepository;
import com.gerson.cowork.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the PostgreSQL exclusion constraint directly, without going through any service. */
class ReservationOverlapConstraintIntegrationTest extends AbstractIntegrationTest {

	private static final Instant TEN = Instant.parse("2030-01-01T10:00:00Z");

	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private SpaceRepository spaceRepository;
	@Autowired
	private UserRepository userRepository;

	private User user;
	private Space space;

	@BeforeEach
	void setUp() {
		user = userRepository.save(User.builder().name("Tester").email(uniqueEmail()).password("x").role(Role.USER).build());
		space = newSpace();
	}

	private Space newSpace() {
		return spaceRepository.save(Space.builder().name("Room " + UUID.randomUUID()).type(SpaceType.MEETING_ROOM)
				.capacity(4).location("Floor 1").hourlyRate(BigDecimal.TEN).build());
	}

	private Reservation reservation(Space target, int startHour, int endHour, ReservationStatus status) {
		return Reservation.builder().space(target).user(user)
				.startAt(TEN.plus(startHour - 10, ChronoUnit.HOURS))
				.endAt(TEN.plus(endHour - 10, ChronoUnit.HOURS))
				.status(status).totalPrice(BigDecimal.TEN).build();
	}

	@Test
	void overlappingActiveReservationsAreRejectedByTheDatabase() {
		reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.PENDING_PAYMENT));

		assertThatThrownBy(() -> reservationRepository.saveAndFlush(reservation(space, 11, 13, ReservationStatus.PENDING_PAYMENT)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.CONFIRMED)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> reservationRepository.saveAndFlush(reservation(space, 9, 13, ReservationStatus.PENDING_PAYMENT)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void backToBackReservationsAreAllowed() {
		reservationRepository.saveAndFlush(reservation(space, 10, 11, ReservationStatus.CONFIRMED));

		Reservation next = reservationRepository.saveAndFlush(reservation(space, 11, 12, ReservationStatus.CONFIRMED));

		assertThat(next.getId()).isNotNull();
	}

	@Test
	void sameSlotInAnotherSpaceIsAllowed() {
		reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.CONFIRMED));

		Reservation other = reservationRepository.saveAndFlush(reservation(newSpace(), 10, 12, ReservationStatus.CONFIRMED));

		assertThat(other.getId()).isNotNull();
	}

	@Test
	void cancelledAndCompletedReservationsDoNotBlockTheSlot() {
		reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.CANCELLED));
		reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.COMPLETED));

		Reservation active = reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.PENDING_PAYMENT));

		assertThat(active.getId()).isNotNull();
	}

	@Test
	void cancellingAReservationFreesItsSlot() {
		Reservation first = reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.PENDING_PAYMENT));
		first.cancel();
		reservationRepository.saveAndFlush(first);

		Reservation second = reservationRepository.saveAndFlush(reservation(space, 10, 12, ReservationStatus.PENDING_PAYMENT));

		assertThat(second.getId()).isNotNull();
	}
}
