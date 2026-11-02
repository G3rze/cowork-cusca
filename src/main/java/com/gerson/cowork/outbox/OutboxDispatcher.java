package com.gerson.cowork.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerson.cowork.event.ReservationConfirmedEvent;
import com.gerson.cowork.notification.EmailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Delivers one outbox event to its destination. Each delivery runs on its own virtual thread so that a slow
 * recipient does not delay the rest of the batch.
 * <p>
 * Delivery is at-least-once: an event can be delivered twice (e.g. a worker is slower than its lease). The
 * outbox event id is passed along as the message id so the receiver can deduplicate, like any idempotent consumer.
 */
@Component
public class OutboxDispatcher {

	private final EmailSender emailSender;
	private final ObjectMapper objectMapper;

	public OutboxDispatcher(EmailSender emailSender, ObjectMapper objectMapper) {
		this.emailSender = emailSender;
		this.objectMapper = objectMapper;
	}

	/** Completes exceptionally when the delivery fails, which makes the publisher schedule a retry. */
	@Async("notificationExecutor")
	public CompletableFuture<Void> deliver(OutboxMessage message) {
		if (!OutboxRecorder.RESERVATION_CONFIRMED.equals(message.eventType())) {
			throw new IllegalStateException("Unknown outbox event type: " + message.eventType());
		}
		ReservationConfirmedEvent event = read(message);
		emailSender.send(message.id().toString(), event.userEmail(),
				"Reservation confirmed: " + event.spaceName(),
				"Hi %s, your reservation from %s to %s is confirmed (total %s)."
						.formatted(event.userName(), event.startAt(), event.endAt(), event.totalPrice()));
		return CompletableFuture.completedFuture(null);
	}

	private ReservationConfirmedEvent read(OutboxMessage message) {
		try {
			return objectMapper.readValue(message.payload(), ReservationConfirmedEvent.class);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unreadable outbox payload " + message.id(), ex);
		}
	}
}
