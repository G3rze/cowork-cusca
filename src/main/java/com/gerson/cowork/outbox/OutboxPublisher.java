package com.gerson.cowork.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls the outbox and delivers due events. Safe to run on every replica at once: events are claimed with
 * {@code FOR UPDATE SKIP LOCKED} plus a lease, so each one is handled by a single worker at a time.
 * Never runs inside a transaction: no database connection is held while talking to the outside world.
 */
@Component
@Slf4j
public class OutboxPublisher {

	private final OutboxRepository repository;
	private final OutboxDispatcher dispatcher;
	private final OutboxProperties properties;
	private final MeterRegistry meterRegistry;

	public OutboxPublisher(OutboxRepository repository, OutboxDispatcher dispatcher, OutboxProperties properties,
			MeterRegistry meterRegistry) {
		this.repository = repository;
		this.dispatcher = dispatcher;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval:PT2S}", initialDelayString = "${app.outbox.poll-interval:PT2S}")
	public void poll() {
		try {
			processBatch();
		} catch (RuntimeException ex) {
			// A failing poll (e.g. the database is briefly down) must not kill the scheduled task
			log.error("Outbox poll failed", ex);
		}
	}

	/** @return how many events were delivered in this batch */
	public int processBatch() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("The outbox must be processed outside a transaction");
		}
		List<OutboxMessage> batch = repository.claimBatch(properties.batchSize(), properties.lease());
		if (batch.isEmpty()) {
			return 0;
		}
		Map<OutboxMessage, CompletableFuture<Void>> deliveries = new LinkedHashMap<>();
		for (OutboxMessage message : batch) {
			deliveries.put(message, dispatch(message));
		}
		int delivered = 0;
		for (Map.Entry<OutboxMessage, CompletableFuture<Void>> delivery : deliveries.entrySet()) {
			if (await(delivery.getKey(), delivery.getValue())) {
				delivered++;
			}
		}
		return delivered;
	}

	private CompletableFuture<Void> dispatch(OutboxMessage message) {
		try {
			return dispatcher.deliver(message);
		} catch (RuntimeException ex) {
			return CompletableFuture.failedFuture(ex);
		}
	}

	private boolean await(OutboxMessage message, CompletableFuture<Void> delivery) {
		try {
			// Wait less than the lease: past that point another worker is allowed to take the event
			delivery.get(properties.lease().toMillis() / 2, TimeUnit.MILLISECONDS);
			repository.markSent(message.id());
			meterRegistry.counter("cowork.outbox.delivered").increment();
			return true;
		} catch (ExecutionException ex) {
			recordFailure(message, ex.getCause());
		} catch (TimeoutException ex) {
			recordFailure(message, ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			recordFailure(message, ex);
		}
		return false;
	}

	private void recordFailure(OutboxMessage message, Throwable cause) {
		boolean giveUp = message.attempts() >= properties.maxAttempts();
		Duration retryIn = backoff(message.attempts(), properties.baseBackoff(), properties.maxBackoff(),
				ThreadLocalRandom.current().nextDouble());
		repository.markFailedAttempt(message.id(), String.valueOf(cause), retryIn, giveUp);
		if (giveUp) {
			meterRegistry.counter("cowork.outbox.failures", "result", "dead_letter").increment();
			log.error("Outbox event {} parked as FAILED after {} attempts", message.id(), message.attempts(), cause);
		} else {
			meterRegistry.counter("cowork.outbox.failures", "result", "retry").increment();
			log.warn("Outbox event {} failed (attempt {}), retrying in {}: {}", message.id(), message.attempts(),
					retryIn, String.valueOf(cause));
		}
	}

	/**
	 * Exponential backoff with jitter: base * 2^(attempt-1), capped, then spread +-20% so retries from many
	 * events do not all fire at the same instant.
	 *
	 * @param jitter a value in [0, 1)
	 */
	static Duration backoff(int attempts, Duration base, Duration max, double jitter) {
		long exponential = base.toMillis() * (1L << Math.min(Math.max(attempts - 1, 0), 20));
		long capped = Math.min(exponential, max.toMillis());
		return Duration.ofMillis(Math.round(capped * (0.8 + 0.4 * jitter)));
	}
}
