package com.gerson.cowork.notification;

import com.gerson.cowork.event.ReservationConfirmedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Observer of confirmed reservations. It runs only after the confirming transaction committed (a rolled-back
 * confirmation never notifies) and on a separate virtual thread, so the HTTP response does not wait for it.
 * <p>
 * Trade-off: AFTER_COMMIT is not durable. If the process dies between the commit and the send, the
 * notification is lost; a transactional outbox would fix that.
 */
@Component
@Slf4j
public class ReservationNotificationListener {

	private final EmailSender emailSender;

	public ReservationNotificationListener(EmailSender emailSender) {
		this.emailSender = emailSender;
	}

	@Async("notificationExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onReservationConfirmed(ReservationConfirmedEvent event) {
		try {
			emailSender.send(event.userEmail(),
					"Reservation confirmed: " + event.spaceName(),
					"Hi %s, your reservation from %s to %s is confirmed (total %s)."
							.formatted(event.userName(), event.startAt(), event.endAt(), event.totalPrice()));
		} catch (RuntimeException ex) {
			// The booking is already committed: a failed notification must never undo or surface as an error
			log.error("Could not send confirmation for reservation {}", event.reservationId(), ex);
		}
	}
}
