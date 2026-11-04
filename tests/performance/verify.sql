\set ON_ERROR_STOP on
SELECT current_database() = 'hospital_performance' AS isolated \gset
\if :isolated
\else
    \echo 'Expected isolated hospital_performance database'
    DO $$ BEGIN RAISE EXCEPTION 'Expected isolated performance database'; END $$;
\endif

SELECT
    (SELECT count(*) FROM bed_reservation) = :created
    AND (SELECT count(*) FROM event_outbox) = :created
    AND (SELECT sum(b.available_beds - h.available_beds)
         FROM hospital h JOIN performance_baseline b ON b.hospital_id = h.id) = :created
    AND NOT EXISTS (
        SELECT 1 FROM hospital h JOIN performance_baseline b ON b.hospital_id = h.id
        WHERE b.available_beds - h.available_beds <>
              (SELECT count(*) FROM bed_reservation r WHERE r.hospital_id = h.id)
           OR h.available_beds < 0
    )
    AND NOT EXISTS (
        SELECT 1 FROM bed_reservation r LEFT JOIN event_outbox e ON e.aggregate_id = r.id
        GROUP BY r.id HAVING count(e.id) <> 1
    )
    AND NOT EXISTS (
        SELECT 1 FROM event_outbox e LEFT JOIN bed_reservation r ON r.id = e.aggregate_id
        WHERE r.id IS NULL OR e.event_type <> 'BedReserved'
           OR e.payload->>'reservationId' IS DISTINCT FROM r.id::text
           OR e.payload->>'eventId' IS DISTINCT FROM e.id::text
           OR e.payload->>'hospitalId' IS DISTINCT FROM r.hospital_id::text
           OR e.payload->>'specialtyCode' IS DISTINCT FROM r.specialty_code
    ) AS consistent \gset
\if :consistent
    \echo 'PASS: beds, reservations and outbox are consistent'
\else
    \echo 'FAIL: beds, reservations or outbox differ from HTTP results'
    DO $$ BEGIN RAISE EXCEPTION 'Performance data consistency check failed'; END $$;
\endif

SELECT count(*) AS pending_events,
       coalesce(extract(epoch FROM now() - min(occurred_at)), 0) AS oldest_pending_seconds
FROM event_outbox WHERE published_at IS NULL;
