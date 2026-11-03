package com.gerson.cowork.repository;

import java.util.UUID;

/** Row of the occupancy aggregation: seconds a space was booked inside the business-hours windows. */
public interface SpaceOccupiedSeconds {

	UUID getSpaceId();

	Long getOccupiedSeconds();
}
