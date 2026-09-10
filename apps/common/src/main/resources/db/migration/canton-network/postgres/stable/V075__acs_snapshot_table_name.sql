alter table acs_snapshot
  -- the name 'table_name' is not reserved, but it is a PostgreSQL keyword, so we use a different name to avoid confusion
  add column data_table_name text default null,
  -- these values won't be set anymore
  drop constraint acs_snapshot_first_row_id_fkey,
  drop constraint acs_snapshot_last_row_id_fkey,
  alter column first_row_id drop not null,
  alter column last_row_id drop not null,
  -- ensure consistency
  add constraint legacy_or_per_snapshot check
    -- per-snapshot tables
    ((first_row_id is null and last_row_id is null and data_table_name is not null) or
    -- legacy table
    (first_row_id is not null and last_row_id is not null and data_table_name is null));
