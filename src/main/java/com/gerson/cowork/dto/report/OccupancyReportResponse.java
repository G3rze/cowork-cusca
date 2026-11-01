package com.gerson.cowork.dto.report;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record OccupancyReportResponse(
		LocalDate from,
		LocalDate to,
		LocalTime openingTime,
		LocalTime closingTime,
		String timeZone,
		List<SpaceOccupancyResponse> spaces) {
}
