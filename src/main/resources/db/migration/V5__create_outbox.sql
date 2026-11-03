-- Transactional outbox: events are written in the same transaction as the business change, so they exist if and
-- only if that change committed. A separate publisher delivers them afterwards (at-least-once).
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    -- PENDING: to be delivered (possibly retried) / SENT: delivered / FAILED: gave up (dead letter, kept for review)
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- Lease taken by the worker delivering the event; when it expires another worker may take over (crash recovery)
    locked_until    TIMESTAMP WITH TIME ZONE,
    last_error      TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at         TIMESTAMP WITH TIME ZONE
);

-- Keeps the polling query cheap no matter how many delivered rows pile up
CREATE INDEX idx_outbox_due ON outbox_events (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);
-- Supports the purge of delivered events
CREATE INDEX idx_outbox_sent_at ON outbox_events (sent_at) WHERE status = 'SENT';
