package com.gerson.cowork.service;

import com.gerson.cowork.dto.space.SpaceRequest;
import com.gerson.cowork.dto.space.SpaceResponse;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.enums.SpaceType;
import com.gerson.cowork.exception.ResourceNotFoundException;
import com.gerson.cowork.mapper.SpaceMapper;
import com.gerson.cowork.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {

	@Mock
	private SpaceRepository spaceRepository;

	private SpaceService service;
	private final SpaceRequest request = new SpaceRequest("Room B", SpaceType.EVENT_HALL, 40, "Floor 2", new BigDecimal("55.00"));

	@BeforeEach
	void setUp() {
		service = new SpaceService(spaceRepository, new SpaceMapper());
	}

	@Test
	void createPersistsTheRequestedValues() {
		when(spaceRepository.save(any(Space.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SpaceResponse response = service.create(request);

		assertThat(response.name()).isEqualTo("Room B");
		assertThat(response.type()).isEqualTo(SpaceType.EVENT_HALL);
		assertThat(response.hourlyRate()).isEqualByComparingTo("55.00");
	}

	@Test
	void updateChangesTheExistingSpaceInPlace() {
		Space existing = Space.builder().id(UUID.randomUUID()).name("Old").type(SpaceType.DESK).capacity(1)
				.location("Old").hourlyRate(BigDecimal.ONE).build();
		when(spaceRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

		SpaceResponse response = service.update(existing.getId(), request);

		assertThat(response.id()).isEqualTo(existing.getId());
		assertThat(existing.getName()).isEqualTo("Room B");
		assertThat(existing.getCapacity()).isEqualTo(40);
	}

	@Test
	void missingSpaceIsReportedAsNotFound() {
		UUID id = UUID.randomUUID();
		when(spaceRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findById(id)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.update(id, request)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResourceNotFoundException.class);
		verify(spaceRepository, never()).delete(any());
	}
}
