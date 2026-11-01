package com.gerson.cowork.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulated external payment provider so the project runs without third parties. Behavior is deterministic and
 * chosen by the payment method, which makes the resilience features easy to demonstrate:
 * <ul>
 *   <li>{@code CARD_DECLINED}: provider refuses the payment (HTTP 200, approved=false)</li>
 *   <li>{@code CARD_ERROR}: provider fails (HTTP 503)</li>
 *   <li>{@code CARD_SLOW}: provider answers after 5 s, beyond the client read timeout</li>
 *   <li>anything else: approved after a short delay</li>
 * </ul>
 */
@RestController
@RequestMapping("/mock/payments")
@ConditionalOnProperty(name = "app.payment.mock-enabled", havingValue = "true")
public class MockPaymentController {

	@PostMapping("/validate")
	public ResponseEntity<PaymentApiResponse> validate(@RequestBody PaymentApiRequest request) throws InterruptedException {
		String method = request.paymentMethod() == null ? "" : request.paymentMethod();
		switch (method) {
			case "CARD_SLOW" -> Thread.sleep(5_000);
			case "CARD_ERROR" -> {
				return ResponseEntity.status(503).build();
			}
			case "CARD_DECLINED" -> {
				return ResponseEntity.ok(new PaymentApiResponse(false, null, "Payment method declined"));
			}
			default -> Thread.sleep(100);
		}
		String reference = "PAY-" + request.reservationId().toString().substring(0, 8).toUpperCase();
		return ResponseEntity.ok(new PaymentApiResponse(true, reference, null));
	}
}
