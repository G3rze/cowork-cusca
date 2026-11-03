package com.gerson.cowork.exception;

/** A request with the same Idempotency-Key is still being processed (HTTP 409): never run it twice. */
public class IdempotencyInProgressException extends ConflictException {

	public IdempotencyInProgressException() {
		super("A request with this Idempotency-Key is still being processed, retry shortly");
	}
}
