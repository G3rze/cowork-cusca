package com.gerson.cowork.controller;

import com.gerson.cowork.dto.reservation.ReservationCreateRequest;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.security.AuthenticatedUser;
import com.gerson.cowork.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	@PostMapping
	public ResponseEntity<ReservationResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
			@Valid @RequestBody ReservationCreateRequest request) {
		ReservationResponse created = reservationService.create(principal, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	@GetMapping
	public Page<ReservationResponse> findAll(@AuthenticationPrincipal AuthenticatedUser principal,
			@PageableDefault(size = 20, sort = "startAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return reservationService.findAll(principal, pageable);
	}

	@GetMapping("/{id}")
	public ReservationResponse findById(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reservationService.findById(principal, id);
	}

	@PostMapping("/{id}/cancel")
	public ReservationResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reservationService.cancel(principal, id);
	}
}
