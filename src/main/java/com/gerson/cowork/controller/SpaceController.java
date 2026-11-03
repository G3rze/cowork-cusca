package com.gerson.cowork.controller;

import com.gerson.cowork.dto.space.SpaceRequest;
import com.gerson.cowork.dto.space.SpaceResponse;
import com.gerson.cowork.service.SpaceService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Spaces")
@RequestMapping("/api/v1/spaces")
public class SpaceController {

	private final SpaceService spaceService;

	public SpaceController(SpaceService spaceService) {
		this.spaceService = spaceService;
	}

	@Operation(summary = "List spaces (paged)", description = "ADMIN and USER.")
	@GetMapping
	public Page<SpaceResponse> findAll(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
		return spaceService.findAll(pageable);
	}

	@Operation(summary = "Get a space", description = "ADMIN and USER.")
	@GetMapping("/{id}")
	public SpaceResponse findById(@PathVariable UUID id) {
		return spaceService.findById(id);
	}

	@Operation(summary = "Create a space", description = "ADMIN only.")
	@PostMapping
	public ResponseEntity<SpaceResponse> create(@Valid @RequestBody SpaceRequest request) {
		SpaceResponse created = spaceService.create(request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	@Operation(summary = "Update a space", description = "ADMIN only.")
	@PutMapping("/{id}")
	public SpaceResponse update(@PathVariable UUID id, @Valid @RequestBody SpaceRequest request) {
		return spaceService.update(id, request);
	}

	@Operation(summary = "Delete a space", description = "ADMIN only. Fails with 409 if it has reservations.")
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		spaceService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
