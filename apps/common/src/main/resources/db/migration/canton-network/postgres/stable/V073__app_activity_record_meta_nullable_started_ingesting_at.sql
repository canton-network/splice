-- Allow started_ingesting_at to be NULL so the firstSV bootstrap can
-- create the meta row (for round completeness) before the sequencer
-- starts serving traffic.  The column is populated when the first
-- verdict batch with traffic summaries arrives.
alter table app_activity_record_meta alter column started_ingesting_at drop not null;
