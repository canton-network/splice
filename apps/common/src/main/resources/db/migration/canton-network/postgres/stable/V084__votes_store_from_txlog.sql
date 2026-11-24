-- Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Populates votes_store_results with the vote results of the scan txlog, so that Scans that have them already
-- do not have to backfill them from their peers (see VotesStoreBackfilling).
--
-- This is meant for the SVs on the long-running networks, whose txlog and update history are complete.
-- For each scan app in the database, the script
--   1. checks that the txlog and the update history cover everything from the start of the network to where
--      the ingestion of the votes store took over,
--   2. copies the vote results of the txlog, with the contract ids from the update history,
--   3. marks the votes store as complete.
-- A scan for which a check of its state fails is skipped: its votes store stays as it is, and VotesStoreBackfilling
-- fetches the vote results from peers. A skipped scan is logged at info level with the reason (SQLSTATE VS001).
-- If the copied data does not pass the check after the copy, the copy is rolled back, and the failure is logged
-- as a warning (SQLSTATE VS002): this is not expected on any scan, and needs to be investigated.
-- (Codes ending in 000 cannot be used: in an exception handler, they match every code of their class.)
--
-- This migration must never fail, as that would prevent the app from starting. We do not expect any data that
-- the statements below cannot handle, but if there is, the error is logged as a warning and the scan is skipped
-- like above. The steps for a scan are all or nothing: if one fails, the earlier ones are rolled back.
--
-- On a database that has no scan txlog, e.g., because this is the first start of a scan, this script does nothing.

do
$$
declare
    -- One row per scan app: the store ids of its txlog, key value store and update history
    scan           record;
    -- The record time (in microseconds) of the first transaction that the votes store ingested
    first_ingested bigint;
    -- The current migration id of the update history
    current_migration_id bigint;
    num_copied     bigint;
    -- The number of vote results in the votes store without a contract id, after the copy
    num_without_contract_id bigint;
begin
    for scan in
        -- The txlog stores of the scan app (the version distinguishes them from its ACS stores, which have
        -- the same name), with the key value store and the update history of the same party and participant.
        select txlog.id as txlog_store_id,
               kv.id    as kv_store_id,
               history.id as history_id
        from store_descriptors txlog
                 left join store_descriptors kv
                           on kv.descriptor ->> 'name' = 'ScanKeyValueStore'
                               and kv.descriptor ->> 'party' = txlog.descriptor ->> 'party'
                               and kv.descriptor ->> 'participant' = txlog.descriptor ->> 'participant'
                 left join update_history_descriptors history
                           on history.store_name = txlog.descriptor ->> 'name'
                               and history.party = txlog.descriptor ->> 'party'
                               and history.participant_id = txlog.descriptor ->> 'participant'
        where txlog.descriptor ->> 'name' = 'DbScanStore'
          and (txlog.descriptor ->> 'version')::int = 1
        order by txlog.id
        loop
            -- A block with an exception handler is a subtransaction: everything in it is rolled back on an error.
            begin
                raise info 'votes_store_from_txlog: checking txlog store % (key value store %, update history %)',
                    scan.txlog_store_id, scan.kv_store_id, scan.history_id;

                -- Check 1: the txlog contains every vote result since the start of the network.
                -- On a new scan, it takes a few iterations of the backfilling trigger until this is recorded.
                if not exists (select 1
                               from txlog_backfilling_status
                               where store_id = scan.txlog_store_id
                                 and backfilling_complete) then
                    raise exception using errcode = 'VS001',
                        message = 'the backfilling of the txlog is not complete';
                end if;

                -- Check 2: the update history contains every update since the start of the network,
                -- so that the contract ids can be looked up in it.
                if not exists (select 1
                               from update_history_backfilling
                               where history_id = scan.history_id
                                 and complete) then
                    raise exception using errcode = 'VS001',
                        message = 'the backfilling of the update history is not complete';
                end if;

                -- Check 3: the votes store has started ingesting. From that point on it has the votes itself.
                -- The txlog is ingested in the same SQL transaction, so it has all votes up to that point.
                select (value #>> '{}')::bigint
                into first_ingested
                from key_value_store
                where store_id = scan.kv_store_id
                  and key = 'votes_store_first_ingested_record_time_' || scan.txlog_store_id;
                if first_ingested is null then
                    raise exception using errcode = 'VS001',
                        message = 'the votes store has not started ingesting';
                end if;

                -- Check 4: the update history has ingested up to that point as well.
                -- The votes store started ingesting in the current migration id, so it is enough to look at
                -- that one. update_history_last_ingested_offsets has a row for every migration id that the app
                -- ingested live (not for older ones that it backfilled), so the current one is the highest.
                -- It is read with a backward scan of its primary key (history_id, migration_id).
                -- Including the migration id in the exists makes it a single probe of the index
                -- updt_hist_tran_hi_mi_rt_di (history_id, migration_id, record_time, domain_id). Without it,
                -- Postgres versions before 18 (which cannot skip-scan) would walk the entries of all older
                -- migration ids.
                select max(o.migration_id)
                into current_migration_id
                from update_history_last_ingested_offsets o
                where o.history_id = scan.history_id;
                if not exists (select 1
                               from update_history_transactions u
                               where u.history_id = scan.history_id
                                 and u.migration_id = current_migration_id
                                 and u.record_time >= first_ingested) then
                    raise exception using errcode = 'VS001',
                        message = 'the update history has not ingested up to where the votes store started';
                end if;

                -- Fill the contract ids of the vote results that were ingested before V081__votes_store_contract_id.sql
                -- added the column, and that V081 could not find. The ingestion always writes the contract id, so
                -- this is the only way that a row can be without one. This can happen without any data issue:
                -- the votes store and the update history are ingested by separate subscriptions, so when the app
                -- was stopped for the upgrade that ran V081, the update history might not have ingested the latest
                -- votes yet. It has ingested them since. Same lookup as for the copy below, without the migration id,
                -- which votes_store_results does not have: the transaction by the index updt_hist_tran_hi_ui
                -- (history_id, update_id).
                update votes_store_results v
                set contract_id = (select e.contract_id
                                   from update_history_transactions u
                                            join update_history_exercises e on e.update_row_id = u.row_id
                                   where u.history_id = scan.history_id
                                     and u.domain_id = v.synchronizer_id
                                     and u.update_id = v.update_id
                                     and e.choice = 'DsoRules_CloseVoteRequest'
                                     and e.template_id_module_name = 'Splice.DsoRules'
                                     and e.template_id_entity_name = 'DsoRules'
                                     and e.consuming = false
                                   limit 1)
                where v.store_id = scan.txlog_store_id
                  and v.contract_id is null;

                -- Copy the vote results.
                -- - As of now, completing a vote is always the root exercise of a transaction, so its event number is 0.
                -- - The event id in the txlog has format '#<update id>:<node id>'.
                -- - Timestamps are ISO-8601 strings (e.g., 2024-03-01T10:00:00.123456Z), converted to microseconds
                --   since the epoch.
                -- - The rows are read with the index scan_txlog_store_sid_et_ei (store_id, entry_type, event_id),
                --   which only touches the vote entries of this store.
                -- - The contract id of the DsoRules on which the choice was exercised is not in the txlog. It is
                --   looked up in the update history, per vote:
                --   - the transaction by the unique index updt_hist_tran_hi_mi_ui_u
                --     (history_id, migration_id, domain_id, update_id), a single row,
                --   - its exercises by a prefix of the index updt_hist_exer_unique (update_row_id, event_id).
                --   The txlog and the update history are ingested from the same updates, so the migration id and the
                --   synchronizer id of a vote are the same in both. The record time is not needed to find the row.
                --   The contract id stays null if the update is not found. This is checked below.
                -- - Votes that are in votes_store_results already (ingested live) are skipped.
                insert
                into votes_store_results(store_id, record_time, synchronizer_id, update_id, event_number,
                                         contract_id,
                                         vote_action_name, vote_requester_name, vote_accepted, vote_tracking_cid,
                                         vote_completed_at, vote_effective_at, choice_result)
                select t.store_id,
                       t.record_time,
                       t.domain_id,
                       vote.update_id,
                       0,
                       (select e.contract_id
                        from update_history_transactions u
                                 join update_history_exercises e on e.update_row_id = u.row_id
                        where u.history_id = scan.history_id
                          and u.migration_id = t.migration_id
                          and u.domain_id = t.domain_id
                          and u.update_id = vote.update_id
                          and e.choice = 'DsoRules_CloseVoteRequest'
                          and e.template_id_module_name = 'Splice.DsoRules'
                          and e.template_id_entity_name = 'DsoRules'
                          and e.consuming = false
                        limit 1),
                       t.vote_action_name,
                       t.vote_requester_name,
                       t.vote_accepted,
                       coalesce(t.entry_data -> 'result' -> 'request' ->> 'trackingCid', ''),
                       (extract(epoch from (t.entry_data -> 'result' ->> 'completedAt')::timestamptz) * 1000000)::bigint,
                       (extract(epoch from t.vote_effective_at::timestamptz) * 1000000)::bigint,
                       t.entry_data -> 'result'
                from scan_txlog_store t
                         cross join lateral (select substring(t.event_id from '^#?(.*):[0-9]+$') as update_id) vote
                where t.store_id = scan.txlog_store_id
                  and t.entry_type = 'vot'
                  and t.entry_data -> 'result' is not null
                on conflict do nothing;
                get diagnostics num_copied = row_count;

                -- Check 5: every vote result has a contract id, the copied ones as well as the ones that were in the
                -- votes store already. With the checks above, all of them are expected to be found in the update
                -- history. A missing one means that one of our assumptions is wrong, and a vote result without a
                -- contract id cannot be served to peers (see HttpScanHandler.encodeVoteResultForBackfilling).
                -- So fail instead of marking the votes store as complete: the copy is rolled back, the txlog stays
                -- the source of the vote results, and the failure is logged as a warning by the handler below.
                -- Uses a prefix of the primary key (store_id, record_time, event_number).
                select count(*)
                into num_without_contract_id
                from votes_store_results v
                where v.store_id = scan.txlog_store_id
                  and v.contract_id is null;
                if num_without_contract_id > 0 then
                    raise exception using errcode = 'VS002',
                        message = format('%s vote results have no contract id', num_without_contract_id);
                end if;

                -- Mark the votes store as complete: it now has every vote result since the start of the network.
                insert
                into key_value_store(key, value, store_id)
                values ('votes_store_backfill_complete_' || scan.txlog_store_id, to_jsonb(true), scan.kv_store_id)
                on conflict on constraint uc_key_value_store do update set value = excluded.value;

                raise info 'votes_store_from_txlog: copied % vote results for txlog store %, the votes store is complete',
                    num_copied, scan.txlog_store_id;
            exception
                -- A failed check of the state of the scan: the migration is not meant for it
                when sqlstate 'VS001' then
                    raise info 'votes_store_from_txlog: skipping txlog store %, its vote results will be backfilled from peers: %',
                        scan.txlog_store_id, sqlerrm;
                -- A failed check of the copied data: not expected on any scan
                when sqlstate 'VS002' then
                    raise warning 'votes_store_from_txlog: the copied data for txlog store % failed a check, the copy is rolled back and its vote results will be backfilled from peers: %',
                        scan.txlog_store_id, sqlerrm;
                -- Any other error. query_canceled (e.g., a statement timeout) is not covered by "others", so it is
                -- listed explicitly.
                when query_canceled or others then
                    raise warning 'votes_store_from_txlog: failed for txlog store %, nothing was copied and its vote results will be backfilled from peers. Cause: % (SQLSTATE %)',
                        scan.txlog_store_id, sqlerrm, sqlstate;
            end;
        end loop;
exception
    -- Errors outside of the steps for one scan, i.e., when listing the scans
    when query_canceled or others then
        raise warning 'votes_store_from_txlog: failed, nothing more is copied. Cause: % (SQLSTATE %)', sqlerrm, sqlstate;
end
$$;
