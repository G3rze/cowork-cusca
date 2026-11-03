package com.gerson.cowork.state;

import com.gerson.cowork.enums.ReservationStatus;

/** Terminal state: every transition is rejected by the interface defaults. */
final class CompletedState implements ReservationState {

	static final CompletedState INSTANCE = new CompletedState();

	private CompletedState() {
	}

	@Override
	public ReservationStatus status() {
		return ReservationStatus.COMPLETED;
	}
}
