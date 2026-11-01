package com.gerson.cowork.service;

import com.gerson.cowork.dto.space.SpaceRequest;
import com.gerson.cowork.dto.space.SpaceResponse;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.exception.ResourceNotFoundException;
import com.gerson.cowork.mapper.SpaceMapper;
import com.gerson.cowork.repository.SpaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SpaceService {

	private final SpaceRepository spaceRepository;
	private final SpaceMapper spaceMapper;

	public SpaceService(SpaceRepository spaceRepository, SpaceMapper spaceMapper) {
		this.spaceRepository = spaceRepository;
		this.spaceMapper = spaceMapper;
	}

	public Page<SpaceResponse> findAll(Pageable pageable) {
		return spaceRepository.findAll(pageable).map(spaceMapper::toResponse);
	}

	public SpaceResponse findById(UUID id) {
		return spaceMapper.toResponse(getOrThrow(id));
	}

	@Transactional
	public SpaceResponse create(SpaceRequest request) {
		return spaceMapper.toResponse(spaceRepository.save(spaceMapper.toEntity(request)));
	}

	@Transactional
	public SpaceResponse update(UUID id, SpaceRequest request) {
		Space space = getOrThrow(id);
		spaceMapper.apply(space, request);
		return spaceMapper.toResponse(space);
	}

	@Transactional
	public void delete(UUID id) {
		spaceRepository.delete(getOrThrow(id));
	}

	private Space getOrThrow(UUID id) {
		return spaceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Space", id));
	}
}
