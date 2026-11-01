package com.gerson.cowork.exception;

/** Business conflict that maps to HTTP 409. */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}
}
