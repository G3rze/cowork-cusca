package com.gerson.cowork.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges delivered events after the retention period. FAILED events are deliberately kept: they are the dead
 * letters someone has to look at. Safe on every replica at once (deleting old rows is idempotent).
 */
@Component
@Slf4j
public class OutboxCleanup {

	private final OutboxRepository repository;
	private final OutboxProperties properties;

	public OutboxCleanup(OutboxRepository repository, OutboxProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval:PT1H}",
			initialDelayString = "${app.idempotency.cleanup-interval:PT1H}")
	public void purgeDelivered() {
		int deleted = repository.deleteSentOlderThan(properties.retention());
		if (deleted > 0) {
			log.info("Purged {} delivered outbox events", deleted);
		}
	}
}
