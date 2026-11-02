package com.gerson.cowork.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.exception.BadRequestException;
import com.gerson.cowork.exception.IdempotencyInProgressException;
import com.gerson.cowork.exception.IdempotencyKeyReuseException;
import com.gerson.cowork.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Makes a non-idempotent POST safe to retry. With an {@code Idempotency-Key}:
 * <ol>
 *   <li>the key is claimed (committed immediately) with a fingerprint of the request;</li>
 *   <li>a retry with the same key and request gets the stored response instead of running again;</li>
 *   <li>the same key with a different request is rejected (422);</li>
 *   <li>a retry that arrives while the first request is still running is rejected (409), never run twice.</li>
 * </ol>
 * Only responses of requests that actually changed something are stored (recorded inside the business
 * transaction). Anything else -- errors, or a "payment unavailable" 202 that changed nothing -- releases the key,
 * so retrying with the same key really retries. This must run outside any transaction.
 */
@Service
@Slf4j
public class IdempotencyService {

	public static final String HEADER = "Idempotency-Key";

	private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

	@FunctionalInterface
	public interface Operation {
		IdempotentResponse run(IdempotencyCompletion completion);
	}

	private final IdempotencyStore store;
	private final IdempotencyProperties properties;
	private final ObjectMapper objectMapper;

	public IdempotencyService(IdempotencyStore store, IdempotencyProperties properties, ObjectMapper objectMapper) {
		this.store = store;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	/** Fingerprint of an operation and its parameters; the same key must always come with the same one. */
	public static String fingerprint(String operation, Object... parts) {
		String canonical = operation + "|" + Stream.of(parts).map(String::valueOf).collect(Collectors.joining("|"));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public IdempotentResponse execute(AuthenticatedUser principal, String key, String fingerprint, Operation operation) {
		if (key == null) {
			return operation.run(IdempotencyCompletion.NONE);
		}
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Idempotency handling must run outside a transaction");
		}
		validate(key);

		Optional<IdempotentResponse> replay = claimOrReplay(principal, key, fingerprint);
		if (replay.isPresent()) {
			return replay.get();
		}
		AtomicBoolean recorded = new AtomicBoolean(false);
		IdempotencyCompletion completion = (status, body) -> {
			store.complete(principal.id(), key, status, toJson(body));
			recorded.set(true);
		};
		try {
			IdempotentResponse response = operation.run(completion);
			if (!recorded.get()) {
				release(principal, key);
			}
			return response;
		} catch (RuntimeException | Error ex) {
			release(principal, key);
			throw ex;
		}
	}

	/** @return the stored response to replay, or empty when this request now owns the key and must run */
	private Optional<IdempotentResponse> claimOrReplay(AuthenticatedUser principal, String key, String fingerprint) {
		// Bounded retry: the existing key can be released by its owner between our claim and our read
		for (int attempt = 0; attempt < 3; attempt++) {
			if (store.claim(principal.id(), key, fingerprint)) {
				return Optional.empty();
			}
			Optional<IdempotencyStore.StoredKey> stored = store.find(principal.id(), key);
			if (stored.isEmpty()) {
				continue;
			}
			if (!stored.get().requestHash().equals(fingerprint)) {
				throw new IdempotencyKeyReuseException();
			}
			if (stored.get().isDone()) {
				return Optional.of(new IdempotentResponse(stored.get().responseStatus(),
						fromJson(stored.get().responseBody()), true));
			}
			if (store.takeOverIfAbandoned(principal.id(), key, properties.processingTimeout())) {
				log.warn("Taking over abandoned idempotency key for user {}", principal.id());
				return Optional.empty();
			}
			throw new IdempotencyInProgressException();
		}
		throw new IdempotencyInProgressException();
	}

	private void validate(String key) {
		if (!VALID_KEY.matcher(key).matches()) {
			throw new BadRequestException(HEADER + " must be 1-64 characters of letters, digits, '.', '_', ':' or '-'");
		}
	}

	private void release(AuthenticatedUser principal, String key) {
		try {
			store.release(principal.id(), key);
		} catch (RuntimeException ex) {
			// The key stays PROCESSING until the processing timeout lets a retry take it over
			log.error("Could not release idempotency key for user {}", principal.id(), ex);
		}
	}

	private String toJson(ReservationResponse body) {
		try {
			return objectMapper.writeValueAsString(body);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private ReservationResponse fromJson(String json) {
		try {
			return objectMapper.readValue(json, ReservationResponse.class);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
