package com.gerson.cowork.pricing;

import com.gerson.cowork.entity.Space;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/** 10% discount for bookings of 4 hours or more. */
@Component
@Order(1)
public class LongBookingDiscountStrategy implements PricingStrategy {

	static final Duration MIN_DURATION = Duration.ofHours(4);
	private static final BigDecimal DISCOUNT_FACTOR = new BigDecimal("0.90");

	@Override
	public boolean appliesTo(Space space, Duration duration) {
		return duration.compareTo(MIN_DURATION) >= 0;
	}

	@Override
	public BigDecimal calculate(Space space, Duration duration) {
		return StandardPricingStrategy.basePrice(space, duration)
				.multiply(DISCOUNT_FACTOR)
				.setScale(2, RoundingMode.HALF_UP);
	}
}
