package com.gerson.cowork.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Simulates sending an email by logging it. The address is masked to keep personal data out of the logs. */
@Component
@Slf4j
public class LoggingEmailSender implements EmailSender {

	@Override
	public void send(String to, String subject, String body) {
		log.info("[SIMULATED EMAIL] to={} subject=\"{}\" body=\"{}\"", mask(to), subject, body);
	}

	static String mask(String email) {
		int at = email.indexOf('@');
		if (at < 0) {
			return "***";
		}
		// A one-character local part would be fully revealed by keeping its first letter
		return at <= 1 ? "***" + email.substring(at) : email.charAt(0) + "***" + email.substring(at);
	}
}
