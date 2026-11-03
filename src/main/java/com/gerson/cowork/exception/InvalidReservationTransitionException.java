package com.gerson.cowork.exception;

import com.gerson.cowork.enums.ReservationStatus;

public class InvalidReservationTransitionException extends ConflictException {

	public InvalidReservationTransitionException(String action, ReservationStatus current) {
		super("Cannot " + action + " a reservation in status " + current);
	}
}
