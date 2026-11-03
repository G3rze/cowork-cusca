package com.gerson.cowork.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a reservation is confirmed. It carries plain values instead of the JPA entity,
 * so listeners running after the transaction (and on other threads) never touch lazy associations.
 */
public record ReservationConfirmedEvent(
		UUID reservationId,
		String userName,
		String userEmail,
		String spaceName,
		Instant startAt,
		Instant endAt,
		BigDecimal totalPrice) {
}
