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
