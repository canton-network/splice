BEGIN
  DECLARE added ARRAY<STRING>;
  DECLARE oldest_record_date DATE;

  -- 1. Add the listed columns that production lacks
  CALL {{fixRoutineName}}({{fixRoutineArgs}}, added);

  {{fillAddedColumns}}
END;
