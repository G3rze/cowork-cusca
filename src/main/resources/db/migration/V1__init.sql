-- Required later for the exclusion constraint that prevents overlapping reservations
-- (combines equality on space_id with range overlap on the time slot).
CREATE EXTENSION IF NOT EXISTS btree_gist;
