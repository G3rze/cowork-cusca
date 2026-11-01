package com.gerson.cowork.mapper;

import com.gerson.cowork.dto.space.SpaceRequest;
import com.gerson.cowork.dto.space.SpaceResponse;
import com.gerson.cowork.entity.Space;
import org.springframework.stereotype.Component;

@Component
public class SpaceMapper {

	public Space toEntity(SpaceRequest request) {
		Space space = new Space();
		apply(space, request);
		return space;
	}

	public void apply(Space space, SpaceRequest request) {
		space.setName(request.name());
		space.setType(request.type());
		space.setCapacity(request.capacity());
		space.setLocation(request.location());
		space.setHourlyRate(request.hourlyRate());
	}

	public SpaceResponse toResponse(Space space) {
		return new SpaceResponse(space.getId(), space.getName(), space.getType(), space.getCapacity(),
				space.getLocation(), space.getHourlyRate());
	}
}
