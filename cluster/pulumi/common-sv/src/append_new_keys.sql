BEGIN
  -- Append for insert-only tables written in bursts, such as
  -- interned_strings. hourly_append.sql closes its window an hour
  -- before the newest staging row, so the last hour of a burst would
  -- wait in staging for the next burst, and be purged if that came
  -- after the retention period. Rows of these tables never change, so
  -- instead every row whose key isn't in production yet is appended.

  {{createProdTable}}

  -- 2. Append the staging rows whose key production doesn't have yet,
  -- one per key: staging is append-only, so a key can be there more
  -- than once, e.g. when a backfill re-delivers a row
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
