package com.gerson.cowork.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentApiRequest(UUID reservationId, BigDecimal amount, String paymentMethod) {
}
