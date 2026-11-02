package com.gerson.cowork.outbox;

import java.util.UUID;

/** An outbox event claimed by a worker. {@code attempts} already counts the delivery about to be made. */
public record OutboxMessage(UUID id, String eventType, String payload, int attempts) {
}
