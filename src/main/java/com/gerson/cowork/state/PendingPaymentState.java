package com.gerson.cowork.state;

import com.gerson.cowork.enums.ReservationStatus;

final class PendingPaymentState implements ReservationState {

	static final PendingPaymentState INSTANCE = new PendingPaymentState();

	private PendingPaymentState() {
	}

	@Override
	public ReservationStatus status() {
		return ReservationStatus.PENDING_PAYMENT;
	}

	@Override
	public ReservationState confirm() {
		return ConfirmedState.INSTANCE;
	}

	@Override
	public ReservationState cancel() {
		return CancelledState.INSTANCE;
	}
}
