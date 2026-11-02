package com.gerson.cowork.config;

import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN from configuration (idempotent), since public registration only creates USER accounts.
 * <p>
 * It is a {@link SmartInitializingSingleton} rather than an {@code ApplicationRunner} on purpose: runners execute
 * after the web server is already accepting requests, so {@code /actuator/health} could report UP (and a login
 * attempt fail with 401) for a few hundred milliseconds before the admin existed. Singleton initialization
 * finishes before the web server starts.
 */
@Component
@Slf4j
public class AdminInitializer implements SmartInitializingSingleton {

	private final AppProperties properties;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public AdminInitializer(AppProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
		this.properties = properties;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public void afterSingletonsInstantiated() {
		AppProperties.Admin admin = properties.admin();
		if (admin == null || !admin.isConfigured()) {
			log.info("No bootstrap admin configured, skipping");
			return;
		}
		if (userRepository.existsByEmail(admin.email())) {
			return;
		}
		userRepository.save(User.builder()
				.name(admin.name() == null || admin.name().isBlank() ? "Administrator" : admin.name())
				.email(admin.email())
				.password(passwordEncoder.encode(admin.password()))
				.role(Role.ADMIN)
				.build());
		log.info("Bootstrap admin created: {}", admin.email());
	}
}
