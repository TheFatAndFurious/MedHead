\set ON_ERROR_STOP on

BEGIN;

-- Deterministic synthetic dataset for repeatable performance tests. Replaying
-- this script updates static data while preserving the current bed availability.
WITH generated_hospital AS (
    SELECT
        sequence_number,
        'PERF-' || LPAD(sequence_number::text, 4, '0') AS code,
        'Synthetic Hospital ' || LPAD(sequence_number::text, 4, '0') AS name,
        -5.8 + MOD(sequence_number * 37, 850) / 100.0 AS longitude,
        50.1 + MOD(sequence_number * 53, 880) / 100.0 AS latitude,
        80 + MOD(sequence_number, 321) AS total_beds,
        CASE
            WHEN MOD(sequence_number, 5) = 0 THEN 0
            ELSE 1 + MOD(sequence_number, 20)
        END AS available_beds
    FROM generate_series(1, 2000) AS sequence_number
)
INSERT INTO hospital AS current_hospital (
    code,
    name,
    location,
    total_beds,
    available_beds
)
SELECT
    code,
    name,
    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
    total_beds,
    available_beds
FROM generated_hospital
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    location = EXCLUDED.location,
    total_beds = EXCLUDED.total_beds,
    available_beds = LEAST(current_hospital.available_beds, EXCLUDED.total_beds);

WITH numbered_specialty AS (
    SELECT
        id,
        ROW_NUMBER() OVER (ORDER BY code) AS position,
        COUNT(*) OVER () AS specialty_count
    FROM specialty
),
performance_hospital AS (
    SELECT
        id,
        SUBSTRING(code FROM 6)::integer AS sequence_number
    FROM hospital
    WHERE code LIKE 'PERF-%'
)
INSERT INTO hospital_specialty (hospital_id, specialty_id)
SELECT hospital.id, specialty.id
FROM performance_hospital AS hospital
CROSS JOIN generate_series(0, 4) AS offset_value(value)
JOIN numbered_specialty AS specialty
    ON specialty.position = MOD(
        hospital.sequence_number - 1 + offset_value.value * 13,
        specialty.specialty_count
    ) + 1
ON CONFLICT (hospital_id, specialty_id) DO NOTHING;

COMMIT;
