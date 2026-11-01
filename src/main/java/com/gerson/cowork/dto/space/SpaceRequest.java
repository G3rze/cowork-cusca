package com.gerson.cowork.dto.space;

import com.gerson.cowork.enums.SpaceType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SpaceRequest(
		@NotBlank @Size(max = 100) String name,
		@NotNull SpaceType type,
		@Min(1) int capacity,
		@NotBlank @Size(max = 200) String location,
		@NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal hourlyRate) {
}
