package com.gerson.cowork.repository;

import com.gerson.cowork.entity.Space;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

	/**
	 * SELECT ... FOR UPDATE on the space row. Used to serialize concurrent reservations of the same space:
	 * concurrent inserts against the exclusion constraint alone can deadlock in PostgreSQL, whereas queuing on
	 * a single row lock cannot. Always lock a single row per transaction to keep lock ordering trivial.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Space s where s.id = :id")
	Optional<Space> findByIdForUpdate(@Param("id") UUID id);
}
