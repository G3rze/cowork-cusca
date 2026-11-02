package com.gerson.cowork.config;

import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminInitializerTest {

	@Mock
	private UserRepository userRepository;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

	private AdminInitializer initializerFor(AppProperties.Admin admin) {
		return new AdminInitializer(new AppProperties(null, admin, null, null), userRepository, passwordEncoder);
	}

	@Test
	void createsTheAdminWithAHashedPassword() {
		when(userRepository.existsByEmail("root@test.com")).thenReturn(false);

		initializerFor(new AppProperties.Admin("Root", "root@test.com", "S3cret-pass")).afterSingletonsInstantiated();

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(saved.capture());
		assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
		assertThat(saved.getValue().getPassword()).isNotEqualTo("S3cret-pass");
		assertThat(passwordEncoder.matches("S3cret-pass", saved.getValue().getPassword())).isTrue();
	}

	@Test
	void doesNothingWhenTheAdminAlreadyExists() {
		when(userRepository.existsByEmail("root@test.com")).thenReturn(true);

		initializerFor(new AppProperties.Admin("Root", "root@test.com", "S3cret-pass")).afterSingletonsInstantiated();

		verify(userRepository, never()).save(any());
	}

	@Test
	void doesNothingWhenNoAdminIsConfigured() {
		initializerFor(null).afterSingletonsInstantiated();
		initializerFor(new AppProperties.Admin(null, "", "")).afterSingletonsInstantiated();

		verify(userRepository, never()).save(any());
	}

	@Test
	void runsBeforeTheWebServerStartsAcceptingRequests() {
		// Regression: as an ApplicationRunner it ran after startup, leaving a window where health was UP
		// but the administrator did not exist yet.
		assertThat(SmartInitializingSingleton.class).isAssignableFrom(AdminInitializer.class);
	}
}
