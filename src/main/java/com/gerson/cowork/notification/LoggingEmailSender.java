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
		return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
	}
}
