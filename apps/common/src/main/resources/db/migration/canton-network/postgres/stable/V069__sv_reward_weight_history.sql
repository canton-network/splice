alter table scan_txlog_store
    add column sv_onboarding_party text;
alter table scan_txlog_store
    add column sv_onboarding_effective_at bigint;

alter table scan_txlog_store
    add column vote_sv_party text;

-- Supporting indices are created concurrently by SqlIndexInitializationTrigger
-- to avoid blocking ingestion on large scan_txlog_store tables.

-- Backfill vote_sv_party for vote-result rows ingested before this migration.
update scan_txlog_store
set vote_sv_party = entry_data #>> '{result,request,action,ARC_DsoRules,dsoAction,SRARC_UpdateSvRewardWeight,svParty}'
where entry_type = 'vot'
  and vote_action_name = 'SRARC_UpdateSvRewardWeight'
  and vote_sv_party is null;
