-- Server-side idempotency for non-idempotent POSTs (create / confirm a reservation).
-- The key is scoped per user so one user can never read or collide with another user's keys.
-- The primary key is the arbiter: a "check if exists, then insert" would race, the constraint cannot.
CREATE TABLE idempotency_keys (
    user_id         UUID         NOT NULL REFERENCES users (id),
    idem_key        VARCHAR(64)  NOT NULL,
    -- SHA-256 of the operation and its parameters: the same key with a different request is rejected
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PROCESSING', 'DONE')),
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key),
    CONSTRAINT chk_idempotency_done_has_response CHECK (status <> 'DONE' OR response_status IS NOT NULL)
);

-- Supports the periodic purge of expired keys
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
