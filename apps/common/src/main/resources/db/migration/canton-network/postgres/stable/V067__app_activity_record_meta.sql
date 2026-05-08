-- Metadata for activity record ingestion runs.
-- Tracks when ingestion started so we can determine the earliest round
-- with complete activity data and detect config version downgrades.
-- One row per (history_id, version) pair. A new row is inserted on each
-- version bump; previous rows are retained as an audit trail.
create table app_activity_record_meta
(
    -- History identifier for update history partitioning (same as update_history_id).
    history_id                        bigint not null,
    -- Code version of the ingestion logic. Bumped when the computation
    -- of app activity records is functionally changed.
    activity_ingestion_code_version   int not null,
    -- User-configured version, allowing operators to force a re-ingestion
    -- by incrementing the value in ScanAppConfig.
    activity_ingestion_user_version   int not null,
    -- Record time (microseconds since epoch) of the first verdict in the
    -- with activity records. Rounds before this time may be partial.
    started_ingesting_at              bigint not null,
    -- The earliest round number in the first batch with activity records ingested
    -- with this code version and user version
    earliest_ingested_round           bigint not null,

    constraint app_activity_record_meta_pkey primary key (
        history_id, activity_ingestion_code_version, activity_ingestion_user_version
    )
);
