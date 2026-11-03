package com.gerson.cowork.service;

import com.gerson.cowork.config.AppProperties;
import com.gerson.cowork.dto.report.OccupancyReportResponse;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.exception.BadRequestException;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.repository.SpaceOccupiedSeconds;
import com.gerson.cowork.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OccupancyReportServiceTest {

	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private SpaceRepository spaceRepository;

	private OccupancyReportService service;
	private Space busy;
	private Space idle;

	@BeforeEach
	void setUp() {
		AppProperties.BusinessHours hours = new AppProperties.BusinessHours(LocalTime.of(8, 0), LocalTime.of(18, 0),
				ZoneId.of("America/El_Salvador"));
		service = new OccupancyReportService(reservationRepository, spaceRepository,
				new AppProperties(null, null, null, hours));
		busy = Space.builder().id(UUID.randomUUID()).name("Busy").build();
		idle = Space.builder().id(UUID.randomUUID()).name("Idle").build();
	}

	private SpaceOccupiedSeconds row(UUID spaceId, long seconds) {
		return new SpaceOccupiedSeconds() {
			@Override
			public UUID getSpaceId() {
				return spaceId;
			}

			@Override
			public Long getOccupiedSeconds() {
				return seconds;
			}
		};
	}

	@Test
	void computesHoursAndPercentAgainstTheBusinessWindow() {
		LocalDate day = LocalDate.of(2040, 3, 4);
		when(reservationRepository.findOccupiedSeconds("2040-03-04", "2040-03-04", "08:00", "18:00", "America/El_Salvador"))
				.thenReturn(List.of(row(busy.getId(), 3 * 3600)));
		when(spaceRepository.findAll(Sort.by("name"))).thenReturn(List.of(busy, idle));

		OccupancyReportResponse report = service.occupancy(day, day);

		assertThat(report.spaces()).hasSize(2);
		assertThat(report.spaces().get(0).occupiedHours()).isEqualByComparingTo("3.00");
		assertThat(report.spaces().get(0).availableHours()).isEqualByComparingTo("10.00");
		assertThat(report.spaces().get(0).occupancyPercent()).isEqualByComparingTo("30.00");
		assertThat(report.spaces().get(1).occupancyPercent()).isEqualByComparingTo("0.00");
		assertThat(report.openingTime()).isEqualTo(LocalTime.of(8, 0));
		assertThat(report.timeZone()).isEqualTo("America/El_Salvador");
	}

	@Test
	void availableHoursGrowWithTheNumberOfDays() {
		when(reservationRepository.findOccupiedSeconds("2040-03-04", "2040-03-10", "08:00", "18:00", "America/El_Salvador"))
				.thenReturn(List.of());
		when(spaceRepository.findAll(Sort.by("name"))).thenReturn(List.of(idle));

		OccupancyReportResponse report = service.occupancy(LocalDate.of(2040, 3, 4), LocalDate.of(2040, 3, 10));

		assertThat(report.spaces().get(0).availableHours()).isEqualByComparingTo("70.00");
	}

	@Test
	void roundsThePercentToTwoDecimals() {
		LocalDate day = LocalDate.of(2040, 3, 4);
		when(reservationRepository.findOccupiedSeconds("2040-03-04", "2040-03-04", "08:00", "18:00", "America/El_Salvador"))
				.thenReturn(List.of(row(busy.getId(), 3600)));
		when(spaceRepository.findAll(Sort.by("name"))).thenReturn(List.of(busy));

		assertThat(service.occupancy(day, day).spaces().get(0).occupancyPercent()).isEqualByComparingTo("10.00");
	}

	@Test
	void rejectsAnInvertedRangeWithoutQueryingTheDatabase() {
		assertThatThrownBy(() -> service.occupancy(LocalDate.of(2040, 3, 5), LocalDate.of(2040, 3, 4)))
				.isInstanceOf(BadRequestException.class);
		verifyNoInteractions(reservationRepository);
	}

	@Test
	void rejectsRangesLongerThanTheLimit() {
		LocalDate from = LocalDate.of(2040, 1, 1);

		assertThatThrownBy(() -> service.occupancy(from, from.plusDays(OccupancyReportService.MAX_RANGE_DAYS)))
				.isInstanceOf(BadRequestException.class);
		verify(reservationRepository, never()).findOccupiedSeconds("", "", "", "", "");
	}

	@Test
	void businessHoursMustOpenBeforeTheyClose() {
		assertThatThrownBy(() -> new AppProperties.BusinessHours(LocalTime.of(18, 0), LocalTime.of(8, 0), ZoneId.of("UTC")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
