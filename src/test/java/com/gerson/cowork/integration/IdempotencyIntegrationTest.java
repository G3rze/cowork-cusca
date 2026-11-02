package com.gerson.cowork.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.gerson.cowork.repository.UserRepository;
import com.gerson.cowork.service.IdempotencyCleanup;
import com.gerson.cowork.service.IdempotentReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotency of POST /reservations, against a real PostgreSQL. */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

	private static final Instant BASE = Instant.now().plus(200, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	@Autowired
	private JdbcClient jdbc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private IdempotencyCleanup cleanup;

	private record Account(String email, String token, UUID id) {
	}

	private Account newAccount() throws Exception {
		String email = uniqueEmail();
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("name", "Idem User", "email", email, "password", PASSWORD)))).andExpect(status().isCreated());
		return new Account(email, loginToken(email, PASSWORD), userRepository.findByEmail(email).orElseThrow().getId());
	}

	private String newSpace() throws Exception {
		String response = mockMvc.perform(post("/api/v1/spaces").header("Authorization", bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Room " + UUID.randomUUID(), "type", "MEETING_ROOM", "capacity", 4,
								"location", "Floor 8", "hourlyRate", 10))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	private ResultActions reserve(Account account, String key, String spaceId, int startHour, int endHour) throws Exception {
		var request = post("/api/v1/reservations").header("Authorization", bearer(account.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("spaceId", spaceId, "startAt", BASE.plus(startHour, ChronoUnit.HOURS).toString(),
						"endAt", BASE.plus(endHour, ChronoUnit.HOURS).toString())));
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mockMvc.perform(request);
	}

	private long reservationCount(String spaceId) {
		return jdbc.sql("SELECT count(*) FROM reservations WHERE space_id = :id").param("id", UUID.fromString(spaceId))
				.query(Long.class).single();
	}

	private String newKey() {
		return "key-" + UUID.randomUUID();
	}

	@Test
	void retryWithTheSameKeyReturnsTheOriginalResponseWithoutCreatingAnotherReservation() throws Exception {
		Account account = newAccount();
		String spaceId = newSpace();
		String key = newKey();

		MvcResult first = reserve(account, key, spaceId, 0, 2).andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();
		MvcResult retry = reserve(account, key, spaceId, 0, 2).andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(header().exists("Location")).andReturn();

		assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()))
				.isEqualTo(objectMapper.readTree(first.getResponse().getContentAsString()));
		assertThat(reservationCount(spaceId)).isEqualTo(1);
	}

	@Test
	void withoutAKeyTheRetryStillConflictsAsBefore() throws Exception {
		Account account = newAccount();
		String spaceId = newSpace();

		reserve(account, null, spaceId, 0, 2).andExpect(status().isCreated());
		reserve(account, null, spaceId, 0, 2).andExpect(status().isConflict());
	}

	@Test
	void sameKeyWithADifferentRequestIsRejected() throws Exception {
		Account account = newAccount();
		String spaceId = newSpace();
		String key = newKey();
		reserve(account, key, spaceId, 0, 2).andExpect(status().isCreated());

		reserve(account, key, spaceId, 5, 7).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.message", is("The Idempotency-Key was already used with a different request")));
		assertThat(reservationCount(spaceId)).isEqualTo(1);
	}

	@Test
	void keysAreScopedPerUser() throws Exception {
		Account alice = newAccount();
		Account bob = newAccount();
		String key = newKey();

		String aliceReservation = objectMapper.readTree(reserve(alice, key, newSpace(), 0, 2).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString()).get("id").asText();
		// Same key from another user is a different operation: it must not replay Alice's response
		MvcResult bobResult = reserve(bob, key, newSpace(), 0, 2).andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();

		assertThat(objectMapper.readTree(bobResult.getResponse().getContentAsString()).get("id").asText())
				.isNotEqualTo(aliceReservation);
	}

	@Test
	void failedRequestsAreNotStoredSoTheSameKeyCanBeRetried() throws Exception {
		Account owner = newAccount();
		Account other = newAccount();
		String spaceId = newSpace();
		String blocking = objectMapper.readTree(reserve(owner, newKey(), spaceId, 0, 2).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString()).get("id").asText();
		String key = newKey();

		// The slot is taken: 409, and the key must not keep that failure
		reserve(other, key, spaceId, 0, 2).andExpect(status().isConflict());
		mockMvc.perform(post("/api/v1/reservations/" + blocking + "/cancel").header("Authorization", bearer(owner.token())))
				.andExpect(status().isOk());

		reserve(other, key, spaceId, 0, 2).andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"));
	}

	@Test
	void malformedKeysAreRejected() throws Exception {
		Account account = newAccount();
		String spaceId = newSpace();

		reserve(account, "has spaces!", spaceId, 0, 1).andExpect(status().isBadRequest());
		reserve(account, "x".repeat(65), spaceId, 0, 1).andExpect(status().isBadRequest());
		reserve(account, "", spaceId, 0, 1).andExpect(status().isBadRequest());
		assertThat(reservationCount(spaceId)).isZero();
	}

	@Test
	void concurrentRetriesWithTheSameKeyCreateExactlyOneReservation() throws Exception {
		Account account = newAccount();
		String spaceId = newSpace();
		String key = newKey();
		int requests = 20;

		ExecutorService executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				futures.add(executor.submit(() -> reserve(account, key, spaceId, 0, 2).andReturn()));
			}
			Set<String> reservationIds = new HashSet<>();
			for (Future<MvcResult> future : futures) {
				MvcResult result = future.get();
				int code = result.getResponse().getStatus();
				// Either the first execution, a replay of its response, or "still processing"; never a duplicate
				assertThat(code).isIn(201, 409);
				if (code == 201) {
					JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
					reservationIds.add(body.get("id").asText());
				}
			}
			assertThat(reservationIds).hasSize(1);
			assertThat(reservationCount(spaceId)).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void aRetryWhileTheFirstRequestIsStillProcessingIsRejected() throws Exception {
		Account account = newAccount();
		String spaceId = newSpace();
		String key = newKey();
		jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash, status) VALUES (:u, :k, :h, 'PROCESSING')")
				.param("u", account.id()).param("k", key)
				.param("h", IdempotentReservationService.createFingerprint(UUID.fromString(spaceId),
						BASE, BASE.plus(2, ChronoUnit.HOURS)))
				.update();

		reserve(account, key, spaceId, 0, 2).andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("still being processed")));
		assertThat(reservationCount(spaceId)).isZero();
	}

	@Test
	void anAbandonedProcessingKeyCanBeTakenOverByARetry() throws Exception {
		Account account = newAccount();
		String spaceId = newSpace();
		String key = newKey();
		// Left in PROCESSING for longer than the 2-minute timeout, e.g. the process died mid-request
		jdbc.sql("""
						INSERT INTO idempotency_keys (user_id, idem_key, request_hash, status, created_at)
						VALUES (:u, :k, :h, 'PROCESSING', now() - interval '10 minutes')
						""")
				.param("u", account.id()).param("k", key)
				.param("h", IdempotentReservationService.createFingerprint(UUID.fromString(spaceId),
						BASE, BASE.plus(2, ChronoUnit.HOURS)))
				.update();

		reserve(account, key, spaceId, 0, 2).andExpect(status().isCreated());
		assertThat(reservationCount(spaceId)).isEqualTo(1);
	}

	@Test
	void expiredKeysArePurgedAndRecentOnesKept() throws Exception {
		Account account = newAccount();
		String expired = newKey();
		String recent = newKey();
		for (String[] row : new String[][] {{expired, "30 hours"}, {recent, "1 hour"}}) {
			jdbc.sql("""
							INSERT INTO idempotency_keys (user_id, idem_key, request_hash, status, response_status, response_body, created_at)
							VALUES (:u, :k, 'h', 'DONE', 201, '{}', now() - CAST(:age AS interval))
							""")
					.param("u", account.id()).param("k", row[0]).param("age", row[1]).update();
		}

		cleanup.purgeExpired();

		assertThat(keyExists(account, expired)).isFalse();
		assertThat(keyExists(account, recent)).isTrue();
	}

	private boolean keyExists(Account account, String key) {
		return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = :u AND idem_key = :k")
				.param("u", account.id()).param("k", key).query(Long.class).single() > 0;
	}
}
