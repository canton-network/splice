-- Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Historical vote results, i.e., the result of exercised DsoRules_CloseVoteRequest choices.
create table votes_store_results
(
    store_id            int     not null,

    -- Data identifying the transaction tree in which the exercise choice was executed.
    migration_id        int     not null,
    record_time         bigint  not null,
    domain_id           text    not null,
    update_id           text    not null,

    -- Event number of the exercise choice within the transaction tree.
    -- To make sure this field is identical across SVs, the event number
    -- is assigned by in-order traversing the DSO-visible part of the transaction tree.
    event_number        int     not null,

    -- Data extracted from the choice result
    vote_action_name    text    not null,
    vote_requester_name text    not null,
    vote_accepted       boolean not null,
    vote_completed_at   text,
    vote_effective_at   text,

    -- Full result of the exercise choice, encoded in protobuf-json format.
    choice_result       jsonb   not null,

    constraint votes_store_id_fkey foreign key (store_id) references store_descriptors(id)

    -- TODO(#6255): the following constraint is only for testing the implementation.
    -- It should be removed before merging to main, or at the latest before starting the pruning of update history.
    constraint update_history_fkey foreign key update_history_exercises(history_id, migration_id, record_time, update_id)
        references update_history_exercises(store_id, migration_id, record_time, update_id)
);

-- Index for returning paginated API responses for replication (sorted by event record time)
create index votes_store_sid_rt_en on votes_store_results (store_id, record_time desc, event_number desc);

-- Index for returning paginated API responses for the UI (sorted by vote effective date)
create index votes_store_sid_effat_rt_en
    on votes_store_results (store_id, coalesce(vote_effective_at, vote_completed_at) desc, record_time desc, event_number desc);

-- Populate the store by copying votes from the deprecated TxLog store.
insert into votes_store_results (
    store_id,
    migration_id,
    record_time,
    domain_id,
    update_id,
    event_number,
    vote_action_name,
    vote_requester_name,
    vote_accepted,
    vote_completed_at,
    vote_effective_at,
    choice_result
)
select
    store_id,
    migration_id,
    record_time,
    domain_id,
    -- The event_id in the txlog table looks like '[#]<update_id>:<event_number>'.
    -- The event_number will always be 0 in practice, as DsoRules_CloseVoteRequest is the root
    -- event of the transaction tree created by CloseVoteRequestTrigger.
    substring(
        ltrim(event_id, '#')
        from 1 for position(':' in ltrim(event_id, '#')) - 1
    ) as update_id,
    cast(substring(event_id from position(':' in event_id) + 1) as int) as event_number,
    vote_action_name,
    vote_requester_name,
    vote_accepted,
    entry_data -> 'result' ->> 'completedAt' as vote_completed_at,
    vote_effective_at,
    entry_data -> 'result' as choice_result
from scan_txlog_store
where entry_type = 'vot';
