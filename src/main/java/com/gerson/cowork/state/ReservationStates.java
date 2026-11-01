package com.gerson.cowork.state;

import com.gerson.cowork.enums.ReservationStatus;

/** Maps the persisted status to its (stateless, shared) state object. */
public final class ReservationStates {

	private ReservationStates() {
	}

	public static ReservationState of(ReservationStatus status) {
		return switch (status) {
			case PENDING_PAYMENT -> PendingPaymentState.INSTANCE;
			case CONFIRMED -> ConfirmedState.INSTANCE;
			case CANCELLED -> CancelledState.INSTANCE;
			case COMPLETED -> CompletedState.INSTANCE;
		};
	}
}
