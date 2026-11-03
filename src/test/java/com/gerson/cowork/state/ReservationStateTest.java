package com.gerson.cowork.state;

import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.enums.ReservationStatus;
import com.gerson.cowork.exception.InvalidReservationTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.gerson.cowork.enums.ReservationStatus.CANCELLED;
import static com.gerson.cowork.enums.ReservationStatus.COMPLETED;
import static com.gerson.cowork.enums.ReservationStatus.CONFIRMED;
import static com.gerson.cowork.enums.ReservationStatus.PENDING_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationStateTest {

	private Reservation reservationIn(ReservationStatus status) {
		return Reservation.builder().status(status).build();
	}

	@Test
	void newReservationStartsPendingPayment() {
		assertThat(Reservation.builder().build().getStatus()).isEqualTo(PENDING_PAYMENT);
	}

	@Test
	void pendingCanBeConfirmedOrCancelled() {
		Reservation confirmed = reservationIn(PENDING_PAYMENT);
		confirmed.confirm();
		assertThat(confirmed.getStatus()).isEqualTo(CONFIRMED);

		Reservation cancelled = reservationIn(PENDING_PAYMENT);
		cancelled.cancel();
		assertThat(cancelled.getStatus()).isEqualTo(CANCELLED);
	}

	@Test
	void confirmedCanBeCancelledOrCompleted() {
		Reservation cancelled = reservationIn(CONFIRMED);
		cancelled.cancel();
		assertThat(cancelled.getStatus()).isEqualTo(CANCELLED);

		Reservation completed = reservationIn(CONFIRMED);
		completed.complete();
		assertThat(completed.getStatus()).isEqualTo(COMPLETED);
	}

	@Test
	void pendingCannotBeCompleted() {
		assertThatThrownBy(() -> reservationIn(PENDING_PAYMENT).complete())
				.isInstanceOf(InvalidReservationTransitionException.class)
				.hasMessageContaining("complete")
				.hasMessageContaining("PENDING_PAYMENT");
	}

	@Test
	void confirmedCannotBeConfirmedAgain() {
		assertThatThrownBy(() -> reservationIn(CONFIRMED).confirm())
				.isInstanceOf(InvalidReservationTransitionException.class);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationStatus.class, names = {"CANCELLED", "COMPLETED"})
	void terminalStatesRejectEveryTransition(ReservationStatus terminal) {
		assertThatThrownBy(() -> reservationIn(terminal).confirm())
				.isInstanceOf(InvalidReservationTransitionException.class);
		assertThatThrownBy(() -> reservationIn(terminal).cancel())
				.isInstanceOf(InvalidReservationTransitionException.class);
		assertThatThrownBy(() -> reservationIn(terminal).complete())
				.isInstanceOf(InvalidReservationTransitionException.class);
		assertThat(reservationIn(terminal).getStatus()).isEqualTo(terminal);
	}

	@ParameterizedTest
	@EnumSource(ReservationStatus.class)
	void everyStatusHasAStateObjectReportingItself(ReservationStatus status) {
		assertThat(ReservationStates.of(status).status()).isEqualTo(status);
	}
}
