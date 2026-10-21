\set ON_ERROR_STOP on

BEGIN;

-- The cascading foreign key also removes the corresponding specialty links.
DELETE FROM hospital
WHERE code LIKE 'DEMO-%';

COMMIT;

\ir seed-demo.sql
