package com.gerson.cowork.dto.reservation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record ReservationCreateRequest(
		@NotNull UUID spaceId,
		@NotNull @Future Instant startAt,
		@NotNull Instant endAt) {

	@JsonIgnore
	@AssertTrue(message = "endAt must be after startAt")
	public boolean isEndAfterStart() {
		return startAt == null || endAt == null || endAt.isAfter(startAt);
	}
}
