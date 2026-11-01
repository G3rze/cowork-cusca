package com.gerson.cowork.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaceControllerIntegrationTest extends AbstractIntegrationTest {

	private Map<String, Object> spaceBody(String name) {
		return Map.of("name", name, "type", "MEETING_ROOM", "capacity", 8, "location", "Floor 2", "hourlyRate", 25.50);
	}

	private String createSpace(String adminToken, String name) throws Exception {
		String response = mockMvc.perform(post("/api/v1/spaces")
						.header("Authorization", bearer(adminToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(spaceBody(name))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	@Test
	void adminCanCreateSpaceAndGetsLocationHeader() throws Exception {
		mockMvc.perform(post("/api/v1/spaces")
						.header("Authorization", bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(spaceBody("Room A"))))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.name", is("Room A")))
				.andExpect(jsonPath("$.hourlyRate", is(25.5)));
	}

	@Test
	void userCannotModifySpaces() throws Exception {
		String adminToken = adminToken();
		String userToken = userToken();
		String id = createSpace(adminToken, "Room B");

		mockMvc.perform(post("/api/v1/spaces")
						.header("Authorization", bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(spaceBody("Forbidden"))))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/v1/spaces/" + id)
						.header("Authorization", bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(spaceBody("Forbidden"))))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/api/v1/spaces/" + id).header("Authorization", bearer(userToken)))
				.andExpect(status().isForbidden());
	}

	@Test
	void userCanReadSpaces() throws Exception {
		String id = createSpace(adminToken(), "Room C");
		String userToken = userToken();

		mockMvc.perform(get("/api/v1/spaces").header("Authorization", bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray());
		mockMvc.perform(get("/api/v1/spaces/" + id).header("Authorization", bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name", is("Room C")));
	}

	@Test
	void adminCanUpdateAndDelete() throws Exception {
		String adminToken = adminToken();
		String id = createSpace(adminToken, "Room D");

		mockMvc.perform(put("/api/v1/spaces/" + id)
						.header("Authorization", bearer(adminToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(spaceBody("Room D renamed"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name", is("Room D renamed")));
		mockMvc.perform(delete("/api/v1/spaces/" + id).header("Authorization", bearer(adminToken)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/spaces/" + id).header("Authorization", bearer(adminToken)))
				.andExpect(status().isNotFound());
	}

	@Test
	void createValidatesPayload() throws Exception {
		mockMvc.perform(post("/api/v1/spaces")
						.header("Authorization", bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "", "type", "MEETING_ROOM", "capacity", 0, "location", "x",
								"hourlyRate", -1))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.name").exists())
				.andExpect(jsonPath("$.fieldErrors.capacity").exists())
				.andExpect(jsonPath("$.fieldErrors.hourlyRate").exists());
	}

	@Test
	void unknownSpaceReturnsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/spaces/" + UUID.randomUUID()).header("Authorization", bearer(adminToken())))
				.andExpect(status().isNotFound());
	}
}
