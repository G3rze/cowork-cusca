package com.gerson.cowork.controller;

import com.gerson.cowork.dto.report.OccupancyReportResponse;
import com.gerson.cowork.service.OccupancyReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@Tag(name = "Reports")
@RequestMapping("/api/v1/reports")
public class ReportController {

	private final OccupancyReportService reportService;

	public ReportController(OccupancyReportService reportService) {
		this.reportService = reportService;
	}

	/** Occupancy percentage of every space between two dates (both inclusive). ADMIN only. */
	@Operation(summary = "Occupancy report", description = "Percentage of the business hours each space was booked. ADMIN only; cached.")
	@GetMapping("/occupancy")
	public OccupancyReportResponse occupancy(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return reportService.occupancy(from, to);
	}
}
