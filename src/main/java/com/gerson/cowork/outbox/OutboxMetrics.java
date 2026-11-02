package com.gerson.cowork.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Backlog gauges: a growing {@code pending} means delivery is stuck or slow, and any {@code failed} is a dead
 * letter that needs attention. These are the numbers an alert on the outbox should watch.
 */
@Component
public class OutboxMetrics {

	public OutboxMetrics(MeterRegistry registry, OutboxRepository repository) {
		for (String status : List.of("PENDING", "FAILED")) {
			Gauge.builder("cowork.outbox.backlog", () -> repository.count(status))
					.tag("status", status.toLowerCase())
					.description("Outbox events by status")
					.register(registry);
		}
	}
}
