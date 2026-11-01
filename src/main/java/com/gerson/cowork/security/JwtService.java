package com.gerson.cowork.security;

import com.gerson.cowork.config.AppProperties;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

	private static final String EMAIL_CLAIM = "email";
	private static final String ROLE_CLAIM = "role";

	private final SecretKey key;
	private final Duration expiration;

	public JwtService(AppProperties properties) {
		this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwt().secret()));
		this.expiration = properties.jwt().expiration();
	}

	public String generateToken(User user) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(user.getId().toString())
				.claim(EMAIL_CLAIM, user.getEmail())
				.claim(ROLE_CLAIM, user.getRole().name())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(expiration)))
				.signWith(key)
				.compact();
	}

	public long expiresInSeconds() {
		return expiration.toSeconds();
	}

	/** @throws JwtException if the token is malformed, expired or its signature is invalid */
	public AuthenticatedUser parse(String token) {
		Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
		try {
			return new AuthenticatedUser(
					UUID.fromString(claims.getSubject()),
					claims.get(EMAIL_CLAIM, String.class),
					Role.valueOf(claims.get(ROLE_CLAIM, String.class)));
		} catch (IllegalArgumentException | NullPointerException ex) {
			throw new JwtException("Invalid token claims", ex);
		}
	}
}
