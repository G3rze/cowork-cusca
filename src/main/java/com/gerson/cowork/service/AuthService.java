package com.gerson.cowork.service;

import com.gerson.cowork.dto.auth.AuthResponse;
import com.gerson.cowork.dto.auth.LoginRequest;
import com.gerson.cowork.dto.auth.RegisterRequest;
import com.gerson.cowork.dto.user.UserResponse;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.exception.ConflictException;
import com.gerson.cowork.mapper.UserMapper;
import com.gerson.cowork.repository.UserRepository;
import com.gerson.cowork.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final UserMapper userMapper;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
			UserMapper userMapper) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.userMapper = userMapper;
	}

	/** Public registration always creates a USER; admins come from configuration. */
	@Transactional
	public UserResponse register(RegisterRequest request) {
		String email = normalize(request.email());
		if (userRepository.existsByEmail(email)) {
			throw new ConflictException("Email is already registered");
		}
		User user = User.builder()
				.name(request.name().trim())
				.email(email)
				.password(passwordEncoder.encode(request.password()))
				.role(Role.USER)
				.build();
		return userMapper.toResponse(userRepository.save(user));
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(normalize(request.email()))
				.filter(found -> passwordEncoder.matches(request.password(), found.getPassword()))
				// Same error for unknown email and wrong password to avoid user enumeration
				.orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
		return AuthResponse.bearer(jwtService.generateToken(user), jwtService.expiresInSeconds());
	}

	private String normalize(String email) {
		return email.trim().toLowerCase();
	}
}
