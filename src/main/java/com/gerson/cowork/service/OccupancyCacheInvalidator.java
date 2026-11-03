package com.gerson.cowork.service;

import com.gerson.cowork.event.ReservationCancelledEvent;
import com.gerson.cowork.event.ReservationConfirmedEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Observer that keeps the cached occupancy report coherent. Evicting after the commit (not before) matters:
 * evicting earlier would let a concurrent request re-cache the old data before the change is visible.
 */
@Component
public class OccupancyCacheInvalidator {

	private final CacheManager cacheManager;

	public OccupancyCacheInvalidator(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onConfirmed(ReservationConfirmedEvent event) {
		evict();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCancelled(ReservationCancelledEvent event) {
		evict();
	}

	private void evict() {
		Cache cache = cacheManager.getCache(OccupancyReportService.CACHE_NAME);
		if (cache != null) {
			cache.clear();
		}
	}
}
