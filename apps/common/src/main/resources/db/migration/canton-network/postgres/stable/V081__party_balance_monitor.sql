-- Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Last time the balance of a wallet party was observed at or below the allowed limit.
create table wallet_party_balance_monitor
(
    -- The wallet store this row belongs to (same id space as user_wallet_acs_store.store_id).
    -- A wallet store is per party, so this identifies the party.
    store_id             int    not null primary key,
    -- Micros since epoch.
    last_below_limit_at  bigint not null
);
