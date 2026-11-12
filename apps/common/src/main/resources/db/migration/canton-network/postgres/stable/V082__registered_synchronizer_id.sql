-- The synchronizer id of a dedicated synchronizer's registration, for Scan's lookup by it.
alter table scan_acs_store add column registered_synchronizer_id text;
