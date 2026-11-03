package com.gerson.cowork.repository;

import com.gerson.cowork.entity.Reservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	// space and user are LAZY: load them in the same query to avoid N+1 when mapping to DTOs.
	// Both are to-one associations, so the join does not break pagination.

	@Override
	@EntityGraph(attributePaths = {"space", "user"})
	Optional<Reservation> findById(UUID id);

	@Override
	@EntityGraph(attributePaths = {"space", "user"})
	Page<Reservation> findAll(Pageable pageable);

	@EntityGraph(attributePaths = {"space", "user"})
	Page<Reservation> findByUserId(UUID userId, Pageable pageable);

	/**
	 * Booked seconds per space, aggregated in the database. One business-hours window is generated per day of
	 * the range (in the given time zone) and every confirmed/completed reservation is clipped to the windows it
	 * overlaps, so time outside opening hours and outside the range is not counted.
	 */
	@Query(value = """
			WITH windows AS (
			    SELECT (CAST(day AS date) + CAST(:opening AS time)) AT TIME ZONE :zone AS open_at,
			           (CAST(day AS date) + CAST(:closing AS time)) AT TIME ZONE :zone AS close_at
			    FROM generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), INTERVAL '1 day') AS day
			)
			SELECT r.space_id AS "spaceId",
			       CAST(SUM(EXTRACT(EPOCH FROM (LEAST(r.end_at, w.close_at) - GREATEST(r.start_at, w.open_at)))) AS bigint)
			           AS "occupiedSeconds"
			FROM reservations r
			JOIN windows w ON r.start_at < w.close_at AND r.end_at > w.open_at
			WHERE r.status IN ('CONFIRMED', 'COMPLETED')
			GROUP BY r.space_id
			""", nativeQuery = true)
	List<SpaceOccupiedSeconds> findOccupiedSeconds(@Param("fromDate") String fromDate, @Param("toDate") String toDate,
			@Param("opening") String opening, @Param("closing") String closing, @Param("zone") String zone);
}
