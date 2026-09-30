-- Test-only. Expand, step 2: the database fills the new column for a writer that does not know it
-- exists, and the rows written before step 1 are backfilled with the same rule.
--
-- A UUID-shaped key is cast; any other key maps deterministically to md5(key)::uuid, so a retried
-- submission of an old key still lands on the same uuid and still collides.
CREATE FUNCTION idempotency_uuid_of(key text) RETURNS uuid
    LANGUAGE sql IMMUTABLE AS $$
        SELECT CASE
            WHEN key ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                THEN key::uuid
            ELSE md5(key)::uuid
        END
    $$;

CREATE FUNCTION fill_idempotency_uuid() RETURNS trigger
    LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.idempotency_uuid IS NULL THEN
                NEW.idempotency_uuid := idempotency_uuid_of(NEW.idempotency_key);
            END IF;
            RETURN NEW;
        END
    $$;

CREATE TRIGGER loan_trade_fill_idempotency_uuid
    BEFORE INSERT ON loan_trade
    FOR EACH ROW EXECUTE FUNCTION fill_idempotency_uuid();

UPDATE loan_trade
   SET idempotency_uuid = idempotency_uuid_of(idempotency_key)
 WHERE idempotency_uuid IS NULL;
