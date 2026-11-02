package com.gerson.cowork.integration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActuatorIntegrationTest extends AbstractIntegrationTest {

	@Test
	void anonymousHealthOnlyReportsTheStatus() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("UP")))
				.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	void adminHealthIncludesTheDatabaseAndThePaymentCircuitBreaker() throws Exception {
		mockMvc.perform(get("/actuator/health").header("Authorization", bearer(adminToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.db.status", is("UP")))
				.andExpect(jsonPath("$.components.circuitBreakers.details.payment.details.state", is("CLOSED")));
	}

	@Test
	void circuitBreakersEndpointExposesTheStateWithoutAToken() throws Exception {
		mockMvc.perform(get("/actuator/circuitbreakers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.circuitBreakers.payment.state", is("CLOSED")))
				.andExpect(jsonPath("$.circuitBreakers.payment.failureRateThreshold", is("50.0%")));
	}

	@Test
	void infoIsPublicAndMetricsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/metrics").header("Authorization", bearer(userToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.names").isArray());
		mockMvc.perform(get("/actuator/metrics/resilience4j.circuitbreaker.state")
						.header("Authorization", bearer(adminToken())))
				.andExpect(status().isOk());
	}

	@Test
	void outboxBacklogIsObservable() throws Exception {
		mockMvc.perform(get("/actuator/metrics/cowork.outbox.backlog").header("Authorization", bearer(adminToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.availableTags[0].tag", is("status")));
	}
}
