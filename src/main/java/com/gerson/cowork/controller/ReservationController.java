package com.gerson.cowork.controller;

import com.gerson.cowork.dto.reservation.ConfirmReservationRequest;
import com.gerson.cowork.dto.reservation.ReservationCreateRequest;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.security.AuthenticatedUser;
import com.gerson.cowork.service.ConfirmationResult;
import com.gerson.cowork.service.ReservationConfirmationService;
import com.gerson.cowork.service.ReservationService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Reservations")
@RequestMapping("/api/v1/reservations")
public class ReservationController {

	private final ReservationService reservationService;
	private final ReservationConfirmationService confirmationService;

	public ReservationController(ReservationService reservationService,
			ReservationConfirmationService confirmationService) {
		this.reservationService = reservationService;
		this.confirmationService = confirmationService;
	}

	@Operation(summary = "Create a reservation",
			description = "For the authenticated user. Starts as PENDING_PAYMENT. 409 if the slot is already taken.")
	@PostMapping
	public ResponseEntity<ReservationResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
			@Valid @RequestBody ReservationCreateRequest request) {
		ReservationResponse created = reservationService.create(principal, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	@Operation(summary = "List reservations (paged)", description = "USER: their own. ADMIN: all of them.")
	@GetMapping
	public Page<ReservationResponse> findAll(@AuthenticationPrincipal AuthenticatedUser principal,
			@PageableDefault(size = 20, sort = "startAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return reservationService.findAll(principal, pageable);
	}

	@Operation(summary = "Get a reservation", description = "USER: only their own (404 otherwise). ADMIN: any.")
	@GetMapping("/{id}")
	public ReservationResponse findById(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reservationService.findById(principal, id);
	}

	@Operation(summary = "Cancel a reservation", description = "Owner or ADMIN. 409 if it is already cancelled or completed.")
	@PostMapping("/{id}/cancel")
	public ReservationResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reservationService.cancel(principal, id);
	}

	/**
	 * 200 when the payment was validated and the reservation is CONFIRMED; 202 when the payment service is
	 * unavailable and the reservation stays PENDING_PAYMENT, so the client can retry later.
	 */
	@Operation(summary = "Confirm a reservation by validating the payment",
			description = "200: payment approved, reservation CONFIRMED. 202: payment service unavailable, "
					+ "reservation stays PENDING_PAYMENT (retry later). 402: payment declined. "
					+ "Test payment methods: CARD_OK, CARD_DECLINED, CARD_ERROR, CARD_SLOW.")
	@PostMapping("/{id}/confirm")
	public ResponseEntity<ReservationResponse> confirm(@AuthenticationPrincipal AuthenticatedUser principal,
			@PathVariable UUID id, @Valid @RequestBody ConfirmReservationRequest request) {
		ConfirmationResult result = confirmationService.confirm(principal, id, request.paymentMethod());
		return ResponseEntity.status(result.confirmed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
				.body(result.reservation());
	}
}
