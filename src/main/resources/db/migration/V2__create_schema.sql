CREATE TABLE users (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(150) NOT NULL UNIQUE,
    password   VARCHAR(100) NOT NULL,
    role       VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE spaces (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100)   NOT NULL,
    type        VARCHAR(30)    NOT NULL CHECK (type IN ('MEETING_ROOM', 'DESK', 'PRIVATE_OFFICE', 'EVENT_HALL')),
    capacity    INTEGER        NOT NULL CHECK (capacity > 0),
    location    VARCHAR(200)   NOT NULL,
    hourly_rate NUMERIC(10, 2) NOT NULL CHECK (hourly_rate >= 0),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE reservations (
    id                UUID PRIMARY KEY,
    space_id          UUID           NOT NULL REFERENCES spaces (id),
    user_id           UUID           NOT NULL REFERENCES users (id),
    start_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    status            VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'COMPLETED')),
    total_price       NUMERIC(10, 2) NOT NULL CHECK (total_price >= 0),
    payment_reference VARCHAR(100),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_reservation_range CHECK (end_at > start_at)
);

-- Foreign keys are not indexed automatically in PostgreSQL
CREATE INDEX idx_reservations_space_start ON reservations (space_id, start_at);
CREATE INDEX idx_reservations_user ON reservations (user_id);
CREATE INDEX idx_reservations_status ON reservations (status);
