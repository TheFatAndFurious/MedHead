ALTER TABLE hospital
    ADD COLUMN code VARCHAR(100);

-- Existing development rows receive a deterministic compatibility code.
UPDATE hospital
SET code = 'LEGACY-' || LPAD(id::text, 8, '0')
WHERE code IS NULL;

ALTER TABLE hospital
    ALTER COLUMN code SET NOT NULL;

ALTER TABLE hospital
    ADD CONSTRAINT uk_hospital_code UNIQUE (code);
