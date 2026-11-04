CREATE FUNCTION notify_event_outbox() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- PostgreSQL delivers this wake-up only if the inserting transaction commits.
    PERFORM pg_notify('medhead_outbox', '');
    RETURN NULL;
END;
$$;

CREATE TRIGGER event_outbox_inserted
AFTER INSERT ON event_outbox
FOR EACH STATEMENT EXECUTE FUNCTION notify_event_outbox();
