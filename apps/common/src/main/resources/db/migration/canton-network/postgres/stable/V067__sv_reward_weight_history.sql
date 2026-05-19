-- Columns for SvOnboardingTxLogEntry index extraction.
alter table scan_txlog_store
    add column sv_onboarding_party text;
alter table scan_txlog_store
    add column sv_onboarding_weight bigint;
alter table scan_txlog_store
    add column sv_onboarding_effective_at bigint;

-- Column for the existing VoteRequestTxLogEntry, populated only when the
-- vote action is SRARC_UpdateSvRewardWeight. Sparse on purpose.
alter table scan_txlog_store
    add column vote_sv_party text;

-- Partial index supporting "most recent accepted weight-change vote for
-- SV X before time T". vote_effective_at is a text column on the
-- existing schema (see V020); ISO-8601 sort-order is lexicographic so
-- the DESC order is correct.
create index scan_txlog_store_sv_weight_vote_idx
    on scan_txlog_store (store_id, vote_sv_party, vote_effective_at desc)
    where entry_type = 'vot'
      and vote_action_name = 'SRARC_UpdateSvRewardWeight'
      and vote_accepted = true;

-- Partial index supporting "most recent AddSv for SV X before time T".
create index scan_txlog_store_sv_onboarding_idx
    on scan_txlog_store (store_id, sv_onboarding_party, sv_onboarding_effective_at desc)
    where entry_type = 'asv';

-- Backfill vote_sv_party for already-ingested SRARC_UpdateSvRewardWeight vote-result rows.
-- TxLogBackfilling does NOT re-derive index columns for existing rows (it only inserts rows
-- for transactions absent from the store, using an existence check — see HistoryBackfilling +
-- DbMultiDomainAcsStore.doInsertEntries). Therefore this UPDATE is required.
--
-- The entry_data column stores the full VoteRequestTxLogEntry as ScalaPB JSON
-- (scalapb.json4s.JsonFormat.toJsonString). The 'result' field is a google.protobuf.Struct
-- carrying a DsoRules_CloseVoteRequestResult serialized via the Java SDK JsonLfEncoder.
-- Java SDK variants are encoded as {"VARIANT_TAG": value} (not {"tag": ..., "value": ...}).
-- Verified path from codegen:
--   ARC_DsoRules.fieldForJsonEncoder -> Field.of("ARC_DsoRules", {dsoAction: ...})
--   SRARC_UpdateSvRewardWeight.fieldForJsonEncoder -> Field.of("SRARC_UpdateSvRewardWeight", ...)
--   DsoRules_UpdateSvRewardWeight.jsonEncoder -> Field.of("svParty", ...)
-- Full JSON path: result.request.action.ARC_DsoRules.dsoAction.SRARC_UpdateSvRewardWeight.svParty
-- NOTE: verify this path against real data before merge if possible.
update scan_txlog_store
set vote_sv_party = entry_data #>> '{result,request,action,ARC_DsoRules,dsoAction,SRARC_UpdateSvRewardWeight,svParty}'
where entry_type = 'vot'
  and vote_action_name = 'SRARC_UpdateSvRewardWeight'
  and vote_sv_party is null;
