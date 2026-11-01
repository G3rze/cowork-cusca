package com.gerson.cowork.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.ReservationStatus;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.enums.SpaceType;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.repository.SpaceRepository;
import com.gerson.cowork.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Business hours are the defaults: 08:00-18:00 in America/El_Salvador (UTC-6, no daylight saving), i.e. a
 * 10-hour window per day. Dates in 2040 keep these tests away from the reservations created by other tests.
 */
class OccupancyReportIntegrationTest extends AbstractIntegrationTest {

	private static final ZoneId ZONE = ZoneId.of("America/El_Salvador");

	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private SpaceRepository spaceRepository;
	@Autowired
	private UserRepository userRepository;

	private User user;
	private Space space;

	@BeforeEach
	void setUp() {
		user = userRepository.save(User.builder().name("Reporter").email(uniqueEmail()).password("x").role(Role.USER).build());
		space = spaceRepository.save(Space.builder().name("Report room " + UUID.randomUUID()).type(SpaceType.DESK)
				.capacity(1).location("Floor 6").hourlyRate(BigDecimal.TEN).build());
	}

	private void book(ReservationStatus status, String start, String end) {
		reservationRepository.saveAndFlush(Reservation.builder().space(space).user(user)
				.startAt(LocalDateTime.parse(start).atZone(ZONE).toInstant())
				.endAt(LocalDateTime.parse(end).atZone(ZONE).toInstant())
				.status(status).totalPrice(BigDecimal.TEN).build());
	}

	private JsonNode report(String from, String to) throws Exception {
		String body = mockMvc.perform(get("/api/v1/reports/occupancy?from=" + from + "&to=" + to)
						.header("Authorization", bearer(adminToken())))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		for (JsonNode entry : objectMapper.readTree(body).get("spaces")) {
			if (entry.get("spaceId").asText().equals(space.getId().toString())) {
				return entry;
			}
		}
		throw new AssertionError("Space missing from the report");
	}

	@Test
	void countsOnlyConfirmedAndCompletedHoursInsideBusinessHours() throws Exception {
		LocalDate day = LocalDate.of(2040, 3, 4);
		book(ReservationStatus.CONFIRMED, day + "T10:00:00", day + "T12:00:00");   // 2 h
		book(ReservationStatus.COMPLETED, day + "T17:00:00", day + "T20:00:00");   // only 17-18 counts: 1 h
		book(ReservationStatus.CANCELLED, day + "T12:00:00", day + "T13:00:00");   // ignored
		book(ReservationStatus.PENDING_PAYMENT, day + "T13:00:00", day + "T14:00:00"); // ignored
		book(ReservationStatus.CONFIRMED, day.plusDays(1) + "T09:00:00", day.plusDays(1) + "T10:00:00"); // other day

		JsonNode oneDay = report(day.toString(), day.toString());
		assertThat(oneDay.get("occupiedHours").decimalValue()).isEqualByComparingTo("3.00");
		assertThat(oneDay.get("availableHours").decimalValue()).isEqualByComparingTo("10.00");
		assertThat(oneDay.get("occupancyPercent").decimalValue()).isEqualByComparingTo("30.00");

		JsonNode twoDays = report(day.toString(), day.plusDays(1).toString());
		assertThat(twoDays.get("occupiedHours").decimalValue()).isEqualByComparingTo("4.00");
		assertThat(twoDays.get("availableHours").decimalValue()).isEqualByComparingTo("20.00");
		assertThat(twoDays.get("occupancyPercent").decimalValue()).isEqualByComparingTo("20.00");
	}

	@Test
	void reservationCrossingMidnightIsSplitAcrossDays() throws Exception {
		LocalDate day = LocalDate.of(2040, 3, 10);
		book(ReservationStatus.CONFIRMED, day + "T20:00:00", day.plusDays(1) + "T09:00:00");

		assertThat(report(day.toString(), day.toString()).get("occupiedHours").decimalValue()).isEqualByComparingTo("0.00");
		// Only 08:00-09:00 of the next day falls inside business hours
		assertThat(report(day.toString(), day.plusDays(1).toString()).get("occupiedHours").decimalValue())
				.isEqualByComparingTo("1.00");
	}

	@Test
	void spaceWithoutReservationsIsReportedAtZeroPercent() throws Exception {
		JsonNode entry = report("2040-04-01", "2040-04-07");

		assertThat(entry.get("occupiedHours").decimalValue()).isEqualByComparingTo("0.00");
		assertThat(entry.get("availableHours").decimalValue()).isEqualByComparingTo("70.00");
		assertThat(entry.get("occupancyPercent").decimalValue()).isEqualByComparingTo("0.00");
	}

	@Test
	void reportIsServedFromCacheUntilAReservationChanges() throws Exception {
		LocalDate day = LocalDate.of(2040, 5, 6);
		assertThat(report(day.toString(), day.toString()).get("occupiedHours").decimalValue()).isEqualByComparingTo("0.00");

		// Written straight to the database: no event, so the cached report must not notice it
		book(ReservationStatus.CONFIRMED, day + "T09:00:00", day + "T11:00:00");
		assertThat(report(day.toString(), day.toString()).get("occupiedHours").decimalValue()).isEqualByComparingTo("0.00");

		// Cancelling any reservation through the API publishes an event that evicts the cache after commit
		String userToken = userToken();
		String spaceId = createSpaceViaApi();
		String reservationId = reserveViaApi(userToken, spaceId);
		mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
				.header("Authorization", bearer(userToken))).andExpect(status().isOk());

		assertThat(report(day.toString(), day.toString()).get("occupiedHours").decimalValue()).isEqualByComparingTo("2.00");
	}

	@Test
	void onlyAdminsCanReadTheReport() throws Exception {
		mockMvc.perform(get("/api/v1/reports/occupancy?from=2040-01-01&to=2040-01-02"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/reports/occupancy?from=2040-01-01&to=2040-01-02")
						.header("Authorization", bearer(userToken())))
				.andExpect(status().isForbidden());
	}

	@Test
	void rejectsInvalidRanges() throws Exception {
		String admin = bearer(adminToken());
		mockMvc.perform(get("/api/v1/reports/occupancy?from=2040-01-10&to=2040-01-01").header("Authorization", admin))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/reports/occupancy?from=2040-01-01&to=2042-01-01").header("Authorization", admin))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/reports/occupancy?from=2040-01-01").header("Authorization", admin))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/reports/occupancy?from=nope&to=2040-01-01").header("Authorization", admin))
				.andExpect(status().isBadRequest());
	}

	private String createSpaceViaApi() throws Exception {
		String response = mockMvc.perform(post("/api/v1/spaces")
						.header("Authorization", bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("name", "Api room " + UUID.randomUUID(), "type", "DESK", "capacity", 1,
								"location", "Floor 7", "hourlyRate", 5))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	private String reserveViaApi(String token, String spaceId) throws Exception {
		java.time.Instant start = java.time.Instant.now().plus(120, java.time.temporal.ChronoUnit.DAYS)
				.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
		String response = mockMvc.perform(post("/api/v1/reservations")
						.header("Authorization", bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("spaceId", spaceId, "startAt", start.toString(),
								"endAt", start.plusSeconds(3600).toString()))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}
}
