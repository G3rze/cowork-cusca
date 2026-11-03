package com.gerson.cowork.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
	private final MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/things/1");

	private void assertResponse(ResponseEntity<ErrorResponse> response, HttpStatus status) {
		assertThat(response.getStatusCode()).isEqualTo(status);
		assertThat(response.getBody().status()).isEqualTo(status.value());
		assertThat(response.getBody().path()).isEqualTo("/api/v1/things/1");
		assertThat(response.getBody().timestamp()).isNotNull();
	}

	@Test
	void optimisticLockFailureIsAConflictTheClientCanRetry() {
		ResponseEntity<ErrorResponse> response = handler.handleOptimisticLock(
				new ObjectOptimisticLockingFailureException("Reservation", 1), request);

		assertResponse(response, HttpStatus.CONFLICT);
		assertThat(response.getBody().message()).contains("retry");
	}

	@Test
	void integrityViolationDoesNotLeakDatabaseDetails() {
		ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(
				new DataIntegrityViolationException("x", new RuntimeException("duplicate key value (secret_column)")), request);

		assertResponse(response, HttpStatus.CONFLICT);
		assertThat(response.getBody().message()).doesNotContain("secret_column");
	}

	@Test
	void unexpectedErrorsAreHiddenBehindAGenericMessage() {
		ResponseEntity<ErrorResponse> response = handler.handleUnexpected(new IllegalStateException("db password is hunter2"), request);

		assertResponse(response, HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().message()).doesNotContain("hunter2");
	}

	@Test
	void businessErrorsMapToTheirStatus() {
		assertResponse(handler.handlePaymentDeclined(new PaymentDeclinedException("No funds"), request), HttpStatus.PAYMENT_REQUIRED);
		assertResponse(handler.handleAccessDenied(new AccessDeniedException("no"), request), HttpStatus.FORBIDDEN);
		assertResponse(handler.handleConflict(new OverlappingReservationException(), request), HttpStatus.CONFLICT);
		assertResponse(handler.handleInvalidValues(new BadRequestException("bad"), request), HttpStatus.BAD_REQUEST);
		assertResponse(handler.handleNotFound(new ResourceNotFoundException("Space", 1), request), HttpStatus.NOT_FOUND);
	}
}
