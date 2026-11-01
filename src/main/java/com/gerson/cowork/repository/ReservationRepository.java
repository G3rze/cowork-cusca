package com.gerson.cowork.repository;

import com.gerson.cowork.entity.Reservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
