-- CIP-0104 extension hook (PoC): tables for the "extra" activity records
-- that future CIPs inject via the ActivityRecorder_DsoRecordActivity choice.
-- Shape mirrors app_activity_record_store (post-V082) and
-- app_activity_record_meta (post-V071).
--
-- Source difference vs the app-side sibling: these rows are derived from
-- ActivityRecorder exercises in update history, not from mediator verdicts.
-- Consequently there is no FK to scan_verdict_store.
--
-- PK is (history_id, round_number, record_time). This is a PoC stand-in
-- for a proper source-event identity — a production ingestion path may
-- need a sharper discriminator (e.g. update_history event id) if more than
-- one ActivityRecorder_DsoRecordActivity exercise can land at the same
-- record_time within a batch. The PK is also what ON CONFLICT DO NOTHING
-- keys against on insert, so that Canton's retry-on-transient path stays
-- idempotent (same pattern as app_activity_record_store's PK on verdict_row_id).
--
-- Populated by a separate ingestion service (not in this PoC).

create table extra_app_activity_record_store
(
    -- History identifier for update-history partitioning (mirrors
    -- app_activity_record_store.history_id).
    history_id                      bigint                   not null,
    -- The mining round number to which this activity is assigned,
    -- resolved from the OpenMiningRound active at record_time.
    round_number                    bigint                   not null,
    -- Record time (microseconds since epoch) of the ActivityRecorder
    -- exercise that produced this row.
    record_time                     bigint                   not null,
    -- App providers for which app activity should be recorded (bytes of traffic),
    -- in one-to-one correspondence with app_activity_weights.
    app_provider_parties            text[]                   not null default '{}',
    app_activity_weights            bigint[]                 not null default '{}',
    -- Asset providers for which asset activity should be recorded (bytes of traffic),
    -- in one-to-one correspondence with asset_activity_weights.
    asset_activity_provider_parties text[]                   not null default '{}',
    asset_activity_weights          bigint[]                 not null default '{}',
    constraint extra_app_activity_record_store_pkey
        primary key (history_id, round_number, record_time)
);


-- Metadata for extra-activity-record ingestion runs. One row per
-- (history_id, code_version, user_version) tuple. Mirrors
-- app_activity_record_meta exactly: a new row is inserted on each version
-- bump; previous rows are retained as an audit trail.
--
-- Note on earliest_ingested_round: the external design doc proposes
-- deriving this from DsoRules.svSwitchOverTimes["cip-104/enable-extra-activity-after"],
-- but that switchover-time key does not yet exist on splice-side DsoRules.
-- Non-initial-SV logic is stubbed in the Scala store (step 2) until the
-- Daml side carries the key; the schema here does not encode the rule.
create table extra_app_activity_record_meta
(
    history_id                        bigint      not null,
    -- Code version of the ingestion logic. Bumped when the computation
    -- of extra activity records is functionally changed.
    activity_ingestion_code_version   int         not null,
    -- User-configured version, allowing operators to force a re-ingestion
    -- by incrementing the value in ScanAppConfig.
    activity_ingestion_user_version   int         not null,
    -- Record time (microseconds since epoch) of the first ActivityRecorder
    -- exercise seen by this ingestion-version pair.
    started_ingesting_at              bigint      not null,
    -- The earliest round number in the first batch ingested with this
    -- code version and user version.
    earliest_ingested_round           bigint      not null,
    -- Highest OpenMiningRound round number archived as of the max
    -- record_time of the ingested updates.
    last_archived_round               bigint,
    last_updated_at                   timestamptz default now(),

    constraint extra_app_activity_record_meta_pkey primary key (
        history_id, activity_ingestion_code_version, activity_ingestion_user_version
    )
);
