package com.gerson.cowork.security;

import com.gerson.cowork.config.AppProperties;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

	private static final String SECRET = "CUCiEooZn0aVNhYi9IZBgZIiJCGqdMiSpiVbmSonHuinjgGgthYoWaRxloNACwZP";
	private static final String OTHER_SECRET = "T1RIRVJfU0VDUkVUX0tFWV9GT1JfVEVTVElOR19QVVJQT1NFU19PTkxZXzAxMjM0NTY3ODk=";

	private JwtService serviceWith(String secret, Duration expiration) {
		return new JwtService(new AppProperties(new AppProperties.Jwt(secret, expiration), null, null, null));
	}

	private User user() {
		return User.builder().id(UUID.randomUUID()).email("ana@test.com").role(Role.ADMIN).build();
	}

	@Test
	void tokenRoundTripKeepsIdentityAndRole() {
		JwtService service = serviceWith(SECRET, Duration.ofMinutes(5));
		User user = user();

		AuthenticatedUser parsed = service.parse(service.generateToken(user));

		assertThat(parsed.id()).isEqualTo(user.getId());
		assertThat(parsed.email()).isEqualTo("ana@test.com");
		assertThat(parsed.role()).isEqualTo(Role.ADMIN);
	}

	@Test
	void expiredTokenIsRejected() {
		JwtService service = serviceWith(SECRET, Duration.ofSeconds(-1));

		assertThatThrownBy(() -> service.parse(service.generateToken(user()))).isInstanceOf(JwtException.class);
	}

	@Test
	void tokenSignedWithAnotherKeyIsRejected() {
		String token = serviceWith(OTHER_SECRET, Duration.ofMinutes(5)).generateToken(user());

		assertThatThrownBy(() -> serviceWith(SECRET, Duration.ofMinutes(5)).parse(token))
				.isInstanceOf(JwtException.class);
	}
}
