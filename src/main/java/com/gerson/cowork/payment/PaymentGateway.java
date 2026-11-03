package com.gerson.cowork.payment;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Client of the external payment validation service, which is treated as potentially slow or unstable.
 * <ul>
 *   <li><b>Timeouts</b> (see {@link PaymentClientConfig}) bound each call.</li>
 *   <li><b>Bulkhead</b> limits concurrent calls, so a slow provider cannot take every request thread.</li>
 *   <li><b>Circuit breaker</b> fails fast while the provider is unhealthy instead of piling up waiting calls.</li>
 *   <li><b>Fallback</b> returns UNAVAILABLE, never an approval: money-related flows must not succeed by default.</li>
 * </ul>
 * This must be called outside any database transaction, so a slow provider never holds a DB connection.
 */
@Component
@Slf4j
public class PaymentGateway {

	private static final String VALIDATE_PATH = "/validate";

	private final RestClient restClient;

	public PaymentGateway(RestClient paymentRestClient) {
		this.restClient = paymentRestClient;
	}

	@CircuitBreaker(name = "payment", fallbackMethod = "unavailable")
	@Bulkhead(name = "payment")
	public PaymentResult validate(UUID reservationId, BigDecimal amount, String paymentMethod) {
		PaymentApiResponse response = restClient.post()
				.uri(VALIDATE_PATH)
				// The reservation id makes retries safe: the provider can deduplicate repeated validations
				.header("Idempotency-Key", reservationId.toString())
				.body(new PaymentApiRequest(reservationId, amount, paymentMethod))
				.retrieve()
				.body(PaymentApiResponse.class);
		if (response == null) {
			throw new IllegalStateException("Empty response from payment service");
		}
		return response.approved()
				? PaymentResult.approved(response.reference())
				: PaymentResult.declined(response.reason());
	}

	@SuppressWarnings("unused") // invoked by Resilience4j through the fallbackMethod name
	PaymentResult unavailable(UUID reservationId, BigDecimal amount, String paymentMethod, Throwable cause) {
		log.warn("Payment validation unavailable for reservation {}: {}", reservationId, cause.toString());
		return PaymentResult.unavailable(cause.getClass().getSimpleName());
	}
}
