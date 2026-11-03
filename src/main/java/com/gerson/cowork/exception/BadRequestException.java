package com.gerson.cowork.exception;

/** The request is well formed but its values are not acceptable (HTTP 400). */
public class BadRequestException extends RuntimeException {

	public BadRequestException(String message) {
		super(message);
	}
}
