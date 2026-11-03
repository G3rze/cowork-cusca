package com.gerson.cowork.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerson.cowork.event.ReservationConfirmedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Observer that turns domain events into outbox rows. It is a plain {@code @EventListener} on purpose: it runs
 * synchronously on the publisher's thread, i.e. inside the business transaction, so the row commits (or rolls
 * back) together with the change. This is what removes the dual write: nothing talks to the outside world here.
 */
@Component
public class OutboxRecorder {

	public static final String RESERVATION_CONFIRMED = "ReservationConfirmed";

	private final OutboxRepository repository;
	private final ObjectMapper objectMapper;

	public OutboxRecorder(OutboxRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	@EventListener
	public void onReservationConfirmed(ReservationConfirmedEvent event) {
		try {
			repository.append(UUID.randomUUID(), "Reservation", event.reservationId(), RESERVATION_CONFIRMED,
					objectMapper.writeValueAsString(event));
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Could not serialize " + RESERVATION_CONFIRMED, ex);
		}
	}
}
