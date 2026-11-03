package com.gerson.cowork.service;

import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.event.ReservationConfirmedEvent;
import com.gerson.cowork.exception.PaymentDeclinedException;
import com.gerson.cowork.exception.ResourceNotFoundException;
import com.gerson.cowork.mapper.ReservationMapper;
import com.gerson.cowork.payment.PaymentGateway;
import com.gerson.cowork.payment.PaymentResult;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.security.AuthenticatedUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Confirms a reservation after validating the payment with the external service.
 * <p>
 * Deliberately NOT {@code @Transactional}: the remote call can take seconds, and a transaction would hold a
 * database connection for all that time. With a small pool, a few slow payments would exhaust it and take
 * down endpoints that never touch payments. Instead the work is split in three steps and the connection is
 * only held during the two short database steps:
 * <ol>
 *   <li>short read transaction: load, check access and that the reservation can still be confirmed;</li>
 *   <li>no transaction: call the payment service (bulkhead, circuit breaker, timeouts);</li>
 *   <li>short write transaction: reload and apply the result. {@code @Version} detects a concurrent change
 *       (e.g. the user cancelled while the payment was being validated).</li>
 * </ol>
 */
@Service
public class ReservationConfirmationService {

	private static final int TX_TIMEOUT_SECONDS = 5;

	private final ReservationRepository reservationRepository;
	private final ReservationAccess reservationAccess;
	private final ReservationMapper reservationMapper;
	private final PaymentGateway paymentGateway;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate readTx;
	private final TransactionTemplate writeTx;

	public ReservationConfirmationService(ReservationRepository reservationRepository,
			ReservationAccess reservationAccess, ReservationMapper reservationMapper, PaymentGateway paymentGateway,
			PlatformTransactionManager transactionManager, ApplicationEventPublisher eventPublisher) {
		this.reservationRepository = reservationRepository;
		this.reservationAccess = reservationAccess;
		this.reservationMapper = reservationMapper;
		this.paymentGateway = paymentGateway;
		this.eventPublisher = eventPublisher;
		this.readTx = new TransactionTemplate(transactionManager);
		this.readTx.setReadOnly(true);
		this.readTx.setTimeout(TX_TIMEOUT_SECONDS);
		this.writeTx = new TransactionTemplate(transactionManager);
		this.writeTx.setTimeout(TX_TIMEOUT_SECONDS);
	}

	public ConfirmationResult confirm(AuthenticatedUser principal, UUID reservationId, String paymentMethod) {
		return confirm(principal, reservationId, paymentMethod, IdempotencyCompletion.NONE);
	}

	/**
	 * @param completion called inside the write transaction when the reservation gets confirmed, so an
	 *                   idempotency key is stored atomically with the state change
	 */
	public ConfirmationResult confirm(AuthenticatedUser principal, UUID reservationId, String paymentMethod,
			IdempotencyCompletion completion) {
		BigDecimal amount = readTx.execute(status -> {
			Reservation reservation = load(reservationId);
			reservationAccess.check(principal, reservation);
			// Do not charge for a transition that is already impossible
			reservation.ensureCanConfirm();
			return reservation.getTotalPrice();
		});

		PaymentResult payment = paymentGateway.validate(reservationId, amount, paymentMethod);

		return writeTx.execute(status -> {
			Reservation reservation = load(reservationId);
			// The state may have changed while the payment was in flight
			reservation.ensureCanConfirm();
			return switch (payment.outcome()) {
				case APPROVED -> {
					reservation.confirm();
					reservation.setPaymentReference(payment.reference());
					// Delivered to listeners only if this transaction commits
					eventPublisher.publishEvent(new ReservationConfirmedEvent(
							reservation.getId(),
							reservation.getUser().getName(),
							reservation.getUser().getEmail(),
							reservation.getSpace().getName(),
							reservation.getStartAt(),
							reservation.getEndAt(),
							reservation.getTotalPrice()));
					ReservationResponse response = reservationMapper.toResponse(reservation);
					completion.record(200, response);
					yield new ConfirmationResult(response, true);
				}
				case DECLINED -> throw new PaymentDeclinedException(payment.reason());
				// Unknown payment outcome: keep PENDING_PAYMENT so it can be retried, instead of failing the request
				case UNAVAILABLE -> new ConfirmationResult(reservationMapper.toResponse(reservation), false);
			};
		});
	}

	private Reservation load(UUID id) {
		return reservationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
	}
}
