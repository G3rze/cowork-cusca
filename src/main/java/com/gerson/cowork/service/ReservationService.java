package com.gerson.cowork.service;

import com.gerson.cowork.dto.reservation.ReservationCreateRequest;
import com.gerson.cowork.dto.reservation.ReservationResponse;
import com.gerson.cowork.entity.Reservation;
import com.gerson.cowork.entity.Space;
import com.gerson.cowork.entity.User;
import com.gerson.cowork.enums.Role;
import com.gerson.cowork.exception.OverlappingReservationException;
import com.gerson.cowork.exception.ResourceNotFoundException;
import com.gerson.cowork.mapper.ReservationMapper;
import com.gerson.cowork.pricing.PriceCalculator;
import com.gerson.cowork.repository.ReservationRepository;
import com.gerson.cowork.repository.SpaceRepository;
import com.gerson.cowork.repository.UserRepository;
import com.gerson.cowork.security.AuthenticatedUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReservationService {

	/** Name of the exclusion constraint created in V3__prevent_overlapping_reservations.sql */
	static final String NO_OVERLAP_CONSTRAINT = "no_overlapping_active_reservations";

	private final ReservationRepository reservationRepository;
	private final SpaceRepository spaceRepository;
	private final UserRepository userRepository;
	private final PriceCalculator priceCalculator;
	private final ReservationMapper reservationMapper;

	public ReservationService(ReservationRepository reservationRepository, SpaceRepository spaceRepository,
			UserRepository userRepository, PriceCalculator priceCalculator, ReservationMapper reservationMapper) {
		this.reservationRepository = reservationRepository;
		this.spaceRepository = spaceRepository;
		this.userRepository = userRepository;
		this.priceCalculator = priceCalculator;
		this.reservationMapper = reservationMapper;
	}

	/**
	 * Overlaps are not checked with a query: a check-then-insert would race with a concurrent request. Instead
	 * the space row is locked to queue concurrent reservations of that space (avoiding the deadlocks that
	 * simultaneous inserts against an exclusion constraint can cause), and the insert is attempted: the
	 * database exclusion constraint, which is atomic, is the final arbiter. The timeout bounds how long a
	 * request may hold its connection waiting for the lock.
	 */
	@Transactional(timeout = 5)
	public ReservationResponse create(AuthenticatedUser principal, ReservationCreateRequest request) {
		Space space = spaceRepository.findByIdForUpdate(request.spaceId())
				.orElseThrow(() -> new ResourceNotFoundException("Space", request.spaceId()));
		User user = userRepository.findById(principal.id())
				.orElseThrow(() -> new ResourceNotFoundException("User", principal.id()));

		Reservation reservation = Reservation.builder()
				.space(space)
				.user(user)
				.startAt(request.startAt())
				.endAt(request.endAt())
				.totalPrice(priceCalculator.calculate(space, request.startAt(), request.endAt()))
				.build();
		try {
			return reservationMapper.toResponse(reservationRepository.saveAndFlush(reservation));
		} catch (DataIntegrityViolationException ex) {
			if (isOverlap(ex)) {
				throw new OverlappingReservationException();
			}
			throw ex;
		}
	}

	/** USER sees only their own reservations; ADMIN sees all of them. */
	public Page<ReservationResponse> findAll(AuthenticatedUser principal, Pageable pageable) {
		Page<Reservation> page = principal.role() == Role.ADMIN
				? reservationRepository.findAll(pageable)
				: reservationRepository.findByUserId(principal.id(), pageable);
		return page.map(reservationMapper::toResponse);
	}

	public ReservationResponse findById(AuthenticatedUser principal, UUID id) {
		return reservationMapper.toResponse(findAccessible(principal, id));
	}

	/**
	 * The status change is flushed at commit with the {@code @Version} check, so two concurrent transitions on
	 * the same reservation cannot overwrite each other (the loser gets a 409).
	 */
	@Transactional
	public ReservationResponse cancel(AuthenticatedUser principal, UUID id) {
		Reservation reservation = findAccessible(principal, id);
		reservation.cancel();
		return reservationMapper.toResponse(reservation);
	}

	/** A USER asking for someone else's reservation gets 404, so ids do not reveal what exists. */
	private Reservation findAccessible(AuthenticatedUser principal, UUID id) {
		Reservation reservation = reservationRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
		boolean isOwner = reservation.getUser().getId().equals(principal.id());
		if (principal.role() != Role.ADMIN && !isOwner) {
			throw new ResourceNotFoundException("Reservation", id);
		}
		return reservation;
	}

	private boolean isOverlap(DataIntegrityViolationException ex) {
		String message = ex.getMostSpecificCause().getMessage();
		return message != null && message.contains(NO_OVERLAP_CONSTRAINT);
	}
}
