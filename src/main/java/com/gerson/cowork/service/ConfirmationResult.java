package com.gerson.cowork.service;

import com.gerson.cowork.dto.reservation.ReservationResponse;

public record ConfirmationResult(ReservationResponse reservation, boolean confirmed) {
}
