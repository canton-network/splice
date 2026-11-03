BEGIN
  DECLARE current_watermark_micros INT64;
  DECLARE current_watermark_ts TIMESTAMP;
  DECLARE max_available_timestamp TIMESTAMP;
  DECLARE max_closed_timestamp TIMESTAMP;
  DECLARE staging_columns STRING;

  {{createProdTable}}

  -- 2. Create central watermark tracking table using INT64 (microseconds)
  CREATE TABLE IF NOT EXISTS {{watermarksTable}} (
    table_name STRING,
    last_watermark_time INT64
  );

  -- 3. Initialize watermark to epoch zero (0 micros) if missing
  IF NOT EXISTS (
    SELECT 1 
    FROM {{watermarksTable}} 
    WHERE table_name = '{{tableName}}'
  ) THEN
    INSERT INTO {{watermarksTable}} 
    VALUES ('{{tableName}}', 0);
  END IF;

  -- 4. Get current scalar watermark in INT64 micros and convert to TIMESTAMP
  SET current_watermark_micros = (
    SELECT MAX(last_watermark_time) 
    FROM {{watermarksTable}} 
    WHERE table_name = '{{tableName}}'
  );

  SET current_watermark_ts = TIMESTAMP_MICROS(current_watermark_micros);

  -- 5. Fetch max available timestamp from staging WITH PARTITION PRUNING
  SET max_available_timestamp = COALESCE(
    (
      SELECT MAX({{recordTimestampExpr}}) 
      FROM {{stagingTable}} AS staging
      WHERE (
          staging._PARTITIONTIME >= TIMESTAMP_SUB(current_watermark_ts, INTERVAL 3 HOUR)
          OR staging._PARTITIONTIME IS NULL
        )
    ),
    -- Fall back to current_watermark_ts if no new data arrived in the last 3 hours
    current_watermark_ts
  );

  -- Subtract 1 hour to safely close the ingestion window
  SET max_closed_timestamp = TIMESTAMP_SUB(max_available_timestamp, INTERVAL 1 HOUR);

  -- 6. Staging columns, by name. The production schema is fixed when
  -- it is created in step 1, but Datastream adds columns to staging
  -- when they are added in Postgres (e.g. the `*_interned` columns of
  -- update_history_*). Read before temp_incremental, so it has every
  -- column listed; a column added since only reaches rows newer than
  -- max_closed_timestamp.
  SET staging_columns = (
    SELECT STRING_AGG(FORMAT('`%s`', column_name), ', ' ORDER BY ordinal_position)
    FROM {{stagingInfoSchemaColumns}}
    WHERE table_name = '{{stagingTableName}}'
      AND is_hidden = 'NO' -- e.g. _PARTITIONTIME
  );

  -- 6a. Filter incremental data with Primary Key Deduplication &
  -- Partition Pruning
  CREATE TEMP TABLE temp_incremental AS (
    SELECT 
      staging.*, 
      {{recordDateExpr}} AS record_date
    FROM {{stagingTable}} AS staging
    WHERE (
        -- Cost control: Scan 3-hour partition window
        staging._PARTITIONTIME >= TIMESTAMP_SUB(current_watermark_ts, INTERVAL 3 HOUR)
        -- Streaming buffer protection for recent arrivals
        OR staging._PARTITIONTIME IS NULL
      )
      AND {{recordTimestampExpr}} > current_watermark_ts
      AND {{recordTimestampExpr}} <= max_closed_timestamp
    -- Primary Key Deduplication
    QUALIFY ROW_NUMBER() OVER (
      PARTITION BY {{primaryKeyExpr}}
      ORDER BY 
        staging.datastream_metadata.source_timestamp DESC,
        staging.datastream_metadata.change_sequence_number DESC
    ) = 1
  );

  -- 7. Perform append and update watermark if valid new records exist
  IF EXISTS (SELECT 1 FROM temp_incremental) THEN

    -- 7a. Add staging columns missing from production, so they are
    -- not dropped
    FOR new_column IN (
      SELECT column_name, data_type
      FROM {{stagingInfoSchemaColumns}}
      WHERE table_name = '{{stagingTableName}}'
        AND is_hidden = 'NO'
        AND column_name NOT IN (
          SELECT column_name
          FROM {{prodInfoSchemaColumns}}
          WHERE table_name = '{{tableName}}'
        )
      ORDER BY ordinal_position
    ) DO
      EXECUTE IMMEDIATE FORMAT(
        'ALTER TABLE %s ADD COLUMN IF NOT EXISTS `%s` %s',
        '{{prodTable}}', new_column.column_name, new_column.data_type
      );
    END FOR;

    -- 7b. Append by column name; production columns are no longer in
    -- staging order
    EXECUTE IMMEDIATE FORMAT(
      'INSERT INTO %s (%s, record_date) SELECT %s, record_date FROM temp_incremental',
      '{{prodTable}}', staging_columns, staging_columns
    );

    UPDATE {{watermarksTable}}
    SET last_watermark_time = UNIX_MICROS(max_closed_timestamp)
    WHERE table_name = '{{tableName}}'
    AND last_watermark_time < UNIX_MICROS(max_closed_timestamp);

  END IF;
END;