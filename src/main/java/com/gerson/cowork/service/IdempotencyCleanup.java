package com.gerson.cowork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges expired idempotency keys. Safe to run on every replica at once: deleting expired rows is itself
 * idempotent, so no distributed lock is needed.
 */
@Component
@Slf4j
public class IdempotencyCleanup {

	private final IdempotencyStore store;
	private final IdempotencyProperties properties;

	public IdempotencyCleanup(IdempotencyStore store, IdempotencyProperties properties) {
		this.store = store;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval:PT1H}",
			initialDelayString = "${app.idempotency.cleanup-interval:PT1H}")
	public void purgeExpired() {
		int deleted = store.deleteExpired(properties.ttl());
		if (deleted > 0) {
			log.info("Purged {} expired idempotency keys", deleted);
		}
	}
}
