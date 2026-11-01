package com.gerson.cowork.event;

import java.util.UUID;

public record ReservationCancelledEvent(UUID reservationId, UUID spaceId) {
}
