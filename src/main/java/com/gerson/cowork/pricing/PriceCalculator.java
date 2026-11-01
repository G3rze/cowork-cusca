package com.gerson.cowork.pricing;

import com.gerson.cowork.entity.Space;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Picks the first applicable strategy; Spring injects them already sorted by {@code @Order}. */
@Component
public class PriceCalculator {

	private final List<PricingStrategy> strategies;

	public PriceCalculator(List<PricingStrategy> strategies) {
		this.strategies = strategies;
	}

	public BigDecimal calculate(Space space, Instant startAt, Instant endAt) {
		Duration duration = Duration.between(startAt, endAt);
		return strategies.stream()
				.filter(strategy -> strategy.appliesTo(space, duration))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No pricing strategy applies"))
				.calculate(space, duration);
	}
}
