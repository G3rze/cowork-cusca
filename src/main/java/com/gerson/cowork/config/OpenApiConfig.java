package com.gerson.cowork.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI definition generated from the controllers. Every operation requires the Bearer token by default
 * (use "Authorize" in Swagger UI); the public ones override it with an empty {@code @SecurityRequirements}.
 */
@Configuration
@OpenAPIDefinition(
		info = @Info(
				title = "Cowork API",
				version = "v1",
				description = "Coworking space reservations: spaces, reservations with payment validation, "
						+ "and occupancy reports."),
		security = @SecurityRequirement(name = SecuritySchemes.BEARER))
@SecurityScheme(name = SecuritySchemes.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
