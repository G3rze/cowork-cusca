package com.gerson.cowork.service;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param ttl               how long a stored response can be replayed before the key is purged
 * @param processingTimeout a key still PROCESSING after this long is considered abandoned (e.g. the process
 *                          died mid-request) and can be taken over by a retry
 * @param cleanupInterval   how often expired keys are deleted
 */
@Validated
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(
		@NotNull @DefaultValue("24h") Duration ttl,
		@NotNull @DefaultValue("2m") Duration processingTimeout,
		@NotNull @DefaultValue("PT1H") Duration cleanupInterval) {
}
