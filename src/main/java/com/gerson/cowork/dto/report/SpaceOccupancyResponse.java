package com.gerson.cowork.dto.report;

import java.math.BigDecimal;
import java.util.UUID;

public record SpaceOccupancyResponse(
		UUID spaceId,
		String spaceName,
		BigDecimal occupiedHours,
		BigDecimal availableHours,
		BigDecimal occupancyPercent) {
}
