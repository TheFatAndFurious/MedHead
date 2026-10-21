CREATE TABLE bed_reservation (
    id UUID PRIMARY KEY,
    hospital_id BIGINT NOT NULL REFERENCES hospital (id) ON DELETE CASCADE,
    specialty_code VARCHAR(100) NOT NULL REFERENCES specialty (code),
    reserved_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_bed_reservation_hospital ON bed_reservation (hospital_id);

CREATE TABLE event_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_event_outbox_pending ON event_outbox (occurred_at, id)
    WHERE published_at IS NULL;
