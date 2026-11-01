package com.gerson.cowork.exception;

/** The payment provider refused the payment method (HTTP 402). */
public class PaymentDeclinedException extends RuntimeException {

	public PaymentDeclinedException(String reason) {
		super(reason == null ? "Payment declined" : reason);
	}
}
