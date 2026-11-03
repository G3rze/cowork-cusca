package com.gerson.cowork.mapper;

import com.gerson.cowork.dto.user.UserResponse;
import com.gerson.cowork.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

	public UserResponse toResponse(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getCreatedAt());
	}
}
