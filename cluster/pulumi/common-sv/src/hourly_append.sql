BEGIN
  DECLARE current_watermark TIMESTAMP;
  DECLARE max_available_timestamp TIMESTAMP;
  DECLARE max_closed_timestamp TIMESTAMP;

  -- 1. Check if production table exists; if not, create shell table
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

  -- 2. Create central watermark tracking table
  CREATE TABLE IF NOT EXISTS {{watermarksTable}} (
    table_name STRING,
    last_watermark_time TIMESTAMP
  );

  -- 3. Initialize watermark to epoch zero if missing
  IF NOT EXISTS (
    SELECT 1 
    FROM {{watermarksTable}} 
    WHERE table_name = '{{tableName}}'
  ) THEN
    INSERT INTO {{watermarksTable}} 
    VALUES ('{{tableName}}', TIMESTAMP_MICROS(0));
  END IF;

  -- 4. Get current scalar watermark value
  SET current_watermark = (
    SELECT MAX(last_watermark_time) 
    FROM {{watermarksTable}} 
    WHERE table_name = '{{tableName}}'
  );

  -- 5. Fetch max available timestamp from staging
  SET max_available_timestamp = COALESCE(
    (
      SELECT MAX({{recordTimestampExpr}}) 
      FROM {{stagingTable}} AS staging
    ),
    TIMESTAMP_MICROS(0)
  );

  -- Subtract 1 hour to safely close the ingestion window
  SET max_closed_timestamp = TIMESTAMP_SUB(max_available_timestamp, INTERVAL 1 HOUR);

  -- 6. Filter incremental data batch into a temp table
  CREATE TEMP TABLE temp_incremental AS (
    SELECT 
      staging.*, 
      {{recordDateExpr}} AS record_date
    FROM {{stagingTable}} AS staging
    WHERE {{recordTimestampExpr}} > current_watermark
      AND {{recordTimestampExpr}} <= max_closed_timestamp
  );

  -- 7. Perform append and advance watermark if new records exist
  IF EXISTS (SELECT 1 FROM temp_incremental) THEN
    
    INSERT INTO {{prodTable}}
    SELECT * FROM temp_incremental;

    UPDATE {{watermarksTable}}
    SET last_watermark_time = max_closed_timestamp
    WHERE table_name = '{{tableName}}';

  END IF;
END;