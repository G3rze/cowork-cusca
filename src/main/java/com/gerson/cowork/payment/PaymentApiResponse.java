package com.gerson.cowork.payment;

public record PaymentApiResponse(boolean approved, String reference, String reason) {
}
