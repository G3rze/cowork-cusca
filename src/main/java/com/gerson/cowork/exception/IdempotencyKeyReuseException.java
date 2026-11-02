package com.gerson.cowork.exception;

/** The same Idempotency-Key was sent with a different request (HTTP 422). */
public class IdempotencyKeyReuseException extends RuntimeException {

	public IdempotencyKeyReuseException() {
		super("The Idempotency-Key was already used with a different request");
	}
}
