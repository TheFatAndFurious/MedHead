-- One row per accepted reservation request, identified by the Idempotency-Key
-- header chosen by the client. The key is scoped to the authenticated subject:
-- a key reused by another account never returns someone else's reservation.
CREATE TABLE reservation_request (
    requested_by VARCHAR(255) NOT NULL,
    idempotency_key UUID NOT NULL,
    -- SHA-256 of the coordinates and specialty. A key reused for a different
    -- request is refused instead of returning an unrelated reservation.
    request_fingerprint CHAR(64) NOT NULL,
    reservation_id UUID NOT NULL REFERENCES bed_reservation (id) ON DELETE CASCADE,
    -- Response of the first call, returned unchanged when the request is repeated.
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_reservation_request PRIMARY KEY (requested_by, idempotency_key),
    CONSTRAINT uk_reservation_request_reservation UNIQUE (reservation_id)
);
