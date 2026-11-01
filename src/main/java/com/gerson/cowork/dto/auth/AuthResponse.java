package com.gerson.cowork.dto.auth;

public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds) {

	public static AuthResponse bearer(String token, long expiresInSeconds) {
		return new AuthResponse(token, "Bearer", expiresInSeconds);
	}
}
