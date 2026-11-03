package com.gerson.cowork.service;

import com.gerson.cowork.dto.reservation.ReservationResponse;

/**
 * Records the definitive response of an idempotent request. It must be called inside the business
 * transaction, so the response is stored atomically with the change it describes: both commit or neither does.
 */
@FunctionalInterface
public interface IdempotencyCompletion {

	/** Used when the request carries no Idempotency-Key. */
	IdempotencyCompletion NONE = (status, body) -> {
	};

	void record(int status, ReservationResponse body);
}
