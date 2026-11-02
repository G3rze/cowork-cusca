package com.gerson.cowork.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param pollInterval how often the publisher looks for due events
 * @param batchSize    events claimed per poll
 * @param lease        how long a claimed event is reserved for its worker; must exceed the time a delivery takes,
 *                     otherwise another worker may deliver it too
 * @param maxAttempts  deliveries tried before the event is parked as FAILED (dead letter)
 * @param baseBackoff  delay after the first failure; doubles on each following one
 * @param maxBackoff   upper bound of the retry delay
 * @param retention    how long delivered events are kept before being purged
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
		@NotNull @DefaultValue("PT2S") Duration pollInterval,
		@Min(1) @DefaultValue("20") int batchSize,
		@NotNull @DefaultValue("PT1M") Duration lease,
		@Min(1) @DefaultValue("8") int maxAttempts,
		@NotNull @DefaultValue("PT5S") Duration baseBackoff,
		@NotNull @DefaultValue("PT10M") Duration maxBackoff,
		@NotNull @DefaultValue("P7D") Duration retention) {
}
