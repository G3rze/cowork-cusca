-- The database is the arbiter of overlaps: a check-then-insert in application code is a race condition
-- when two requests (or two pods) reserve the same slot at the same time.
-- Only active reservations block a slot; cancelled and completed ones free it.
-- tstzrange is half-open ([start, end)), so back-to-back reservations (10:00-11:00 and 11:00-12:00) are allowed.
ALTER TABLE reservations
    ADD CONSTRAINT no_overlapping_active_reservations
    EXCLUDE USING gist (
        space_id WITH =,
        tstzrange(start_at, end_at) WITH &&
    ) WHERE (status IN ('PENDING_PAYMENT', 'CONFIRMED'));
