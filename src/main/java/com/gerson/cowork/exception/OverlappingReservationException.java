package com.gerson.cowork.exception;

public class OverlappingReservationException extends ConflictException {

	public OverlappingReservationException() {
		super("The space is already reserved for the requested time slot");
	}
}
