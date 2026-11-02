package com.gerson.cowork.service;

import com.gerson.cowork.dto.reservation.ReservationCreateRequest;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Entry point for the retry-prone writes (create and confirm), wrapping them with {@link IdempotencyService}.
 * Not transactional itself: the idempotency key is claimed before, and recorded inside, the business transaction.
 */
@Service
public class IdempotentReservationService {

	private static final int TX_TIMEOUT_SECONDS = 5;

	private final IdempotencyService idempotencyService;
	private final ReservationService reservationService;
	private final ReservationConfirmationService confirmationService;
	private final TransactionTemplate writeTx;

	public IdempotentReservationService(IdempotencyService idempotencyService, ReservationService reservationService,
			ReservationConfirmationService confirmationService, PlatformTransactionManager transactionManager) {
		this.idempotencyService = idempotencyService;
		this.reservationService = reservationService;
		this.confirmationService = confirmationService;
		this.writeTx = new TransactionTemplate(transactionManager);
		this.writeTx.setTimeout(TX_TIMEOUT_SECONDS);
	}

	public static String createFingerprint(UUID spaceId, Instant startAt, Instant endAt) {
		return IdempotencyService.fingerprint("create-reservation", spaceId, startAt, endAt);
	}

	public static String confirmFingerprint(UUID reservationId, String paymentMethod) {
		return IdempotencyService.fingerprint("confirm-reservation", reservationId, paymentMethod);
	}

	public IdempotentResponse create(AuthenticatedUser principal, String idempotencyKey, ReservationCreateRequest request) {
		String fingerprint = createFingerprint(request.spaceId(), request.startAt(), request.endAt());
		return idempotencyService.execute(principal, idempotencyKey, fingerprint, completion ->
				// One transaction: the reservation and the stored response commit together, or not at all
				writeTx.execute(status -> {
					ReservationResponse created = reservationService.create(principal, request);
					completion.record(201, created);
					return new IdempotentResponse(201, created, false);
				}));
	}

	public IdempotentResponse confirm(AuthenticatedUser principal, String idempotencyKey, UUID reservationId,
			String paymentMethod) {
		String fingerprint = confirmFingerprint(reservationId, paymentMethod);
		return idempotencyService.execute(principal, idempotencyKey, fingerprint, completion -> {
			ConfirmationResult result = confirmationService.confirm(principal, reservationId, paymentMethod, completion);
			return new IdempotentResponse(result.confirmed() ? 200 : 202, result.reservation(), false);
		});
	}
}
