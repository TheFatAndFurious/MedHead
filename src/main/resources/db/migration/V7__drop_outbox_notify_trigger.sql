-- A NOTIFY in the reservation transaction makes PostgreSQL hold a global exclusive lock from
-- commit until the WAL flush, so reservation commits went through one at a time. The
-- publisher is now woken in memory after commit, with periodic polling as a safety net.
DROP TRIGGER IF EXISTS event_outbox_inserted ON event_outbox;
DROP FUNCTION IF EXISTS notify_event_outbox();
