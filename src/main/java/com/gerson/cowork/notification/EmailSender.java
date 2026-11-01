package com.gerson.cowork.notification;

/** Port for outgoing email. Swap the implementation to use a real provider. */
public interface EmailSender {

	void send(String to, String subject, String body);
}
