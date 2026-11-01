package com.gerson.cowork.state;

import com.gerson.cowork.enums.ReservationStatus;

/** Terminal state: every transition is rejected by the interface defaults. */
final class CancelledState implements ReservationState {

	static final CancelledState INSTANCE = new CancelledState();

	private CancelledState() {
	}

	@Override
	public ReservationStatus status() {
		return ReservationStatus.CANCELLED;
	}
}
