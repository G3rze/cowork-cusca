package com.gerson.cowork.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIntegrationTest extends AbstractIntegrationTest {

	@Test
	void registerCreatesUserWithoutExposingPassword() throws Exception {
		String email = uniqueEmail();

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Ana", "email", email, "password", PASSWORD))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email", is(email)))
				.andExpect(jsonPath("$.role", is("USER")))
				.andExpect(jsonPath("$", not(hasKey("password"))));
	}

	@Test
	void registerRejectsDuplicatedEmail() throws Exception {
		String email = uniqueEmail();
		String body = json(Map.of("name", "Ana", "email", email, "password", PASSWORD));

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict());
	}

	@Test
	void registerValidatesPayload() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "", "email", "not-an-email", "password", "short"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.email").exists())
				.andExpect(jsonPath("$.fieldErrors.password").exists())
				.andExpect(jsonPath("$.fieldErrors.name").exists());
	}

	@Test
	void loginReturnsBearerToken() throws Exception {
		String email = uniqueEmail();
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Ana", "email", email, "password", PASSWORD))))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("email", email, "password", PASSWORD))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType", is("Bearer")));
	}

	@Test
	void loginRejectsWrongPassword() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("email", ADMIN_EMAIL, "password", "wrong-password"))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void protectedEndpointRequiresValidToken() throws Exception {
		mockMvc.perform(get("/api/v1/spaces")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/spaces").header("Authorization", "Bearer not.a.token"))
				.andExpect(status().isUnauthorized());
	}
}
