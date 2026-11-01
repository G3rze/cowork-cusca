package com.gerson.cowork.service;

import com.gerson.cowork.config.AppProperties;
import com.gerson.cowork.dto.report.OccupancyReportResponse;
import com.gerson.cowork.dto.report.SpaceOccupancyResponse;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.exception.BadRequestException;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.repository.SpaceOccupiedSeconds;
import com.gerson.cowork.repository.SpaceRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OccupancyReportService {

	public static final String CACHE_NAME = "occupancy";
	static final int MAX_RANGE_DAYS = 366;

	private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final ReservationRepository reservationRepository;
	private final SpaceRepository spaceRepository;
	private final AppProperties.BusinessHours hours;

	public OccupancyReportService(ReservationRepository reservationRepository, SpaceRepository spaceRepository,
			AppProperties properties) {
		this.reservationRepository = reservationRepository;
		this.spaceRepository = spaceRepository;
		this.hours = properties.businessHours();
	}

	/**
	 * Occupancy = booked hours inside the business-hours windows / available hours (days in the range times the
	 * daily window). Cached because it is expensive and read far more often than reservations change; the entry
	 * is evicted by {@link OccupancyCacheInvalidator} when a reservation is confirmed or cancelled.
	 */
	@Cacheable(cacheNames = CACHE_NAME, key = "#from.toString() + '|' + #to.toString()")
	public OccupancyReportResponse occupancy(LocalDate from, LocalDate to) {
		validate(from, to);
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		BigDecimal availableSeconds = BigDecimal.valueOf(days * hours.windowSeconds());

		Map<UUID, Long> occupiedBySpace = new HashMap<>();
		for (SpaceOccupiedSeconds row : reservationRepository.findOccupiedSeconds(from.toString(), to.toString(),
				hours.opening().toString(), hours.closing().toString(), hours.zone().getId())) {
			occupiedBySpace.put(row.getSpaceId(), row.getOccupiedSeconds());
		}

		List<SpaceOccupancyResponse> spaces = spaceRepository.findAll(Sort.by("name")).stream()
				.map(space -> toResponse(space, occupiedBySpace.getOrDefault(space.getId(), 0L), availableSeconds))
				.toList();
		return new OccupancyReportResponse(from, to, hours.opening(), hours.closing(), hours.zone().getId(), spaces);
	}

	private SpaceOccupancyResponse toResponse(Space space, long occupiedSeconds, BigDecimal availableSeconds) {
		BigDecimal occupied = BigDecimal.valueOf(occupiedSeconds);
		return new SpaceOccupancyResponse(
				space.getId(),
				space.getName(),
				occupied.divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP),
				availableSeconds.divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP),
				occupied.multiply(HUNDRED).divide(availableSeconds, 2, RoundingMode.HALF_UP));
	}

	private void validate(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw new BadRequestException("'to' must not be before 'from'");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
			throw new BadRequestException("The range must not exceed " + MAX_RANGE_DAYS + " days");
		}
	}
}
