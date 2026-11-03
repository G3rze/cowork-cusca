package com.gerson.cowork.controller;

import com.gerson.cowork.dto.reservation.ConfirmReservationRequest;
import com.gerson.cowork.dto.reservation.ReservationCreateRequest;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.security.AuthenticatedUser;
import com.gerson.cowork.service.IdempotencyService;
import com.gerson.cowork.service.IdempotentResponse;
import com.gerson.cowork.service.IdempotentReservationService;
import com.gerson.cowork.service.ReservationService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@Tag(name = "Reservations")
@RequestMapping("/api/v1/reservations")
public class ReservationController {

	private static final String IDEMPOTENCY_KEY_DESCRIPTION = "Optional but recommended: a unique value per intent "
			+ "(e.g. a UUID). Retrying with the same key and request returns the original response instead of "
			+ "repeating the operation; the same key with a different request returns 422.";

	private final ReservationService reservationService;
	private final IdempotentReservationService idempotentReservations;

	public ReservationController(ReservationService reservationService,
			IdempotentReservationService idempotentReservations) {
		this.reservationService = reservationService;
		this.idempotentReservations = idempotentReservations;
	}

	@Operation(summary = "Create a reservation",
			description = "For the authenticated user. Starts as PENDING_PAYMENT. 409 if the slot is already taken.")
	@PostMapping
	public ResponseEntity<ReservationResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
			@RequestHeader(value = IdempotencyService.HEADER, required = false)
			@Parameter(description = IDEMPOTENCY_KEY_DESCRIPTION) String idempotencyKey,
			@Valid @RequestBody ReservationCreateRequest request) {
		IdempotentResponse result = idempotentReservations.create(principal, idempotencyKey, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
				.buildAndExpand(result.body().id()).toUri();
		return respond(result).location(location).body(result.body());
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
			@PathVariable UUID id,
			@RequestHeader(value = IdempotencyService.HEADER, required = false)
			@Parameter(description = IDEMPOTENCY_KEY_DESCRIPTION) String idempotencyKey,
			@Valid @RequestBody ConfirmReservationRequest request) {
		IdempotentResponse result = idempotentReservations.confirm(principal, idempotencyKey, id, request.paymentMethod());
		return respond(result).body(result.body());
	}

	private static ResponseEntity.BodyBuilder respond(IdempotentResponse result) {
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.valueOf(result.status()));
		return result.replayed() ? builder.header("Idempotent-Replayed", "true") : builder;
	}
}
