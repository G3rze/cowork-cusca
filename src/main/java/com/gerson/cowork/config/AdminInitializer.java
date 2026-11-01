package com.gerson.cowork.config;

import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN from configuration (idempotent), since public registration only creates USER accounts.
 */
@Component
@Slf4j
public class AdminInitializer implements ApplicationRunner {

	private final AppProperties properties;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public AdminInitializer(AppProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
		this.properties = properties;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public void run(ApplicationArguments args) {
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
