package com.gerson.cowork.service;

import com.gerson.cowork.dto.reservation.ReservationResponse;

/** @param replayed true when the response was stored by an earlier request with the same key */
public record IdempotentResponse(int status, ReservationResponse body, boolean replayed) {
}
