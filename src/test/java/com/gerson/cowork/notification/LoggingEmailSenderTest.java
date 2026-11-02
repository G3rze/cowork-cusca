package com.gerson.cowork.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingEmailSenderTest {

	@Test
	void masksTheLocalPartKeepingTheDomain() {
		assertThat(LoggingEmailSender.mask("ana.lopez@test.com")).isEqualTo("a***@test.com");
	}

	@Test
	void masksVeryShortOrMalformedAddressesCompletely() {
		assertThat(LoggingEmailSender.mask("a@test.com")).isEqualTo("***@test.com");
		assertThat(LoggingEmailSender.mask("no-at-sign")).isEqualTo("***");
	}

	@Test
	void sendingNeverThrows() {
		new LoggingEmailSender().send("msg-1", "ana@test.com", "Subject", "Body");
	}
}
