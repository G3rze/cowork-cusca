package com.gerson.cowork.config;

import com.gerson.cowork.enums.Role;
import com.gerson.cowork.security.JwtAuthenticationFilter;
import com.gerson.cowork.security.JwtService;
import com.gerson.cowork.security.RestSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, RestSecurityHandlers handlers)
			throws Exception {
		http
				// Stateless API authenticated by a Bearer token: no cookies, so no CSRF surface
				.csrf(csrf -> csrf.disable())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(handlers)
						.accessDeniedHandler(handlers))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/v1/auth/**").permitAll()
						.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/circuitbreakers").permitAll()
						// In-app payment mock, called by the application itself without a token
						.requestMatchers("/mock/**").permitAll()
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/spaces/**").hasAnyRole(Role.ADMIN.name(), Role.USER.name())
						.requestMatchers("/api/v1/spaces/**").hasRole(Role.ADMIN.name())
						.requestMatchers("/api/v1/reports/**").hasRole(Role.ADMIN.name())
						.anyRequest().authenticated())
				.addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
