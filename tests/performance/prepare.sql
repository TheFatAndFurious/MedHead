\set ON_ERROR_STOP on

-- This reset is deliberately restricted to the dedicated performance database.
DO $$ BEGIN
    IF current_database() <> 'hospital_performance' THEN
        RAISE EXCEPTION 'Expected isolated hospital_performance database';
    END IF;
END $$;

BEGIN;
TRUNCATE event_outbox, bed_reservation, hospital_specialty, hospital RESTART IDENTITY;

INSERT INTO specialty (code, name)
VALUES ('PERF_AVAILABLE', 'Performance available'), ('PERF_UNAVAILABLE', 'Performance unavailable')
ON CONFLICT (code) DO NOTHING;

-- 160,000 beds; enough for the full success campaign including warm-up.
INSERT INTO hospital (code, name, location, total_beds, available_beds)
SELECT 'PERF-' || lpad(n::text, 4, '0'), 'Performance Hospital ' || n,
       ST_SetSRID(ST_MakePoint(-5.8 + mod(n * 37, 850) / 100.0,
                               50.1 + mod(n * 53, 880) / 100.0), 4326)::geography,
       100, CASE WHEN n <= 1600 THEN 100 ELSE 0 END
FROM generate_series(1, 2000) AS n;

INSERT INTO hospital_specialty (hospital_id, specialty_id)
SELECT h.id, s.id FROM hospital h JOIN specialty s
ON s.code = CASE WHEN h.available_beds > 0 THEN 'PERF_AVAILABLE' ELSE 'PERF_UNAVAILABLE' END;

CREATE TABLE IF NOT EXISTS performance_baseline (
    hospital_id bigint PRIMARY KEY,
    available_beds integer NOT NULL
);
TRUNCATE performance_baseline;
INSERT INTO performance_baseline SELECT id, available_beds FROM hospital;
COMMIT;
ANALYZE hospital;
ANALYZE hospital_specialty;
