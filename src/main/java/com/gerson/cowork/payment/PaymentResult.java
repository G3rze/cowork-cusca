package com.gerson.cowork.payment;

public record PaymentResult(Outcome outcome, String reference, String reason) {

	public enum Outcome {
		/** The provider validated the payment method. */
		APPROVED,
		/** The provider answered and refused it: a business result, not a failure of the service. */
		DECLINED,
		/** No usable answer (timeout, error, open circuit, full bulkhead): the payment state is unknown. */
		UNAVAILABLE
	}

	public static PaymentResult approved(String reference) {
		return new PaymentResult(Outcome.APPROVED, reference, null);
	}

	public static PaymentResult declined(String reason) {
		return new PaymentResult(Outcome.DECLINED, null, reason);
	}

	public static PaymentResult unavailable(String reason) {
		return new PaymentResult(Outcome.UNAVAILABLE, null, reason);
	}
}
