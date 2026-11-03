package com.gerson.cowork.service;

import com.gerson.cowork.dto.auth.LoginRequest;
import com.gerson.cowork.dto.auth.RegisterRequest;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.exception.ConflictException;
import com.gerson.cowork.mapper.UserMapper;
import com.gerson.cowork.repository.UserRepository;
import com.gerson.cowork.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private UserRepository userRepository;
	@Mock
	private JwtService jwtService;
	@Spy
	private UserMapper userMapper = new UserMapper();
	@Spy
	private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

	private AuthService service() {
		return new AuthService(userRepository, passwordEncoder, jwtService, userMapper);
	}

	@Test
	void registerStoresHashedPasswordAndForcesUserRole() {
		when(userRepository.existsByEmail("ana@test.com")).thenReturn(false);
		when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service().register(new RegisterRequest(" Ana ", " Ana@Test.com ", "Password123"));

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(captor.capture());
		User saved = captor.getValue();
		assertThat(saved.getRole()).isEqualTo(Role.USER);
		assertThat(saved.getEmail()).isEqualTo("ana@test.com");
		assertThat(saved.getPassword()).isNotEqualTo("Password123");
		assertThat(passwordEncoder.matches("Password123", saved.getPassword())).isTrue();
	}

	@Test
	void registerRejectsExistingEmail() {
		when(userRepository.existsByEmail("ana@test.com")).thenReturn(true);

		assertThatThrownBy(() -> service().register(new RegisterRequest("Ana", "ana@test.com", "Password123")))
				.isInstanceOf(ConflictException.class);
		verify(userRepository, never()).save(any());
	}

	@Test
	void loginFailsWithSameErrorForUnknownEmailAndWrongPassword() {
		User user = User.builder().email("ana@test.com").password(passwordEncoder.encode("Password123"))
				.role(Role.USER).build();
		when(userRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));
		when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().login(new LoginRequest("ana@test.com", "wrong")))
				.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> service().login(new LoginRequest("ghost@test.com", "Password123")))
				.isInstanceOf(BadCredentialsException.class);
	}
}
