package com.gerson.cowork.pricing;

import com.gerson.cowork.entity.Space;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Strategy pattern for fee calculation: each pricing policy declares when it applies and how it computes the
 * total. Adding a policy means adding a class, without touching the reservation flow.
 */
public interface PricingStrategy {

	boolean appliesTo(Space space, Duration duration);

	BigDecimal calculate(Space space, Duration duration);
}
