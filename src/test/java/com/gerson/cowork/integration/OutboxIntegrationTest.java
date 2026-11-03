package com.gerson.cowork.integration;

import com.gerson.cowork.event.ReservationConfirmedEvent;
import com.gerson.cowork.notification.EmailSender;
import com.gerson.cowork.outbox.OutboxCleanup;
import com.gerson.cowork.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The delivery mechanics of the outbox, with the scheduler effectively off (1 h) so every step is driven by the
 * test. Events are inserted directly: this is what is left in the table when the process died after the commit.
 */
@SpringBootTest(properties = {
		"app.outbox.poll-interval=PT1H",
		"app.outbox.base-backoff=PT0.05S",
		"app.outbox.max-backoff=PT0.2S",
		"app.outbox.max-attempts=3",
		"app.outbox.lease=PT30S",
		"app.outbox.retention=PT1H"
})
class OutboxIntegrationTest extends AbstractIntegrationTest {

	record Delivery(String messageId, String to) {
	}

	static final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
	static final AtomicInteger failuresLeft = new AtomicInteger();

	@TestConfiguration
	static class RecordingEmailSenderConfig {
		@Bean
		@Primary
		EmailSender recordingEmailSender() {
			return (messageId, to, subject, body) -> {
				if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
					throw new IllegalStateException("simulated provider outage");
				}
				deliveries.add(new Delivery(messageId, to));
			};
		}
	}

	@Autowired
	private OutboxPublisher publisher;
	@Autowired
	private OutboxCleanup cleanup;
	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void reset() {
		deliveries.clear();
		failuresLeft.set(0);
		// Isolate every test from leftovers of the previous ones
		jdbc.sql("DELETE FROM outbox_events").update();
	}

	private UUID insertEvent(String status, String extraColumns) throws Exception {
		UUID id = UUID.randomUUID();
		UUID reservationId = UUID.randomUUID();
		String payload = objectMapper.writeValueAsString(new ReservationConfirmedEvent(reservationId, "Ana",
				"ana-" + id + "@test.com", "Room A", Instant.parse("2031-01-01T10:00:00Z"),
				Instant.parse("2031-01-01T11:00:00Z"), new BigDecimal("20.00")));
		jdbc.sql("""
						INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status)
						VALUES (:id, 'Reservation', :aggregateId, 'ReservationConfirmed', CAST(:payload AS jsonb), :status)
						""")
				.param("id", id).param("aggregateId", reservationId).param("payload", payload).param("status", status).update();
		if (!extraColumns.isBlank()) {
			jdbc.sql("UPDATE outbox_events SET " + extraColumns + " WHERE id = :id").param("id", id).update();
		}
		return id;
	}

	private UUID insertPending() throws Exception {
		return insertEvent("PENDING", "");
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.sql("SELECT status, attempts, last_error, locked_until, next_attempt_at > now() AS postponed FROM outbox_events WHERE id = :id")
				.param("id", id).query().singleRow();
	}

	@Test
	void anEventLeftBehindByACrashIsDeliveredByTheNextPoll() throws Exception {
		UUID id = insertPending();

		assertThat(publisher.processBatch()).isEqualTo(1);

		assertThat(deliveries).extracting(Delivery::messageId).containsExactly(id.toString());
		assertThat(row(id)).containsEntry("status", "SENT");
		assertThat(publisher.processBatch()).isZero(); // already delivered, nothing left
	}

	@Test
	void aFailedDeliveryIsRetriedWithBackoffUntilItSucceeds() throws Exception {
		failuresLeft.set(2);
		UUID id = insertPending();

		assertThat(publisher.processBatch()).isZero();
		Map<String, Object> afterFirst = row(id);
		assertThat(afterFirst).containsEntry("status", "PENDING").containsEntry("attempts", 1).containsEntry("postponed", true);
		assertThat((String) afterFirst.get("last_error")).contains("simulated provider outage");
		assertThat(afterFirst.get("locked_until")).isNull(); // lease released
		assertThat(publisher.processBatch()).isZero(); // not due yet: the backoff is respected

		await().atMost(java.time.Duration.ofSeconds(5)).until(() -> publisher.processBatch() > 0);

		assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 3);
		assertThat(deliveries).extracting(Delivery::messageId).containsExactly(id.toString());
	}

	@Test
	void anEventThatKeepsFailingIsParkedAsADeadLetter() throws Exception {
		failuresLeft.set(1_000);
		UUID id = insertPending();

		await().atMost(java.time.Duration.ofSeconds(5)).until(() -> {
			publisher.processBatch();
			return "FAILED".equals(row(id).get("status"));
		});

		assertThat(row(id)).containsEntry("attempts", 3);
		assertThat(publisher.processBatch()).isZero(); // dead letters are not retried again
		assertThat(deliveries).isEmpty();
	}

	@Test
	void aWorkerThatDiedMidDeliveryIsReplacedOnceItsLeaseExpires() throws Exception {
		UUID id = insertEvent("PENDING", "attempts = 1, locked_until = now() - interval '5 seconds'");

		assertThat(publisher.processBatch()).isEqualTo(1);

		assertThat(deliveries).extracting(Delivery::messageId).containsExactly(id.toString());
		assertThat(row(id)).containsEntry("attempts", 2);
	}

	@Test
	void anEventLeasedByALiveWorkerIsLeftAlone() throws Exception {
		UUID id = insertEvent("PENDING", "attempts = 1, locked_until = now() + interval '1 minute'");

		assertThat(publisher.processBatch()).isZero();

		assertThat(deliveries).isEmpty();
		assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
	}

	@Test
	void eventsScheduledForTheFutureAreNotDeliveredEarly() throws Exception {
		UUID id = insertEvent("PENDING", "next_attempt_at = now() + interval '1 hour'");

		assertThat(publisher.processBatch()).isZero();
		assertThat(row(id)).containsEntry("status", "PENDING");
	}

	@Test
	void concurrentPublishersNeverDeliverTheSameEventTwice() throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			ids.add(insertPending());
		}

		// Four "replicas" polling at the same time until nothing is left
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<Integer>> workers = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				workers.add(executor.submit(() -> {
					int total = 0;
					int delivered;
					while ((delivered = publisher.processBatch()) > 0) {
						total += delivered;
					}
					return total;
				}));
			}
			int total = 0;
			for (Future<Integer> worker : workers) {
				total += worker.get();
			}
			assertThat(total).isEqualTo(60);
		} finally {
			executor.shutdownNow();
		}

		assertThat(deliveries).extracting(Delivery::messageId)
				.hasSize(60)
				.doesNotHaveDuplicates()
				.containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
	}

	@Test
	void deliveredEventsArePurgedAfterTheRetentionButPendingAndFailedAreKept() throws Exception {
		UUID oldSent = insertEvent("SENT", "sent_at = now() - interval '3 hours'");
		UUID recentSent = insertEvent("SENT", "sent_at = now() - interval '5 minutes'");
		UUID failed = insertEvent("FAILED", "attempts = 3");
		UUID pending = insertEvent("PENDING", "next_attempt_at = now() + interval '1 hour'");

		cleanup.purgeDelivered();

		assertThat(exists(oldSent)).isFalse();
		assertThat(exists(recentSent)).isTrue();
		assertThat(exists(failed)).isTrue();
		assertThat(exists(pending)).isTrue();
	}

	@Test
	void appendingOutsideATransactionIsRejected() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
						new com.gerson.cowork.outbox.OutboxRepository(jdbc)
								.append(UUID.randomUUID(), "Reservation", UUID.randomUUID(), "ReservationConfirmed", "{}"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("inside the business transaction");
	}

	private boolean exists(UUID id) {
		return jdbc.sql("SELECT count(*) FROM outbox_events WHERE id = :id").param("id", id).query(Long.class).single() > 0;
	}
}
