package com.gerson.cowork.dto.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmReservationRequest(@NotBlank @Size(max = 50) String paymentMethod) {
}
