package com.gerson.cowork.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid @NotNull Jwt jwt, @Valid Admin admin) {

	/** @param secret Base64-encoded HMAC key, at least 256 bits */
	public record Jwt(@NotBlank String secret, @DefaultValue("1h") Duration expiration) {
	}

	/** Optional administrator created at startup when both values are present. */
	public record Admin(String name, String email, String password) {

		public boolean isConfigured() {
			return email != null && !email.isBlank() && password != null && !password.isBlank();
		}
	}
}
