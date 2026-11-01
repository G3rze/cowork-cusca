package com.gerson.cowork.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerson.cowork.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class AbstractIntegrationTest {

	static final String ADMIN_EMAIL = "admin@cowork.local";
	static final String ADMIN_PASSWORD = "Admin12345!";
	static final String PASSWORD = "Password123";

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected ObjectMapper objectMapper;

	protected String json(Object body) throws Exception {
		return objectMapper.writeValueAsString(body);
	}

	protected String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@test.com";
	}

	protected String loginToken(String email, String password) throws Exception {
		String response = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("email", email, "password", password))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("accessToken").asText();
	}

	protected String adminToken() throws Exception {
		return loginToken(ADMIN_EMAIL, ADMIN_PASSWORD);
	}

	protected record TestUser(String email, String token) {
	}

	/** Registers a fresh USER through the public endpoint and returns its token. */
	protected String userToken() throws Exception {
		return newUser().token();
	}

	protected TestUser newUser() throws Exception {
		String email = uniqueEmail();
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Test User", "email", email, "password", PASSWORD))))
				.andExpect(status().isCreated());
		return new TestUser(email, loginToken(email, PASSWORD));
	}

	protected static String bearer(String token) {
		return "Bearer " + token;
	}
}
