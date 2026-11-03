-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

create table interned_strings
(
    id    bigint generated always as identity primary key,
    value text not null unique
);
