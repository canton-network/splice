-- 2. Production rows appended before a `since` column was listed have
-- it NULL. Fill those that staging still has, from their latest
-- change, where that is not NULL, including an empty array, which
-- Datastream replicates as JSON `[]`; older rows keep NULL. This runs
-- every hour, so it also fills the rows of an append that ran before
-- the fix, and retries a fill that failed. Skipped until both staging
-- and production have every such column.
IF (
  SELECT COUNT(DISTINCT column_name) FROM {{stagingColumnsView}}
  WHERE table_name = '{{stagingTableName}}'
    AND column_name IN UNNEST({{sinceColumnArray}})
) = ARRAY_LENGTH({{sinceColumnArray}}) AND (
  SELECT COUNT(DISTINCT column_name) FROM {{prodColumnsView}}
  WHERE table_name = '{{prodTableName}}'
    AND column_name IN UNNEST({{sinceColumnArray}})
) = ARRAY_LENGTH({{sinceColumnArray}}) THEN
  -- Bounded by production's record_date, not the record time: it is
  -- production's partition column, so the bound prunes partitions,
  -- and every table has it, whereas the record time is `record_time`
  -- in micros in some tables and `datastream_metadata.source_timestamp`
  -- in others. A whole day covers every row that staging still has.
  SET oldest_record_date = (
    SELECT MIN({{recordDateExpr}})
    FROM {{stagingTable}} AS staging
  );
  IF EXISTS (
    SELECT 1 FROM {{prodTable}} AS prod
    WHERE prod.record_date >= oldest_record_date
      AND ({{prodLacksAny}})
  ) THEN
    UPDATE {{prodTable}} AS prod
    SET
      {{fillColumns}}
    FROM (
      SELECT
        {{fillSourceColumns}}
      FROM {{stagingTable}} AS staging
      WHERE TRUE
      QUALIFY ROW_NUMBER() OVER (
        PARTITION BY {{primaryKeyExpr}}
        ORDER BY
          staging.datastream_metadata.source_timestamp DESC,
          staging.datastream_metadata.change_sequence_number DESC
      ) = 1
    ) AS latest
    WHERE prod.record_date >= oldest_record_date
      AND {{primaryKeyMatch}}
      AND ({{fillableAny}});
  END IF;
END IF;
