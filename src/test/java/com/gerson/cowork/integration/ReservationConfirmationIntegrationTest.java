package com.gerson.cowork.integration;

import com.gerson.cowork.notification.EmailSender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the confirm flow against a controllable fake payment provider (a real HTTP server), so timeouts,
 * failures, the circuit breaker and the bulkhead are tested for real instead of being mocked.
 */
@SpringBootTest(properties = {
		"app.payment.read-timeout=500ms",
		"resilience4j.circuitbreaker.instances.payment.sliding-window-size=4",
		"resilience4j.circuitbreaker.instances.payment.minimum-number-of-calls=4",
		"resilience4j.circuitbreaker.instances.payment.wait-duration-in-open-state=1s",
		"resilience4j.circuitbreaker.instances.payment.permitted-number-of-calls-in-half-open-state=2",
		"resilience4j.bulkhead.instances.payment.max-concurrent-calls=3"
})
class ReservationConfirmationIntegrationTest extends AbstractIntegrationTest {

	private static final Instant BASE = Instant.now().plus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
	private static final int BULKHEAD_LIMIT = 3;

	private enum Behavior { APPROVE, DECLINE, ERROR, SLOW }

	private static final AtomicReference<Behavior> behavior = new AtomicReference<>(Behavior.APPROVE);
	private static final AtomicInteger calls = new AtomicInteger();
	private static final AtomicInteger inFlight = new AtomicInteger();
	private static final AtomicInteger maxInFlight = new AtomicInteger();
	private static final AtomicInteger maxDbConnectionsDuringCall = new AtomicInteger();
	private static final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();
	private static final AtomicReference<HikariDataSource> dataSourceRef = new AtomicReference<>();
	private static volatile long handlerDelayMillis = 0;
	private static final HttpServer server = startServer();

	/** Slow, recording replacement for the email sender, to prove notifications are asynchronous. */
	record SentEmail(String to, String subject, Thread thread) {
	}

	static final List<SentEmail> sentEmails = new CopyOnWriteArrayList<>();

	@TestConfiguration
	static class SlowEmailSenderConfig {
		@Bean
		@Primary
		EmailSender slowEmailSender() {
			return (to, subject, body) -> {
				sleep(1_500);
				sentEmails.add(new SentEmail(to, subject, Thread.currentThread()));
			};
		}
	}

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;
	@Autowired
	private DataSource dataSource;

	private static HttpServer startServer() {
		try {
			HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			httpServer.setExecutor(Executors.newCachedThreadPool());
			httpServer.createContext("/mock/payments/validate", ReservationConfirmationIntegrationTest::handle);
			httpServer.start();
			return httpServer;
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void handle(HttpExchange exchange) throws IOException {
		calls.incrementAndGet();
		maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
		try {
			lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			HikariDataSource ds = dataSourceRef.get();
			if (ds != null) {
				// While the payment is being validated no request should be holding a DB connection
				maxDbConnectionsDuringCall.accumulateAndGet(ds.getHikariPoolMXBean().getActiveConnections(), Math::max);
			}
			exchange.getRequestBody().readAllBytes();
			sleep(handlerDelayMillis);
			Behavior current = behavior.get();
			if (current == Behavior.SLOW) {
				sleep(1_500);
			}
			if (current == Behavior.ERROR) {
				exchange.sendResponseHeaders(503, -1);
				return;
			}
			String body = current == Behavior.DECLINE
					? "{\"approved\":false,\"reference\":null,\"reason\":\"Payment method declined\"}"
					: "{\"approved\":true,\"reference\":\"PAY-TEST\",\"reason\":null}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
		} finally {
			inFlight.decrementAndGet();
			exchange.close();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@DynamicPropertySource
	static void paymentProvider(DynamicPropertyRegistry registry) {
		registry.add("app.payment.base-url", () -> "http://localhost:" + server.getAddress().getPort() + "/mock/payments");
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@BeforeEach
	void resetFakeProviderAndCircuit() {
		behavior.set(Behavior.APPROVE);
		handlerDelayMillis = 0;
		calls.set(0);
		maxInFlight.set(0);
		maxDbConnectionsDuringCall.set(0);
		lastIdempotencyKey.set(null);
		sentEmails.clear();
		dataSourceRef.set((HikariDataSource) dataSource);
		circuitBreakerRegistry.circuitBreaker("payment").reset();
	}

	// ---- helpers ----

	private String newSpace() throws Exception {
		String response = mockMvc.perform(post("/api/v1/spaces")
						.header("Authorization", bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Room " + UUID.randomUUID(), "type", "MEETING_ROOM", "capacity", 6,
								"location", "Floor 5", "hourlyRate", 20.00))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	private String reserve(String token, String spaceId, int hourOffset) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reservations")
						.header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("spaceId", spaceId,
								"startAt", BASE.plus(hourOffset, ChronoUnit.HOURS).toString(),
								"endAt", BASE.plus(hourOffset + 1, ChronoUnit.HOURS).toString()))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	private ResultActions confirm(String token, String reservationId) throws Exception {
		return mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("paymentMethod", "CARD_OK"))));
	}

	private ResultActions confirmWithKey(String token, String reservationId, String key, String paymentMethod) throws Exception {
		return mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm")
				.header("Authorization", bearer(token))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("paymentMethod", paymentMethod))));
	}

	private void assertStatus(String token, String reservationId, String expected) throws Exception {
		mockMvc.perform(get("/api/v1/reservations/" + reservationId).header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is(expected)));
	}

	// ---- tests ----

	@Test
	void approvedPaymentConfirmsTheReservation() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);

		confirm(token, reservationId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("CONFIRMED")))
				.andExpect(jsonPath("$.paymentReference", is("PAY-TEST")));

		assertThat(lastIdempotencyKey.get()).isEqualTo(reservationId);
		assertStatus(token, reservationId, "CONFIRMED");
	}

	@Test
	void noDatabaseConnectionIsHeldWhileThePaymentIsValidated() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);

		confirm(token, reservationId).andExpect(status().isOk());

		assertThat(calls.get()).isEqualTo(1);
		assertThat(maxDbConnectionsDuringCall.get()).isZero();
	}

	@Test
	void declinedPaymentReturns402AndKeepsPendingPayment() throws Exception {
		behavior.set(Behavior.DECLINE);
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);

		confirm(token, reservationId).andExpect(status().isPaymentRequired());

		assertStatus(token, reservationId, "PENDING_PAYMENT");
	}

	@Test
	void providerFailureFallsBackToPendingPaymentInsteadOfFailingTheRequest() throws Exception {
		behavior.set(Behavior.ERROR);
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);

		confirm(token, reservationId)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status", is("PENDING_PAYMENT")));

		assertStatus(token, reservationId, "PENDING_PAYMENT");
	}

	@Test
	void slowProviderIsCutByTheReadTimeout() throws Exception {
		behavior.set(Behavior.SLOW);
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);

		long started = System.nanoTime();
		confirm(token, reservationId).andExpect(status().isAccepted());
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

		// The provider needs 1.5 s; the client gives up after 500 ms
		assertThat(elapsedMillis).isLessThan(1_300);
		assertStatus(token, reservationId, "PENDING_PAYMENT");
	}

	@Test
	void circuitOpensAfterRepeatedFailuresThenRecovers() throws Exception {
		behavior.set(Behavior.ERROR);
		String token = userToken();
		String spaceId = newSpace();
		List<String> reservations = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			reservations.add(reserve(token, spaceId, i));
		}

		for (int i = 0; i < 4; i++) {
			confirm(token, reservations.get(i)).andExpect(status().isAccepted());
		}
		CircuitBreaker circuit = circuitBreakerRegistry.circuitBreaker("payment");
		assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		// Open circuit: fails fast, the provider is not even called, and the answer is still the fallback
		int callsBefore = calls.get();
		confirm(token, reservations.get(4)).andExpect(status().isAccepted());
		assertThat(calls.get()).isEqualTo(callsBefore);

		// After the wait the circuit lets a few probes through; successes close it again
		behavior.set(Behavior.APPROVE);
		Thread.sleep(1_300);
		confirm(token, reservations.get(5)).andExpect(status().isOk());
		confirm(token, reservations.get(6)).andExpect(status().isOk());
		assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void bulkheadCapsConcurrentCallsToTheProvider() throws Exception {
		handlerDelayMillis = 300;
		String token = userToken();
		String spaceId = newSpace();
		int requests = 10;
		List<String> reservations = new ArrayList<>();
		for (int i = 0; i < requests; i++) {
			reservations.add(reserve(token, spaceId, i));
		}

		ExecutorService executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (String reservationId : reservations) {
				results.add(executor.submit(() -> confirm(token, reservationId).andReturn().getResponse().getStatus()));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}

			assertThat(maxInFlight.get()).isLessThanOrEqualTo(BULKHEAD_LIMIT);
			// Admitted calls are confirmed (200); the rest are shed to the fallback (202), never a 5xx
			assertThat(statuses).contains(200).contains(202).allMatch(code -> code == 200 || code == 202);
			// Shedding load is not a provider failure, so the circuit must stay closed
			assertThat(circuitBreakerRegistry.circuitBreaker("payment").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void cannotConfirmTwiceNorSomeoneElsesReservation() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);

		confirm(userToken(), reservationId).andExpect(status().isNotFound());
		confirm(token, reservationId).andExpect(status().isOk());
		int callsAfterFirst = calls.get();
		confirm(token, reservationId).andExpect(status().isConflict());

		// The second attempt is rejected before charging again
		assertThat(calls.get()).isEqualTo(callsAfterFirst);
	}

	@Test
	void cancelledReservationCannotBeConfirmed() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);
		mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel").header("Authorization", bearer(token)))
				.andExpect(status().isOk());

		confirm(token, reservationId).andExpect(status().isConflict());
		assertThat(calls.get()).isZero();
	}

	@Test
	void paymentMethodIsRequired() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);

		mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm")
						.header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.paymentMethod").exists());
	}

	/** Emails are asynchronous, so a late one from a previous test may still arrive: only look at our own. */
	private List<SentEmail> emailsTo(String recipient) {
		return sentEmails.stream().filter(email -> email.to().equals(recipient)).toList();
	}

	@Test
	void confirmationEmailIsSentAsynchronouslyAfterCommit() throws Exception {
		TestUser user = newUser();
		String reservationId = reserve(user.token(), newSpace(), 0);

		long started = System.nanoTime();
		confirm(user.token(), reservationId).andExpect(status().isOk());
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

		// The email takes 1.5 s to send, but the HTTP response does not wait for it
		assertThat(elapsedMillis).isLessThan(1_000);
		assertThat(emailsTo(user.email())).isEmpty();
		await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> assertThat(emailsTo(user.email())).hasSize(1));
		SentEmail email = emailsTo(user.email()).get(0);
		assertThat(email.subject()).startsWith("Reservation confirmed");
		assertThat(email.thread().isVirtual()).isTrue();
		assertThat(email.thread().getName()).startsWith("notification-");
	}

	@Test
	void noEmailIsSentWhenTheConfirmationDoesNotHappen() throws Exception {
		TestUser user = newUser();
		String spaceId = newSpace();

		behavior.set(Behavior.DECLINE);
		confirm(user.token(), reserve(user.token(), spaceId, 0)).andExpect(status().isPaymentRequired());
		behavior.set(Behavior.ERROR);
		confirm(user.token(), reserve(user.token(), spaceId, 1)).andExpect(status().isAccepted());

		// Wait longer than the email would take if one had been triggered
		Thread.sleep(2_200);
		assertThat(emailsTo(user.email())).isEmpty();
	}

	// ---- idempotency of /confirm ----

	@Test
	void retryingAConfirmWithTheSameKeyDoesNotChargeTwice() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);
		String key = "confirm-" + UUID.randomUUID();

		confirmWithKey(token, reservationId, key, "CARD_OK").andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("CONFIRMED")));
		confirmWithKey(token, reservationId, key, "CARD_OK").andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.status", is("CONFIRMED")))
				.andExpect(jsonPath("$.paymentReference", is("PAY-TEST")));

		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void sameKeyWithAnotherPaymentMethodOrReservationIsRejected() throws Exception {
		String token = userToken();
		String spaceId = newSpace();
		String first = reserve(token, spaceId, 0);
		String second = reserve(token, spaceId, 1);
		String key = "confirm-" + UUID.randomUUID();
		confirmWithKey(token, first, key, "CARD_OK").andExpect(status().isOk());

		confirmWithKey(token, first, key, "OTHER_CARD").andExpect(status().isUnprocessableEntity());
		confirmWithKey(token, second, key, "CARD_OK").andExpect(status().isUnprocessableEntity());
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void anUnavailableProviderDoesNotConsumeTheKeySoTheRetryReallyRetries() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);
		String key = "confirm-" + UUID.randomUUID();

		behavior.set(Behavior.ERROR);
		confirmWithKey(token, reservationId, key, "CARD_OK").andExpect(status().isAccepted());

		// The provider recovers: the same key now executes again instead of replaying the 202
		behavior.set(Behavior.APPROVE);
		confirmWithKey(token, reservationId, key, "CARD_OK").andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("CONFIRMED")));
		assertThat(calls.get()).isEqualTo(2);
	}

	@Test
	void aDeclinedPaymentIsNotStoredEither() throws Exception {
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);
		String key = "confirm-" + UUID.randomUUID();

		behavior.set(Behavior.DECLINE);
		confirmWithKey(token, reservationId, key, "CARD_OK").andExpect(status().isPaymentRequired());
		behavior.set(Behavior.APPROVE);
		confirmWithKey(token, reservationId, key, "CARD_OK").andExpect(status().isOk());
	}

	@Test
	void concurrentConfirmsWithTheSameKeyCallTheProviderOnlyOnce() throws Exception {
		handlerDelayMillis = 300; // keep the first request in flight while the others arrive
		String token = userToken();
		String reservationId = reserve(token, newSpace(), 0);
		String key = "confirm-" + UUID.randomUUID();
		int requests = 8;

		ExecutorService executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				results.add(executor.submit(() -> confirmWithKey(token, reservationId, key, "CARD_OK")
						.andReturn().getResponse().getStatus()));
			}
			List<Integer> codes = new ArrayList<>();
			for (Future<Integer> result : results) {
				codes.add(result.get());
			}

			// One execution (200), replays (200) or "still processing" (409): never a second charge
			assertThat(codes).contains(200).allMatch(code -> code == 200 || code == 409);
			assertThat(calls.get()).isEqualTo(1);
			assertStatus(token, reservationId, "CONFIRMED");
		} finally {
			executor.shutdownNow();
		}
	}
}
