package com.gerson.cowork.mapper;

import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.entity.Reservation;
import org.springframework.stereotype.Component;

@Component
public class ReservationMapper {

	public ReservationResponse toResponse(Reservation reservation) {
		return new ReservationResponse(
				reservation.getId(),
				reservation.getSpace().getId(),
				reservation.getSpace().getName(),
				reservation.getUser().getId(),
				reservation.getUser().getName(),
				reservation.getStartAt(),
				reservation.getEndAt(),
				reservation.getStatus(),
				reservation.getTotalPrice(),
				reservation.getPaymentReference(),
				reservation.getCreatedAt());
	}
}
