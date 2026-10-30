-- Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- The contract id of the DsoRules on which the DsoRules_CloseVoteRequest choice was exercised,
-- i.e., the DsoRules at the time the vote was closed.
-- Always set for rows ingested after this migration. Rows that were already there are filled below.
-- The column is deliberately nullable, so that the migration cannot fail.
-- TODO (#8157): Make this column non-null once confirming that there are no null values
alter table votes_store_results
    add column contract_id text;

-- Fill the contract id from the update history of the same scan app (same store name, party and participant as the txlog store,
-- which is the store id of votes_store_results).
-- DsoRules_CloseVoteRequest is always the only root of its transaction, so the update and the choice identify the exercise.
-- Uses the indexes of update_history_transactions (history_id, update_id) and update_history_exercises (update_row_id).
-- On a database that has no votes or no update history yet, this does not do anything.
update votes_store_results v
set contract_id = e.contract_id
from store_descriptors s
         join update_history_descriptors h
              on h.store_name = s.descriptor ->> 'name'
                  and h.party = s.descriptor ->> 'party'
                  and h.participant_id = s.descriptor ->> 'participant'
         join update_history_transactions u
              on u.history_id = h.id
         join update_history_exercises e
              on e.update_row_id = u.row_id
where s.id = v.store_id
  and u.update_id = v.update_id
  and u.domain_id = v.synchronizer_id
  and e.choice = 'DsoRules_CloseVoteRequest'
  and e.template_id_module_name = 'Splice.DsoRules'
  and e.template_id_entity_name = 'DsoRules'
  and e.consuming = false;
