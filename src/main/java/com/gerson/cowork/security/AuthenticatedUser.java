package com.gerson.cowork.security;

import com.gerson.cowork.enums.Role;

import java.util.UUID;

/** Principal rebuilt from the JWT claims; no database access per request. */
public record AuthenticatedUser(UUID id, String email, Role role) {
}
