package com.gerson.cowork.pricing;

import com.gerson.cowork.entity.Space;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/** Hourly rate prorated by the minute. Lowest precedence: it is the fallback when no other policy applies. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StandardPricingStrategy implements PricingStrategy {

	private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

	@Override
	public boolean appliesTo(Space space, Duration duration) {
		return true;
	}

	@Override
	public BigDecimal calculate(Space space, Duration duration) {
		return basePrice(space, duration);
	}

	static BigDecimal basePrice(Space space, Duration duration) {
		return space.getHourlyRate()
				.multiply(BigDecimal.valueOf(duration.toMinutes()))
				.divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);
	}
}
