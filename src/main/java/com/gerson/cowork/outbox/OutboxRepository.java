package com.gerson.cowork.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Plain SQL on purpose: the queue semantics ({@code FOR UPDATE SKIP LOCKED}, leases, conditional updates) are
 * the point, and an ORM would hide them.
 */
@Repository
public class OutboxRepository {

	private final JdbcClient jdbc;

	public OutboxRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Appends an event. It must run inside the transaction of the business change, otherwise the whole
	 * guarantee is lost, so a missing transaction is a programming error.
	 */
	public void append(UUID id, String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Outbox events must be written inside the business transaction");
		}
		jdbc.sql("""
						INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
						VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
						""")
				.param("id", id).param("aggregateType", aggregateType).param("aggregateId", aggregateId)
				.param("eventType", eventType).param("payload", payloadJson)
				.update();
	}

	/**
	 * Reserves up to {@code limit} due events for this worker. {@code SKIP LOCKED} lets several replicas poll at
	 * once without waiting for or duplicating each other; the lease (instead of an open transaction) keeps
	 * the claim without holding a database connection while the event is being delivered, and expires if the
	 * worker dies. Events whose lease is still valid are skipped.
	 */
	public List<OutboxMessage> claimBatch(int limit, Duration lease) {
		return jdbc.sql("""
						WITH due AS (
						    SELECT id FROM outbox_events
						    WHERE status = 'PENDING' AND next_attempt_at <= now()
						      AND (locked_until IS NULL OR locked_until < now())
						    ORDER BY created_at
						    LIMIT :limit
						    FOR UPDATE SKIP LOCKED
						)
						UPDATE outbox_events e
						SET locked_until = now() + (CAST(:leaseSeconds AS double precision) * interval '1 second'),
						    attempts = e.attempts + 1
						FROM due
						WHERE e.id = due.id
						RETURNING e.id, e.event_type, CAST(e.payload AS text) AS payload, e.attempts
						""")
				.param("limit", limit).param("leaseSeconds", lease.toMillis() / 1000.0)
				.query((rs, row) -> new OutboxMessage(rs.getObject("id", UUID.class), rs.getString("event_type"),
						rs.getString("payload"), rs.getInt("attempts")))
				.list();
	}

	public void markSent(UUID id) {
		jdbc.sql("""
						UPDATE outbox_events SET status = 'SENT', sent_at = now(), locked_until = NULL, last_error = NULL
						WHERE id = :id
						""")
				.param("id", id).update();
	}

	/** Releases the lease and either schedules a retry or, when {@code giveUp}, parks the event as FAILED. */
	public void markFailedAttempt(UUID id, String error, Duration retryIn, boolean giveUp) {
		jdbc.sql("""
						UPDATE outbox_events
						SET status = :status, locked_until = NULL, last_error = :error,
						    next_attempt_at = now() + (CAST(:delaySeconds AS double precision) * interval '1 second')
						WHERE id = :id
						""")
				.param("status", giveUp ? "FAILED" : "PENDING").param("error", truncate(error))
				.param("delaySeconds", retryIn.toMillis() / 1000.0).param("id", id)
				.update();
	}

	public int deleteSentOlderThan(Duration retention) {
		return jdbc.sql("""
						DELETE FROM outbox_events
						WHERE status = 'SENT' AND sent_at < now() - (CAST(:seconds AS double precision) * interval '1 second')
						""")
				.param("seconds", retention.toSeconds()).update();
	}

	public long count(String status) {
		return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = :status")
				.param("status", status).query(Long.class).single();
	}

	private static String truncate(String text) {
		return text == null || text.length() <= 1000 ? text : text.substring(0, 1000);
	}
}
