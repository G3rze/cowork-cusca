package com.gerson.cowork.notification;

/** Port for outgoing email. Swap the implementation to use a real provider. */
public interface EmailSender {

	/**
	 * @param messageId stable id of the message: the same value is sent again on a redelivery, so a real provider
	 *                  (or this application) can deduplicate and the notification is not duplicated
	 */
	void send(String messageId, String to, String subject, String body);
}
