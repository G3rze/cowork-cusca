package com.gerson.cowork.integration;

import com.gerson.cowork.dto.reservation.ReservationCreateRequest;
import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.ReservationStatus;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.enums.SpaceType;
import com.gerson.cowork.exception.InvalidReservationTransitionException;
import com.gerson.cowork.exception.OverlappingReservationException;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.repository.SpaceRepository;
import com.gerson.cowork.repository.UserRepository;
import com.gerson.cowork.security.AuthenticatedUser;
import com.gerson.cowork.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the concurrency guarantees against a real PostgreSQL: many threads race at the same instant
 * (all released by a latch) and the database, not the application code, keeps the data consistent.
 */
class ReservationConcurrencyIntegrationTest extends AbstractIntegrationTest {

	private static final int THREADS = 50;
	private static final Instant BASE = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	@Autowired
	private ReservationService reservationService;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private SpaceRepository spaceRepository;
	@Autowired
	private UserRepository userRepository;

	private Space newSpace() {
		return spaceRepository.save(Space.builder().name("Room " + UUID.randomUUID()).type(SpaceType.MEETING_ROOM)
				.capacity(10).location("Floor 4").hourlyRate(BigDecimal.TEN).build());
	}

	private List<AuthenticatedUser> newUsers(int count) {
		return IntStream.range(0, count)
				.mapToObj(i -> userRepository.save(User.builder().name("Racer " + i).email(uniqueEmail())
						.password("x").role(Role.USER).build()))
				.map(user -> new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER))
				.toList();
	}

	/** Runs every task on its own thread, releasing them together, and returns each outcome (null = success). */
	private List<Throwable> raceAll(List<Callable<?>> tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Throwable>> futures = new ArrayList<>();
			for (Callable<?> task : tasks) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						task.call();
						return null;
					} catch (Throwable ex) {
						return ex;
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Throwable> outcomes = new ArrayList<>();
			for (Future<Throwable> future : futures) {
				outcomes.add(future.get(30, TimeUnit.SECONDS));
			}
			return outcomes;
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void onlyOneOfFiftyConcurrentRequestsGetsTheSameSlot() throws Exception {
		Space space = newSpace();
		List<AuthenticatedUser> users = newUsers(THREADS);
		ReservationCreateRequest request = new ReservationCreateRequest(space.getId(), BASE, BASE.plus(2, ChronoUnit.HOURS));

		List<Throwable> outcomes = raceAll(users.stream()
				.<Callable<?>>map(user -> () -> reservationService.create(user, request)).toList());

		long successes = outcomes.stream().filter(outcome -> outcome == null).count();
		List<Throwable> failures = outcomes.stream().filter(outcome -> outcome != null).toList();
		assertThat(successes).isEqualTo(1);
		assertThat(failures).hasSize(THREADS - 1).allMatch(failure -> failure instanceof OverlappingReservationException);
		assertThat(reservationRepository.findAll(org.springframework.data.domain.Pageable.unpaged()).stream()
				.filter(r -> r.getSpace().getId().equals(space.getId())))
				.hasSize(1);
	}

	@Test
	void concurrentRequestsForDifferentSlotsAllSucceed() throws Exception {
		Space space = newSpace();
		List<AuthenticatedUser> users = newUsers(20);
		List<Callable<?>> tasks = new ArrayList<>();
		for (int i = 0; i < users.size(); i++) {
			AuthenticatedUser user = users.get(i);
			ReservationCreateRequest request = new ReservationCreateRequest(space.getId(),
					BASE.plus(i, ChronoUnit.HOURS), BASE.plus(i + 1, ChronoUnit.HOURS));
			tasks.add(() -> reservationService.create(user, request));
		}

		List<Throwable> outcomes = raceAll(tasks);

		assertThat(outcomes).allMatch(outcome -> outcome == null);
	}

	@Test
	void concurrentCancellationsOfTheSameReservationTransitionOnlyOnce() throws Exception {
		Space space = newSpace();
		AuthenticatedUser owner = newUsers(1).get(0);
		UUID reservationId = reservationService.create(owner,
				new ReservationCreateRequest(space.getId(), BASE, BASE.plus(1, ChronoUnit.HOURS))).id();

		List<Throwable> outcomes = raceAll(IntStream.range(0, 10)
				.<Callable<?>>mapToObj(i -> () -> reservationService.cancel(owner, reservationId)).toList());

		long successes = outcomes.stream().filter(outcome -> outcome == null).count();
		assertThat(successes).isEqualTo(1);
		// The losers either saw the already-cancelled state or lost the @Version check at commit
		assertThat(outcomes.stream().filter(outcome -> outcome != null))
				.allMatch(failure -> failure instanceof InvalidReservationTransitionException
						|| failure instanceof ObjectOptimisticLockingFailureException);
		Reservation stored = reservationRepository.findById(reservationId).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
	}
}
