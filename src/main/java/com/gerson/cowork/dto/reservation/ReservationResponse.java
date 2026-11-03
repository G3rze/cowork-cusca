package com.gerson.cowork.dto.reservation;

import com.gerson.cowork.enums.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
		UUID id,
		UUID spaceId,
		String spaceName,
		UUID userId,
		String userName,
		Instant startAt,
		Instant endAt,
		ReservationStatus status,
		BigDecimal totalPrice,
		String paymentReference,
		Instant createdAt) {
}
