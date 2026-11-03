package com.gerson.cowork.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class ReservationControllerIntegrationTest extends AbstractIntegrationTest {

	/** Far enough in the future to satisfy @Future, aligned to the hour so slots are easy to reason about. */
	private static final Instant BASE = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static Instant at(int hourOffset) {
		return BASE.plus(hourOffset, ChronoUnit.HOURS);
	}

	/** Every test uses its own space so tests never compete for the same slot. */
	private String newSpace(String adminToken) throws Exception {
		String response = mockMvc.perform(post("/api/v1/spaces")
						.header("Authorization", bearer(adminToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Room " + UUID.randomUUID(), "type", "MEETING_ROOM", "capacity", 6,
								"location", "Floor 3", "hourlyRate", 20.00))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	private ResultActions reserve(String token, String spaceId, Instant start, Instant end) throws Exception {
		return mockMvc.perform(post("/api/v1/reservations")
				.header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("spaceId", spaceId, "startAt", start.toString(), "endAt", end.toString()))));
	}

	private String reserveOk(String token, String spaceId, Instant start, Instant end) throws Exception {
		String response = reserve(token, spaceId, start, end).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	private ResultActions cancel(String token, String reservationId) throws Exception {
		return mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
				.header("Authorization", bearer(token)));
	}

	@Test
	void userCreatesReservationPendingPaymentWithComputedPrice() throws Exception {
		String spaceId = newSpace(adminToken());

		reserve(userToken(), spaceId, at(0), at(2))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.status", is("PENDING_PAYMENT")))
				.andExpect(jsonPath("$.totalPrice", is(40.0)))
				.andExpect(jsonPath("$.spaceId", is(spaceId)));
	}

	@Test
	void longBookingGetsDiscount() throws Exception {
		String spaceId = newSpace(adminToken());

		reserve(userToken(), spaceId, at(0), at(5))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.totalPrice", is(90.0)));
	}

	@Test
	void overlappingReservationIsRejectedWithConflict() throws Exception {
		String spaceId = newSpace(adminToken());
		String token = userToken();
		reserveOk(token, spaceId, at(0), at(2));

		reserve(userToken(), spaceId, at(1), at(3))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("The space is already reserved for the requested time slot"));
	}

	@Test
	void backToBackReservationsAreAccepted() throws Exception {
		String spaceId = newSpace(adminToken());
		String token = userToken();

		reserveOk(token, spaceId, at(0), at(1));
		reserveOk(token, spaceId, at(1), at(2));
	}

	@Test
	void rejectsInvalidRangeAndPastStart() throws Exception {
		String spaceId = newSpace(adminToken());
		String token = userToken();

		reserve(token, spaceId, at(2), at(1))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.endAfterStart").exists());
		reserve(token, spaceId, Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.HOURS))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.startAt").exists());
	}

	@Test
	void unknownSpaceReturnsNotFound() throws Exception {
		reserve(userToken(), UUID.randomUUID().toString(), at(0), at(1)).andExpect(status().isNotFound());
	}

	@Test
	void reservationsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/reservations")).andExpect(status().isUnauthorized());
	}

	@Test
	void userSeesOnlyOwnReservationsAndAdminSeesAll() throws Exception {
		String adminToken = adminToken();
		String spaceId = newSpace(adminToken);
		String ownerToken = userToken();
		String otherToken = userToken();
		String ownerReservation = reserveOk(ownerToken, spaceId, at(0), at(1));
		String otherReservation = reserveOk(otherToken, spaceId, at(1), at(2));

		String ownerList = mockMvc.perform(get("/api/v1/reservations").header("Authorization", bearer(ownerToken)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(ids(ownerList)).containsExactly(ownerReservation);

		String adminList = mockMvc.perform(get("/api/v1/reservations?size=200").header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(ids(adminList)).contains(ownerReservation, otherReservation);
	}

	@Test
	void userCannotReadOrCancelSomeoneElsesReservation() throws Exception {
		String spaceId = newSpace(adminToken());
		String reservationId = reserveOk(userToken(), spaceId, at(0), at(1));
		String intruderToken = userToken();

		mockMvc.perform(get("/api/v1/reservations/" + reservationId).header("Authorization", bearer(intruderToken)))
				.andExpect(status().isNotFound());
		cancel(intruderToken, reservationId).andExpect(status().isNotFound());
	}

	@Test
	void ownerAndAdminCanCancel() throws Exception {
		String adminToken = adminToken();
		String spaceId = newSpace(adminToken);
		String ownerToken = userToken();
		String byOwner = reserveOk(ownerToken, spaceId, at(0), at(1));
		String byAdmin = reserveOk(ownerToken, spaceId, at(1), at(2));

		cancel(ownerToken, byOwner).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
		cancel(adminToken, byAdmin).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
	}

	@Test
	void cancellingTwiceReturnsConflict() throws Exception {
		String spaceId = newSpace(adminToken());
		String token = userToken();
		String reservationId = reserveOk(token, spaceId, at(0), at(1));

		cancel(token, reservationId).andExpect(status().isOk());
		cancel(token, reservationId).andExpect(status().isConflict());
	}

	@Test
	void cancellingFreesTheSlot() throws Exception {
		String spaceId = newSpace(adminToken());
		String token = userToken();
		String reservationId = reserveOk(token, spaceId, at(0), at(2));

		cancel(token, reservationId).andExpect(status().isOk());

		reserveOk(userToken(), spaceId, at(0), at(2));
	}

	private java.util.List<String> ids(String pageJson) throws Exception {
		JsonNode content = objectMapper.readTree(pageJson).get("content");
		java.util.List<String> ids = new java.util.ArrayList<>();
		content.forEach(node -> ids.add(node.get("id").asText()));
		return ids;
	}
}
