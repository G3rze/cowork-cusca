package com.gerson.cowork.repository;

import com.gerson.cowork.entity.Space;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpaceRepository extends JpaRepository<Space, UUID> {
}
