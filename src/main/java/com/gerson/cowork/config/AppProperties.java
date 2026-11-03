package com.gerson.cowork.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
		@Valid @NotNull Jwt jwt,
		@Valid Admin admin,
		@Valid @NotNull Payment payment,
		@Valid @DefaultValue BusinessHours businessHours) {

	/** @param secret Base64-encoded HMAC key, at least 256 bits */
	public record Jwt(@NotBlank String secret, @DefaultValue("1h") Duration expiration) {
	}

	/** Optional administrator created at startup when both values are present. */
	public record Admin(String name, String email, String password) {

		public boolean isConfigured() {
			return email != null && !email.isBlank() && password != null && !password.isBlank();
		}
	}

	/**
	 * External payment validation service. The timeouts bound how long a request thread can be held by a slow
	 * dependency; they must stay below the timeout of whoever calls this API.
	 *
	 * @param baseUrl     base URL of the payment API (the in-app mock by default)
	 * @param mockEnabled exposes the in-app mock endpoint; disable it when pointing to a real provider
	 */
	public record Payment(
			@NotBlank String baseUrl,
			@DefaultValue("1s") Duration connectTimeout,
			@DefaultValue("2s") Duration readTimeout,
			@DefaultValue("false") boolean mockEnabled) {
	}

	/**
	 * Daily operating window used as the denominator of the occupancy report. Defining it explicitly avoids
	 * pretending a space is available 24 hours a day.
	 *
	 * @param zone time zone in which {@code opening} and {@code closing} are expressed
	 */
	public record BusinessHours(
			@DefaultValue("08:00") LocalTime opening,
			@DefaultValue("18:00") LocalTime closing,
			@DefaultValue("America/El_Salvador") ZoneId zone) {

		public BusinessHours {
			if (!opening.isBefore(closing)) {
				throw new IllegalArgumentException("app.business-hours.opening must be before app.business-hours.closing");
			}
		}

		public long windowSeconds() {
			return Duration.between(opening, closing).toSeconds();
		}
	}
}
