package com.gerson.cowork.state;

import com.gerson.cowork.enums.ReservationStatus;
import com.gerson.cowork.exception.InvalidReservationTransitionException;

/**
 * State pattern for the reservation lifecycle. Each state decides which transitions are valid and which
 * state comes next; by default every transition is rejected, so a state only declares what it allows.
 */
public interface ReservationState {

	ReservationStatus status();

	default ReservationState confirm() {
		throw new InvalidReservationTransitionException("confirm", status());
	}

	default ReservationState cancel() {
		throw new InvalidReservationTransitionException("cancel", status());
	}

	default ReservationState complete() {
		throw new InvalidReservationTransitionException("complete", status());
	}
}
