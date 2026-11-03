BEGIN
  -- Append for insert-only tables that are written in bursts, such as interned_strings.
  -- hourly_append.sql closes its window an hour before the newest staging row, so the last hour of a
  -- burst would wait in staging for the next burst, and be purged if that's after the retention
  -- period. Rows of these tables never change, so instead every staging row whose key isn't in
  -- production yet is appended, however old it is and whenever it arrived.

  -- 1. Check if production table exists; if not, create shell table (as in hourly_append.sql)
  IF NOT EXISTS (
    SELECT 1
    FROM {{prodInfoSchema}}
    WHERE table_name = '{{tableName}}'
  ) THEN
    CREATE TABLE {{prodTable}}
    PARTITION BY record_date AS
    SELECT
      *,
      {{recordDateExpr}} AS record_date
    FROM {{stagingTable}} AS staging
    WHERE 1 = 0;
  END IF;

  -- 2. Append one row per key that production doesn't have yet. A backfill re-delivers rows the
  -- stream already delivered; they are skipped here, so production never has duplicate keys.
  INSERT INTO {{prodTable}}
  SELECT
    staging.*,
    {{recordDateExpr}} AS record_date
  FROM {{stagingTable}} AS staging
  WHERE NOT EXISTS (
    SELECT 1
    FROM {{prodTable}} AS prod
    WHERE prod.{{primaryKeyExpr}} = staging.{{primaryKeyExpr}}
  )
  QUALIFY ROW_NUMBER() OVER (
    PARTITION BY staging.{{primaryKeyExpr}}
    ORDER BY
      staging.datastream_metadata.source_timestamp DESC,
      staging.datastream_metadata.change_sequence_number DESC
  ) = 1;
END;
