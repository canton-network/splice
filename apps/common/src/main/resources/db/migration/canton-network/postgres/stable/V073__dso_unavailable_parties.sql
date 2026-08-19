-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Table storing parties that the automation temporarily ignore, because of unresponsiveness or vetting errors.
-- Entries are ignored for updated_at + ignore_duration.
create table dso_unavailable_parties
(
    -- the ID of the party that is unavailable
    party           text   not null,
    -- the time when the party was marked as unavailable, used for capped exponential backoff
    updated_at      bigint not null,
    -- the duration (microseconds) to ignore the entry, used for capped exponential backoff
    ignore_duration bigint not null,
    -- the store ID when the party is added, used for resets
    store_id        bigint not null,
    -- the metadata fields reserved for diagnostic/extra information
    metadata        jsonb,
    primary key (party)
);

-- Index for the expiry check per party
create index dso_unavailable_parties_pid_exp
    on dso_unavailable_parties (party, (updated_at + ignore_duration));

-- Index for the efficient store cleanup
create index dso_unavailable_parties_sid on dso_unavailable_parties (store_id);
