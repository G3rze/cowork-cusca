package com.gerson.cowork.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.exception.BadRequestException;
import com.gerson.cowork.exception.IdempotencyInProgressException;
import com.gerson.cowork.exception.IdempotencyKeyReuseException;
import com.gerson.cowork.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

	private static final String KEY = "abc-123";

	@Mock
	private IdempotencyStore store;

	private IdempotencyService service;
	private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "u@test.com", Role.USER);
	private final ReservationResponse body = new ReservationResponse(UUID.randomUUID(), UUID.randomUUID(), "Room",
			user.id(), "U", null, null, null, null, null, null);

	@BeforeEach
	void setUp() {
		service = new IdempotencyService(store,
				new IdempotencyProperties(Duration.ofHours(24), Duration.ofMinutes(2), Duration.ofHours(1)),
				new ObjectMapper().findAndRegisterModules());
	}

	@Test
	void fingerprintIsDeterministicAndSensitiveToEveryPart() {
		assertThat(IdempotencyService.fingerprint("op", "a", 1)).isEqualTo(IdempotencyService.fingerprint("op", "a", 1));
		assertThat(IdempotencyService.fingerprint("op", "a", 1)).isNotEqualTo(IdempotencyService.fingerprint("op", "a", 2));
		assertThat(IdempotencyService.fingerprint("op", "a", 1)).isNotEqualTo(IdempotencyService.fingerprint("other", "a", 1));
		assertThat(IdempotencyService.fingerprint("op", "a")).hasSize(64);
	}

	@Test
	void withoutAKeyTheOperationJustRunsAndTouchesNoStorage() {
		AtomicInteger runs = new AtomicInteger();

		service.execute(user, null, "fp", completion -> {
			runs.incrementAndGet();
			return new IdempotentResponse(201, body, false);
		});

		assertThat(runs).hasValue(1);
		verify(store, never()).claim(any(), anyString(), anyString());
	}

	@Test
	void anOperationThatRecordsItsResponseKeepsTheKey() {
		when(store.claim(user.id(), KEY, "fp")).thenReturn(true);

		service.execute(user, KEY, "fp", completion -> {
			completion.record(201, body);
			return new IdempotentResponse(201, body, false);
		});

		verify(store).complete(any(), anyString(), anyInt(), anyString());
		verify(store, never()).release(any(), anyString());
	}

	@Test
	void anOperationThatRecordsNothingReleasesTheKey() {
		when(store.claim(user.id(), KEY, "fp")).thenReturn(true);

		service.execute(user, KEY, "fp", completion -> new IdempotentResponse(202, body, false));

		verify(store).release(user.id(), KEY);
	}

	@Test
	void aFailingOperationReleasesTheKeyAndPropagatesTheError() {
		when(store.claim(user.id(), KEY, "fp")).thenReturn(true);
		IllegalStateException failure = new IllegalStateException("boom");

		assertThatThrownBy(() -> service.execute(user, KEY, "fp", completion -> {
			throw failure;
		})).isSameAs(failure);

		verify(store).release(user.id(), KEY);
	}

	@Test
	void aStoredResponseIsReplayedWithoutRunningTheOperation() throws Exception {
		when(store.claim(user.id(), KEY, "fp")).thenReturn(false);
		String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(body);
		when(store.find(user.id(), KEY)).thenReturn(Optional.of(new IdempotencyStore.StoredKey("fp", "DONE", 201, json)));
		AtomicInteger runs = new AtomicInteger();

		IdempotentResponse response = service.execute(user, KEY, "fp", completion -> {
			runs.incrementAndGet();
			return new IdempotentResponse(201, body, false);
		});

		assertThat(runs).hasValue(0);
		assertThat(response.replayed()).isTrue();
		assertThat(response.status()).isEqualTo(201);
		assertThat(response.body().id()).isEqualTo(body.id());
	}

	@Test
	void aDifferentRequestWithTheSameKeyIsRejected() {
		when(store.claim(user.id(), KEY, "fp")).thenReturn(false);
		when(store.find(user.id(), KEY)).thenReturn(Optional.of(new IdempotencyStore.StoredKey("other", "DONE", 201, "{}")));

		assertThatThrownBy(() -> service.execute(user, KEY, "fp", completion -> null))
				.isInstanceOf(IdempotencyKeyReuseException.class);
	}

	@Test
	void aRecentProcessingKeyIsReportedAsInProgress() {
		when(store.claim(user.id(), KEY, "fp")).thenReturn(false);
		when(store.find(user.id(), KEY)).thenReturn(Optional.of(new IdempotencyStore.StoredKey("fp", "PROCESSING", null, null)));
		when(store.takeOverIfAbandoned(user.id(), KEY, Duration.ofMinutes(2))).thenReturn(false);

		assertThatThrownBy(() -> service.execute(user, KEY, "fp", completion -> null))
				.isInstanceOf(IdempotencyInProgressException.class);
	}

	@Test
	void anAbandonedProcessingKeyIsTakenOver() {
		when(store.claim(user.id(), KEY, "fp")).thenReturn(false);
		when(store.find(user.id(), KEY)).thenReturn(Optional.of(new IdempotencyStore.StoredKey("fp", "PROCESSING", null, null)));
		when(store.takeOverIfAbandoned(user.id(), KEY, Duration.ofMinutes(2))).thenReturn(true);
		AtomicInteger runs = new AtomicInteger();

		service.execute(user, KEY, "fp", completion -> {
			runs.incrementAndGet();
			completion.record(201, body);
			return new IdempotentResponse(201, body, false);
		});

		assertThat(runs).hasValue(1);
	}

	@Test
	void invalidKeysAreRejectedBeforeTouchingStorage() {
		for (String bad : new String[] {"", "has space", "ñ", "x".repeat(65), "a/b"}) {
			assertThatThrownBy(() -> service.execute(user, bad, "fp", completion -> null))
					.isInstanceOf(BadRequestException.class);
		}
		verify(store, never()).claim(any(), anyString(), anyString());
	}
}
