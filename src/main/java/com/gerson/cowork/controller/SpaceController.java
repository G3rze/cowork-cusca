package com.gerson.cowork.controller;

import com.gerson.cowork.dto.space.SpaceRequest;
import com.gerson.cowork.dto.space.SpaceResponse;
import com.gerson.cowork.service.SpaceService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/spaces")
public class SpaceController {

	private final SpaceService spaceService;

	public SpaceController(SpaceService spaceService) {
		this.spaceService = spaceService;
	}

	@GetMapping
	public Page<SpaceResponse> findAll(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
		return spaceService.findAll(pageable);
	}

	@GetMapping("/{id}")
	public SpaceResponse findById(@PathVariable UUID id) {
		return spaceService.findById(id);
	}

	@PostMapping
	public ResponseEntity<SpaceResponse> create(@Valid @RequestBody SpaceRequest request) {
		SpaceResponse created = spaceService.create(request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	@PutMapping("/{id}")
	public SpaceResponse update(@PathVariable UUID id, @Valid @RequestBody SpaceRequest request) {
		return spaceService.update(id, request);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		spaceService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
