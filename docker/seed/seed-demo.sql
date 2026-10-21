\set ON_ERROR_STOP on

BEGIN;

-- Fictional hospitals and capacities for local demonstrations. Coordinates are
-- deliberately spread across Greater London so nearest-neighbour queries have
-- observable results. Replaying this script preserves the current availability.
INSERT INTO hospital AS current_hospital (
    code,
    name,
    location,
    total_beds,
    available_beds
)
VALUES
    ('DEMO-LON-001', 'MedHead Westminster', ST_SetSRID(ST_MakePoint(-0.1276, 51.5072), 4326)::geography, 350, 17),
    ('DEMO-LON-002', 'MedHead South Bank',   ST_SetSRID(ST_MakePoint(-0.1167, 51.5007), 4326)::geography, 240,  0),
    ('DEMO-LON-003', 'MedHead Chelsea',      ST_SetSRID(ST_MakePoint(-0.1680, 51.4875), 4326)::geography, 180,  7),
    ('DEMO-LON-004', 'MedHead Camden',       ST_SetSRID(ST_MakePoint(-0.1426, 51.5390), 4326)::geography, 300, 12),
    ('DEMO-LON-005', 'MedHead Whitechapel',  ST_SetSRID(ST_MakePoint(-0.0612, 51.5196), 4326)::geography, 420,  4),
    ('DEMO-LON-006', 'MedHead Greenwich',    ST_SetSRID(ST_MakePoint( 0.0098, 51.4826), 4326)::geography, 210,  9),
    ('DEMO-LON-007', 'MedHead Hammersmith',  ST_SetSRID(ST_MakePoint(-0.2235, 51.4927), 4326)::geography, 260,  2),
    ('DEMO-LON-008', 'MedHead Lewisham',     ST_SetSRID(ST_MakePoint(-0.0145, 51.4569), 4326)::geography, 190,  0),
    ('DEMO-LON-009', 'MedHead Islington',    ST_SetSRID(ST_MakePoint(-0.1032, 51.5362), 4326)::geography, 160,  5),
    ('DEMO-LON-010', 'MedHead Croydon',      ST_SetSRID(ST_MakePoint(-0.1004, 51.3762), 4326)::geography, 280, 15),
    ('DEMO-LON-011', 'MedHead Barnet',       ST_SetSRID(ST_MakePoint(-0.2000, 51.6500), 4326)::geography, 220,  6),
    ('DEMO-LON-012', 'MedHead Kingston',     ST_SetSRID(ST_MakePoint(-0.3064, 51.4123), 4326)::geography, 230, 11)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    location = EXCLUDED.location,
    total_beds = EXCLUDED.total_beds,
    available_beds = LEAST(current_hospital.available_beds, EXCLUDED.total_beds);

WITH assignments (hospital_code, specialty_code) AS (
    VALUES
        ('DEMO-LON-001', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-001', 'CARDIOLOGY'),
        ('DEMO-LON-001', 'GENERAL_SURGERY'),
        ('DEMO-LON-001', 'NEUROLOGY'),
        ('DEMO-LON-001', 'CLINICAL_RADIOLOGY'),
        ('DEMO-LON-001', 'ANAESTHESIA'),
        ('DEMO-LON-001', 'INTENSIVE_CARE'),
        ('DEMO-LON-002', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-002', 'PAEDIATRICS'),
        ('DEMO-LON-002', 'PAEDIATRIC_CARDIOLOGY'),
        ('DEMO-LON-002', 'GENERAL_SURGERY'),
        ('DEMO-LON-002', 'ANAESTHESIA'),
        ('DEMO-LON-003', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-003', 'CARDIOLOGY'),
        ('DEMO-LON-003', 'GERIATRIC_MEDICINE'),
        ('DEMO-LON-003', 'RHEUMATOLOGY'),
        ('DEMO-LON-003', 'PALLIATIVE_MEDICINE'),
        ('DEMO-LON-004', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-004', 'NEUROLOGY'),
        ('DEMO-LON-004', 'NEUROSURGERY'),
        ('DEMO-LON-004', 'CLINICAL_NEUROPHYSIOLOGY'),
        ('DEMO-LON-004', 'REHABILITATION_MEDICINE'),
        ('DEMO-LON-005', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-005', 'INFECTIOUS_DISEASES'),
        ('DEMO-LON-005', 'RESPIRATORY_MEDICINE'),
        ('DEMO-LON-005', 'RENAL_MEDICINE'),
        ('DEMO-LON-005', 'MEDICAL_MICROBIOLOGY'),
        ('DEMO-LON-005', 'INTENSIVE_CARE'),
        ('DEMO-LON-006', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-006', 'GENERAL_INTERNAL_MEDICINE'),
        ('DEMO-LON-006', 'GASTROENTEROLOGY'),
        ('DEMO-LON-006', 'ENDOCRINOLOGY_DIABETES_MELLITUS'),
        ('DEMO-LON-006', 'DERMATOLOGY'),
        ('DEMO-LON-007', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-007', 'TRAUMA_ORTHOPAEDIC_SURGERY'),
        ('DEMO-LON-007', 'PLASTIC_SURGERY'),
        ('DEMO-LON-007', 'SPORT_EXERCISE_MEDICINE'),
        ('DEMO-LON-007', 'CLINICAL_RADIOLOGY'),
        ('DEMO-LON-008', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-008', 'GENERAL_PRACTICE'),
        ('DEMO-LON-008', 'SEXUAL_REPRODUCTIVE_HEALTH'),
        ('DEMO-LON-008', 'PAEDIATRICS'),
        ('DEMO-LON-008', 'ANAESTHESIA'),
        ('DEMO-LON-009', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-009', 'GENERAL_PSYCHIATRY'),
        ('DEMO-LON-009', 'CHILD_ADOLESCENT_PSYCHIATRY'),
        ('DEMO-LON-009', 'PSYCHOTHERAPY'),
        ('DEMO-LON-009', 'LEARNING_DISABILITY_PSYCHIATRY'),
        ('DEMO-LON-010', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-010', 'CARDIOLOGY'),
        ('DEMO-LON-010', 'CARDIOTHORACIC_SURGERY'),
        ('DEMO-LON-010', 'VASCULAR_SURGERY'),
        ('DEMO-LON-010', 'HAEMATOLOGY'),
        ('DEMO-LON-011', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-011', 'GERIATRIC_MEDICINE'),
        ('DEMO-LON-011', 'OLD_AGE_PSYCHIATRY'),
        ('DEMO-LON-011', 'OPHTHALMOLOGY'),
        ('DEMO-LON-011', 'OTOLARYNGOLOGY'),
        ('DEMO-LON-012', 'EMERGENCY_MEDICINE'),
        ('DEMO-LON-012', 'UROLOGY'),
        ('DEMO-LON-012', 'RENAL_MEDICINE'),
        ('DEMO-LON-012', 'GENERAL_SURGERY'),
        ('DEMO-LON-012', 'CLINICAL_ONCOLOGY')
)
INSERT INTO hospital_specialty (hospital_id, specialty_id)
SELECT hospital.id, specialty.id
FROM assignments
JOIN hospital ON hospital.code = assignments.hospital_code
JOIN specialty ON specialty.code = assignments.specialty_code
ON CONFLICT (hospital_id, specialty_id) DO NOTHING;

COMMIT;
