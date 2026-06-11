-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Tracks the highest OpenMiningRound round number which has been archived
-- as of the max record_time of the ingested verdicts.
alter table app_activity_record_meta
    add column last_archived_round bigint,
    add column last_updated_at timestamptz default now();
