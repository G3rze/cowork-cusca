package com.gerson.cowork.state;

import com.gerson.cowork.enums.ReservationStatus;

final class ConfirmedState implements ReservationState {

	static final ConfirmedState INSTANCE = new ConfirmedState();

	private ConfirmedState() {
	}

	@Override
	public ReservationStatus status() {
		return ReservationStatus.CONFIRMED;
	}

	@Override
	public ReservationState cancel() {
		return CancelledState.INSTANCE;
	}

	@Override
	public ReservationState complete() {
		return CompletedState.INSTANCE;
	}
}
