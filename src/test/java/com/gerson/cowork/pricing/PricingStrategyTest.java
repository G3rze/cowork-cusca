package com.gerson.cowork.pricing;

import com.gerson.cowork.entity.Space;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricingStrategyTest {

	private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");

	// Deliberately registered out of precedence order: the real injection order comes from @Order
	private final PriceCalculator calculator = new PriceCalculator(
			List.of(new LongBookingDiscountStrategy(), new StandardPricingStrategy()));

	private Space space(String hourlyRate) {
		return Space.builder().hourlyRate(new BigDecimal(hourlyRate)).build();
	}

	@Test
	void standardPriceIsHourlyRateTimesHours() {
		assertThat(calculator.calculate(space("20.00"), START, START.plusSeconds(2 * 3600)))
				.isEqualByComparingTo("40.00");
	}

	@Test
	void standardPriceIsProratedByTheMinute() {
		assertThat(calculator.calculate(space("25.50"), START, START.plusSeconds(90 * 60)))
				.isEqualByComparingTo("38.25");
	}

	@Test
	void bookingsOfFourHoursOrMoreGetTenPercentOff() {
		assertThat(calculator.calculate(space("20.00"), START, START.plusSeconds(4 * 3600)))
				.isEqualByComparingTo("72.00");
	}

	@Test
	void justUnderFourHoursPaysFullPrice() {
		assertThat(calculator.calculate(space("20.00"), START, START.plusSeconds(4 * 3600 - 60)))
				.isEqualByComparingTo("79.67");
	}

	@Test
	void discountStrategyOnlyAppliesFromFourHours() {
		LongBookingDiscountStrategy strategy = new LongBookingDiscountStrategy();
		assertThat(strategy.appliesTo(space("10"), LongBookingDiscountStrategy.MIN_DURATION)).isTrue();
		assertThat(strategy.appliesTo(space("10"), LongBookingDiscountStrategy.MIN_DURATION.minusMinutes(1))).isFalse();
	}
}
