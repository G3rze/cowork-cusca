package com.gerson.cowork.repository;

import com.gerson.cowork.entity.Reservation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	// space and user are LAZY: load them in the same query to avoid N+1 when mapping to DTOs
	@Override
	@EntityGraph(attributePaths = {"space", "user"})
	Optional<Reservation> findById(UUID id);

	@Override
	@EntityGraph(attributePaths = {"space", "user"})
	List<Reservation> findAll();

	@EntityGraph(attributePaths = {"space", "user"})
	List<Reservation> findByUserId(UUID userId);
}
