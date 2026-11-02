package com.gerson.cowork.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of idempotency keys. Plain SQL on purpose: the whole point is atomic statements
 * ({@code INSERT ... ON CONFLICT}, conditional {@code UPDATE}) that let the database arbitrate between
 * concurrent requests. Statements join the current transaction when there is one.
 */
@Component
public class IdempotencyStore {

	public record StoredKey(String requestHash, String status, Integer responseStatus, String responseBody) {

		public boolean isDone() {
			return "DONE".equals(status);
		}
	}

	private final JdbcClient jdbc;

	public IdempotencyStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return true if this call created the key, false if it already existed (even if still uncommitted) */
	public boolean claim(UUID userId, String key, String requestHash) {
		return jdbc.sql("""
						INSERT INTO idempotency_keys (user_id, idem_key, request_hash, status)
						VALUES (:userId, :key, :hash, 'PROCESSING')
						ON CONFLICT (user_id, idem_key) DO NOTHING
						""")
				.param("userId", userId).param("key", key).param("hash", requestHash)
				.update() == 1;
	}

	public Optional<StoredKey> find(UUID userId, String key) {
		return jdbc.sql("""
						SELECT request_hash, status, response_status, response_body
						FROM idempotency_keys WHERE user_id = :userId AND idem_key = :key
						""")
				.param("userId", userId).param("key", key)
				.query((rs, row) -> new StoredKey(rs.getString("request_hash"), rs.getString("status"),
						(Integer) rs.getObject("response_status"), rs.getString("response_body")))
				.optional();
	}

	/** Atomically takes over a key left in PROCESSING for longer than the timeout. */
	public boolean takeOverIfAbandoned(UUID userId, String key, Duration processingTimeout) {
		return jdbc.sql("""
						UPDATE idempotency_keys SET created_at = now()
						WHERE user_id = :userId AND idem_key = :key AND status = 'PROCESSING'
						  AND created_at < now() - (CAST(:seconds AS double precision) * interval '1 second')
						""")
				.param("userId", userId).param("key", key).param("seconds", processingTimeout.toSeconds())
				.update() == 1;
	}

	public void complete(UUID userId, String key, int responseStatus, String responseBody) {
		int updated = jdbc.sql("""
						UPDATE idempotency_keys SET status = 'DONE', response_status = :status, response_body = :body
						WHERE user_id = :userId AND idem_key = :key AND status = 'PROCESSING'
						""")
				.param("status", responseStatus).param("body", responseBody)
				.param("userId", userId).param("key", key)
				.update();
		if (updated != 1) {
			// Failing here rolls back the business transaction: better to fail than to run it unprotected
			throw new IllegalStateException("Idempotency key is not in progress anymore");
		}
	}

	/** Frees a key that holds no stored response, so a retry with the same key executes again. */
	public void release(UUID userId, String key) {
		jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = :userId AND idem_key = :key AND status = 'PROCESSING'")
				.param("userId", userId).param("key", key)
				.update();
	}

	public int deleteExpired(Duration ttl) {
		return jdbc.sql("""
						DELETE FROM idempotency_keys
						WHERE created_at < now() - (CAST(:seconds AS double precision) * interval '1 second')
						""")
				.param("seconds", ttl.toSeconds())
				.update();
	}
}
