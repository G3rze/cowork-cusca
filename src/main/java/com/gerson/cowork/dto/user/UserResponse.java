package com.gerson.cowork.dto.user;

import com.gerson.cowork.enums.Role;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String name, String email, Role role, Instant createdAt) {
}
