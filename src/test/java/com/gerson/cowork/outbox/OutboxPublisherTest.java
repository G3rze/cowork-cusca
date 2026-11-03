package com.gerson.cowork.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherTest {

	private static final Duration BASE = Duration.ofSeconds(5);
	private static final Duration MAX = Duration.ofMinutes(10);

	@Test
	void backoffDoublesWithEachAttempt() {
		// jitter 0.5 -> factor 1.0, i.e. the exact exponential value
		assertThat(OutboxPublisher.backoff(1, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(5));
		assertThat(OutboxPublisher.backoff(2, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(10));
		assertThat(OutboxPublisher.backoff(3, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(20));
		assertThat(OutboxPublisher.backoff(4, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(40));
	}

	@Test
	void backoffIsCappedAndNeverOverflows() {
		assertThat(OutboxPublisher.backoff(10, BASE, MAX, 0.5)).isEqualTo(MAX);
		assertThat(OutboxPublisher.backoff(1_000, BASE, MAX, 0.5)).isEqualTo(MAX);
	}

	@Test
	void jitterSpreadsTheDelayByTwentyPercentEachWay() {
		assertThat(OutboxPublisher.backoff(2, BASE, MAX, 0.0)).isEqualTo(Duration.ofSeconds(8));
		assertThat(OutboxPublisher.backoff(2, BASE, MAX, 1.0)).isEqualTo(Duration.ofSeconds(12));
	}

	@Test
	void anAttemptNumberBelowOneIsTreatedAsTheFirst() {
		assertThat(OutboxPublisher.backoff(0, BASE, MAX, 0.5)).isEqualTo(Duration.ofSeconds(5));
	}
}
