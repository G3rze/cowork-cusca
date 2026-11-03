package com.gerson.cowork.dto.space;

import com.gerson.cowork.enums.SpaceType;

import java.math.BigDecimal;
import java.util.UUID;

public record SpaceResponse(
		UUID id,
		String name,
		SpaceType type,
		int capacity,
		String location,
		BigDecimal hourlyRate) {
}
