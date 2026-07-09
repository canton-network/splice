-------------------------------------------------------------------------------
-- Placeholders:
-------------------------------------------------------------------------------
--   ${flyway:defaultSchema}  -> target schema, replaced by a string literal by Flyway
--   ${flyway:user}           -> the app's DB login role (also the migration runner),
--                               replaced by a string literal by Flyway
--   current_user             -> the "current user" variable defined in postgres.

-------------------------------------------------------------------------------
-- 1.  Create a standing ddl_owner role
-- This is a role that never logs in, holds no password, appears in no app config.
-- It exists only to permanently hold DDL rights and own functions that need to run with DDL rights.
-------------------------------------------------------------------------------
DO $bootstrap$
DECLARE
    v_schema text := '${flyway:defaultSchema}';
    v_user   text := current_user;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ddl_owner') THEN
        CREATE ROLE ddl_owner NOLOGIN;
        EXECUTE format('GRANT CREATE ON SCHEMA %I TO ddl_owner', v_schema);
    END IF;

    -- The migrating role temporarily needs to act as ddl_owner to transfer
    -- ownership of the function. This membership will be revoked at the end of the migration.
    IF NOT pg_has_role(v_user, 'ddl_owner', 'MEMBER') THEN
        EXECUTE format('GRANT ddl_owner TO %I', v_user);
    END IF;
END
$bootstrap$;

-------------------------------------------------------------------------------
-- 2. Add a function to create a new table for an ACS snapshot
-- SECURITY DEFINER means the function runs with the privileges of its owner (ddl_owner), not the caller.
-- This allows the application to create new tables without having direct DDL rights.
-------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".create_acs_snapshot_table_v1(snapshot_record_time bigint)
    RETURNS text
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS $func$
DECLARE
    c_schema constant text := '${flyway:defaultSchema}';
    c_app    constant text := '${flyway:user}';
    v_name   text := 'acs_snapshot_data_' || snapshot_record_time::text;
    v_qual   text := format('%I.%I', c_schema, v_name);
    v_user   text := current_user;
BEGIN
    -- Step 1: create the table
    EXECUTE format($ddl$
        CREATE TABLE %s (
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            contract_payload    jsonb       NOT NULL,
            -- ... other columns
        )
        WITH (fillfactor = 100) -- Fill pages completely since the table will be read-only after creation
    $ddl$, v_qual);

    -- Step 2: fill it with data. This is the only time rows will be inserted into the table.
    EXECUTE format($ins$
        INSERT INTO %s (contract_payload)
        SELECT contract_payload
        FROM acs_incremental_snapshot_data_next
        WHERE ...
        ORDER BY ... -- Pick a physical ordering that corresponds to the most common ordering when reading data
    $ins$, v_qual, snapshot_recordtime);

    -- Step 3: create indexes. Creating indexes after inserting data is faster than creating them before.
    -- Use fillfactor 100 since we know the table will be read-only after creation, and we want to avoid page splits.
    EXECUTE format('CREATE INDEX %I ON %s (...) WITH (fillfactor = 100);', v_name || '_idx', v_qual);

    -- At this point we would like to execute VACUUM (ANALYZE, FREEZE) to populate statistics and freeze tuples,
    -- but this cannot run within a transaction. We'll let the application execute it afterwards.

    -- After VACCUUM (ANALYZE, FREEZE) ran (and only then, think about crash fault tolerance!),
    -- we can disable autovacuum:
    -- ALTER TABLE %s SET (
    --     autovacuum_enabled = false,
    --     toast.autovacuum_enabled = false
    -- );

    -- Step 4: transfer ownership to the application role
    EXECUTE format('ALTER TABLE %s OWNER to %I', v_qual, c_app);

    -- Step 5: register the new table in a custom catalogue table so the application knows about it
    INSERT INTO acs_snapshots (table_name, record_time, ...) VALUES (v_name, snapshot_recordtime, ...);

    RETURN v_name;
END
$func$;

-------------------------------------------------------------------------------
-- 3. Hand ownership of the above function to ddl_owner
-------------------------------------------------------------------------------
DO $lockdown$
DECLARE
    v_schema text := '${flyway:defaultSchema}';
    v_app    text := '${flyway:user}';
    v_sig    text;
BEGIN
    v_sig := format('%I.create_acs_snapshot_table_v1(snapshot_record_time)', v_schema);

    -- Transfer ownership so the definer context is ddl_owner, not the app role.
    EXECUTE format('ALTER FUNCTION %s OWNER TO ddl_owner', v_sig);

    -- Postgres grants EXECUTE to PUBLIC on new functions by default.
    -- At this point we could lock down who can call the function
    -- SECURITY DEFINER that would let ANY role run privileged DDL. Close it.
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', v_sig);
    EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO %I', v_sig, v_app);
END
$lockdown$;
