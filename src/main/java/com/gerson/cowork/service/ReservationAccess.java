package com.gerson.cowork.service;

import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.exception.ResourceNotFoundException;
import com.gerson.cowork.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

/** Ownership rule shared by every reservation operation. */
@Component
public class ReservationAccess {

	/** A USER asking for someone else's reservation gets 404, so ids do not reveal what exists. */
	public void check(AuthenticatedUser principal, Reservation reservation) {
		boolean isOwner = reservation.getUser().getId().equals(principal.id());
		if (principal.role() != Role.ADMIN && !isOwner) {
			throw new ResourceNotFoundException("Reservation", reservation.getId());
		}
	}
}
