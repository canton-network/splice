-- 2. Production rows appended before a column was added have it
-- NULL. Fill those that staging still has, from their latest change;
-- older rows keep NULL. This runs only in the hour that adds the
-- column, so if it fails, run this UPDATE by hand.
IF ARRAY_LENGTH(added) > 0 THEN
  SET oldest_record_date = (
    SELECT MIN({{recordDateExpr}})
    FROM {{stagingTable}} AS staging
  );
  UPDATE {{prodTable}} AS prod
  SET
    {{fillColumns}}
  FROM (
    SELECT
      {{fillSourceColumns}}
    FROM {{stagingTable}} AS staging
    QUALIFY ROW_NUMBER() OVER (
      PARTITION BY {{primaryKeyExpr}}
      ORDER BY
        staging.datastream_metadata.source_timestamp DESC,
        staging.datastream_metadata.change_sequence_number DESC
    ) = 1
  ) AS latest
  WHERE prod.record_date >= oldest_record_date
    AND {{primaryKeyMatch}};
END IF;
