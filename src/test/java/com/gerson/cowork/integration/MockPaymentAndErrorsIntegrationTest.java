package com.gerson.cowork.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The in-app payment mock and the error responses that no other test reaches through HTTP. */
class MockPaymentAndErrorsIntegrationTest extends AbstractIntegrationTest {

	private String validate(String method) throws Exception {
		return json(Map.of("reservationId", UUID.randomUUID(), "amount", 20, "paymentMethod", method));
	}

	@Test
	void mockApprovesWithAReferenceDerivedFromTheReservation() throws Exception {
		mockMvc.perform(post("/mock/payments/validate").contentType(MediaType.APPLICATION_JSON).content(validate("CARD_OK")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.approved", is(true)))
				.andExpect(jsonPath("$.reference", startsWith("PAY-")));
	}

	@Test
	void mockDeclinesAndFailsOnDemand() throws Exception {
		mockMvc.perform(post("/mock/payments/validate").contentType(MediaType.APPLICATION_JSON).content(validate("CARD_DECLINED")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.approved", is(false)));
		mockMvc.perform(post("/mock/payments/validate").contentType(MediaType.APPLICATION_JSON).content(validate("CARD_ERROR")))
				.andExpect(status().isServiceUnavailable());
	}

	@Test
	void deletingASpaceThatHasReservationsIsAConflictNotAServerError() throws Exception {
		String admin = adminToken();
		String spaceResponse = mockMvc.perform(post("/api/v1/spaces").header("Authorization", bearer(admin))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Busy " + UUID.randomUUID(), "type", "DESK", "capacity", 1,
								"location", "F1", "hourlyRate", 5))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String spaceId = objectMapper.readTree(spaceResponse).get("id").asText();
		String start = java.time.Instant.now().plus(150, java.time.temporal.ChronoUnit.DAYS)
				.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
		String end = java.time.Instant.parse(start).plusSeconds(3600).toString();
		mockMvc.perform(post("/api/v1/reservations").header("Authorization", bearer(userToken()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("spaceId", spaceId, "startAt", start, "endAt", end))))
				.andExpect(status().isCreated());

		mockMvc.perform(delete("/api/v1/spaces/" + spaceId).header("Authorization", bearer(admin)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message", is("The operation violates a data integrity constraint")));
	}

	@Test
	void malformedRequestsAreClientErrors() throws Exception {
		String admin = bearer(adminToken());
		mockMvc.perform(post("/api/v1/spaces").header("Authorization", admin)
						.contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/spaces/not-a-uuid").header("Authorization", admin))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/does-not-exist").header("Authorization", admin))
				.andExpect(status().isNotFound());
		mockMvc.perform(patch("/api/v1/spaces/" + UUID.randomUUID()).header("Authorization", admin))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void openApiDocumentIsPublicAndDeclaresTheBearerScheme() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title", is("Cowork API")))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme", is("bearer")))
				.andExpect(jsonPath("$.paths['/api/v1/reservations/{id}/confirm']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/reports/occupancy']").exists())
				// the internal mock must not be documented as part of the public API
				.andExpect(jsonPath("$.paths['/mock/payments/validate']").doesNotExist())
				// login/register are public: they opt out of the global security requirement
				.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty());
	}

	@Test
	void swaggerUiIsReachableWithoutAToken() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
	}
}
