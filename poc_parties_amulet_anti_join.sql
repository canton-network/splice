-- POC for the anti-join between owners to ignore and amulet

-- dso_acs_stores 1.5M rows in which 500k Amulets including 200k unique owners against a table with 100k owners to ignore
-- steps:
-- 1. insert 500k amulets with 200k unique owners
-- 2. Add more unrelated data to the table - 1M rows
-- 3. create 100k parties to ignore in table test_owners
-- 4. Anti-join without index => using parallel scans
-- 5. Anti-join with index => using parallel indexing

-- 1. insert 500k amulets with 200k unique owners
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, reward_amount, package_name, amulet_round_of_expiry
)
SELECT
    5,
    0,
    'contract-' || lpad(row_num::text, 20, '0'),
    '56ed13e658da77aad568b59c8fb8cca890f8bb26d473a43342340bc1306d547f',
    'Splice.Amulet:Amulet',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'owner', 'user_' || lpad((row_num % 200000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'amount', jsonb_build_object(
                    'createdAt', jsonb_build_object('number', 0),
                    'ratePerRound', jsonb_build_object('rate', '123.0000000000'),
                    'initialAmount', (50 + random() * 200)::numeric(28,10)::text
                      )
    ),
    decode('0A03322E3112C1050A45002A10285920CE60630D645A6A57F2552811BB3E', 'hex'),
    row_num,
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0,
    50 + random() * 200,
    'splice-amulet',
    row_num % 789
FROM generate_series(1, 500000) AS row_num;

-- 2. Add more unrelated data to the table - 1M rows overall
-- SvRewardCoupon: 250k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, reward_amount
)
SELECT
    5, 0,
    'sv-reward-' || md5(row_num::text || 'x1'),
    'a1b2c3d4e5f6' || md5(row_num::text),
    'Splice.Amulet:SvRewardCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
            'round', (row_num * 11 + 3) % 3000,
            'weight', 1 + (row_num % 20),
            'beneficiary', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text)
    ),
    decode(lpad(md5(row_num::text || 'b1'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 120)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 3)::int, 'splice-amulet',
    (row_num * 11 + 3) % 3000,
    'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
    1 + (row_num % 20)
FROM generate_series(1, 250000) AS row_num;

-- AppRewardCoupon: 250k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, reward_amount, app_reward_is_featured
)
SELECT
    5, 0,
    'app-rew-' || md5(row_num::text || 'x2'),
    'f6e5d4c3b2a1' || md5((row_num + 999)::text),
    'Splice.Amulet:AppRewardCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'provider', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'amount', (0.001 + random() * 75)::numeric(28,10)::text,
            'round', (row_num * 17 + 5) % 4000,
            'featured', (row_num % 7 = 0),
            'appId', 'app-' || md5((row_num % 200)::text),
            'activityRef', md5(row_num::text || 'activity')
    ),
    decode(lpad(md5(row_num::text || 'b2'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 90)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 4)::int, 'splice-amulet',
    (row_num * 17 + 5) % 4000,
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
    0.001 + random() * 75,
    (row_num % 7 = 0)
FROM generate_series(1, 250000) AS row_num;

-- ValidatorRewardCoupon: 200k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, reward_amount
)
SELECT
    5, 0,
    'val-rew-' || md5(row_num::text || 'x3'),
    '1122334455667788' || md5((row_num + 5000)::text),
    'Splice.Amulet:ValidatorRewardCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'user', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'amount', (0.05 + random() * 25)::numeric(28,10)::text,
            'round', (row_num * 13 + 9) % 3500
    ),
    decode(lpad(md5(row_num::text || 'b3'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 75)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 2)::int, 'splice-amulet',
    (row_num * 13 + 9) % 3500,
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
    0.05 + random() * 25
FROM generate_series(1, 200000) AS row_num;

-- ValidatorFaucetCoupon: 100k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, validator
)
SELECT
    5, 0,
    'val-faucet-' || md5(row_num::text || 'x4'),
    'aabbccdd11223344' || md5((row_num + 8000)::text),
    'Splice.Amulet:ValidatorFaucetCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'validator', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'round', (row_num * 19 + 2) % 2500
    ),
    decode(lpad(md5(row_num::text || 'b4'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 60)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 3)::int, 'splice-amulet',
    (row_num * 19 + 2) % 2500,
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text)
FROM generate_series(1, 100000) AS row_num;

-- OpenMiningRound: 50k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    mining_round
)
SELECT
    5, 0,
    'open-round-' || md5(row_num::text || 'x5'),
    '99887766554433' || md5((row_num + 3000)::text),
    'Splice.Round:OpenMiningRound',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'round', row_num,
            'opensAt', row_num * (400 + (random() * 200)::int),
            'targetClosesAt', (row_num + 1) * (400 + (random() * 200)::int),
            'amuletPrice', (0.001 + random() * 0.5)::numeric(28,10)::text,
            'transferConfigUsd', jsonb_build_object(
                    'createFee', (0.001 + random() * 0.01)::numeric(28,10)::text,
                    'holdingFee', (0.0001 + random() * 0.001)::numeric(28,10)::text,
                    'transferFee', (0.001 + random() * 0.05)::numeric(28,10)::text
                                 )
    ),
    decode(lpad(md5(row_num::text || 'b5'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 180)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 2)::int, 'splice-amulet',
    row_num
FROM generate_series(1, 50000) AS row_num;

-- ClosedMiningRound: 50k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    mining_round
)
SELECT
    5, 0,
    'closed-round-' || md5(row_num::text || 'x6'),
    '11223344aabbccdd' || md5((row_num + 7000)::text),
    'Splice.Round:ClosedMiningRound',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'round', row_num,
            'closedAt', row_num * 500 + (random() * 100)::int,
            'numMintedAmulets', (100 + random() * 1000)::numeric(28,10)::text,
            'totalFees', (1 + random() * 50)::numeric(28,10)::text
    ),
    decode(lpad(md5(row_num::text || 'b6'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 180)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 2)::int, 'splice-amulet',
    row_num
FROM generate_series(1, 50000) AS row_num;

-- ValidatorLicense: 50k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    validator
)
SELECT
    5, 0,
    'val-license-' || md5(row_num::text || 'x7'),
    'deadbeef12345678' || md5((row_num + 2000)::text),
    'Splice.ValidatorLicense:ValidatorLicense',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'validator', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'sponsor', 'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
            'faucetState', jsonb_build_object(
                    'firstCollectedInRound', (row_num * 3) % 5000,
                    'numCouponsMissed', (random() * 10)::int,
                    'lastCollectedInRound', (row_num * 3) % 5000 + (random() * 200)::int
                           )
    ),
    decode(lpad(md5(row_num::text || 'b7'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 200)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 3)::int, 'splice-amulet',
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text)
FROM generate_series(1, 50000) AS row_num;

-- Splice.DSO.SvState:SvNodeState: 50 rows (one per SV node)
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name
)
SELECT
    5, 0,
    'sv-node-state-' || lpad(row_num::text, 10, '0'),
    'aabb11223344' || md5('svnodestate' || row_num::text),
    'Splice.DSO.SvState:SvNodeState',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad(row_num::text, 3, '0') || '::1220' || md5('sv_' || row_num::text),
            'svName', 'Super Validator ' || row_num,
            'state', jsonb_build_object(
                    'synchronizerNodes', jsonb_build_object(
                    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
                    jsonb_build_object(
                            'sequencer', 'https://sequencer-sv' || row_num || '.example.com',
                            'mediator', 'https://mediator-sv' || row_num || '.example.com',
                            'scan', 'https://scan-sv' || row_num || '.example.com'
                    )
                                         ),
                    'participantId', 'participant-sv' || row_num || '::1220' || md5('participant_sv_' || row_num::text)
                     )
    ),
    decode(lpad(md5(row_num::text || 'svns'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 30)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-dso-governance'
FROM generate_series(1, 50) AS row_num;

-- Splice.DSO.SvState:SvRewardState: 50 rows (one per SV)
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name
)
SELECT
    5, 0,
    'sv-reward-state-' || lpad(row_num::text, 10, '0'),
    'ccdd55667788' || md5('svrewardstate' || row_num::text),
    'Splice.DSO.SvState:SvRewardState',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad(row_num::text, 3, '0') || '::1220' || md5('sv_' || row_num::text),
            'svName', 'Super Validator ' || row_num,
            'state', jsonb_build_object(
                    'numRoundsMissed', (random() * 100)::int,
                    'numRoundsCollected', (random() * 5000)::int,
                    'lastCollectedInRound', 4000 + (random() * 1000)::int,
                    'collectedWeight', 1 + (row_num % 30)
                     )
    ),
    decode(lpad(md5(row_num::text || 'svrs'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 30)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-dso-governance'
FROM generate_series(1, 50) AS row_num;

-- Splice.DSO.SvState:SvStatusReport: 5000 rows (many reports per SV over time)
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name
)
SELECT
    5, 0,
    'sv-status-report-' || lpad(row_num::text, 10, '0'),
    'eeff99001122' || md5('svstatusreport' || row_num::text),
    'Splice.DSO.SvState:SvStatusReport',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
            'round', row_num % 5000,
            'status', jsonb_build_object(
                    'openMiningRoundNumber', row_num % 5000,
                    'latestOpenMiningRound', row_num % 5000,
                    'isSynchronizerNodeOnline', (row_num % 20 != 0),
                    'participantSynchronized', (row_num % 50 != 0),
                    'cometBftHeight', 100000 + row_num * 10
                      )
    ),
    decode(lpad(md5(row_num::text || 'svsr'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 60)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-dso-governance'
FROM generate_series(1, 50000) AS row_num;

-- Splice.Amulet:LockedAmulet: 200k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    amulet_round_of_expiry
)
SELECT
    5, 0,
    'locked-amulet-' || lpad(row_num::text, 20, '0'),
    '56ed13e658da77aad568b59c8fb8cca890f8bb26d473a43342340bc1306d547f',
    'Splice.Amulet:LockedAmulet',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'amulet', jsonb_build_object(
                    'owner', 'user_' || lpad((row_num % 200000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
                    'amount', jsonb_build_object(
                            'createdAt', jsonb_build_object('number', row_num % 5000),
                            'ratePerRound', jsonb_build_object('rate', '123.0000000000'),
                            'initialAmount', (10 + random() * 500)::numeric(28,10)::text
                              )
                      ),
            'lock', jsonb_build_object(
                    'holders', jsonb_build_array(
                    'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text)
                               ),
                    'expiresAt', (1748800000000000 + (row_num::bigint * 600000000))::text
                    ),
            'timelockExpiresAt', row_num % 789 + 100
    ),
    decode('0A03322E3112C1050A45002A10285920CE60630D645A6A57F2552811BB3E', 'hex'),
    row_num,
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-amulet',
    row_num % 789
FROM generate_series(1, 200000) AS row_num;




-- 3. create 100k parties to ignore in table test_owners
CREATE TABLE test_owners (
                             owner_id int PRIMARY KEY,
                             party_id text NOT NULL,
                             created_at timestamp NOT NULL DEFAULT now(),
                             metadata jsonb
);

INSERT INTO test_owners (owner_id, party_id, created_at, metadata)
SELECT
    n,
    'user_' || lpad(n::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || n::text),
    now() - (random() * interval '30 days'),
    jsonb_build_object(
            'display_name', 'User ' || n,
            'region', (ARRAY['US', 'EU', 'APAC', 'LATAM'])[1 + (n % 4)],
            'tier', (ARRAY['basic', 'premium', 'enterprise'])[1 + (n % 3)]
    )
FROM generate_series(0, 99999) AS n;

-- 4. Anti-join without index
DROP INDEX idx_dso_acs_pn_tid_croe_owner;

EXPLAIN ANALYZE
SELECT *
FROM dso_acs_store d
WHERE d.store_id = 5
  AND d.migration_id = 0
  AND d.package_name = 'splice-amulet'
  AND d.template_id_qualified_name = 'Splice.Amulet:Amulet'
  AND d.amulet_round_of_expiry < 900
  AND (create_arguments->>'owner') is not null
  AND NOT EXISTS (
    SELECT 1
    FROM test_owners t
    WHERE t.party_id = d.create_arguments->>'owner'
);
-- Result:
----------
-- Gather  (cost=4591.54..160392.85 rows=97806 width=1587) (actual time=64.642..2368.575 rows=470003 loops=1)
--   Workers Planned: 2
--   Workers Launched: 2
--   ->  Parallel Hash Anti Join  (cost=3591.54..149612.25 rows=40752 width=1587) (actual time=45.117..1739.545 rows=156668 loops=3)
--         Hash Cond: ((d.create_arguments ->> 'owner'::text) = t.party_id)
--         ->  Parallel Seq Scan on dso_acs_store d  (cost=0.00..145297.35 rows=81505 width=1587) (actual time=0.115..1499.276 rows=166667 loops=3)
--               Filter: ((amulet_round_of_expiry < 900) AND ((create_arguments ->> 'owner'::text) IS NOT NULL) AND (store_id = 5) AND (migration_id = 0) AND (package_name = 'splice-amulet'::text) AND (template_id_qualified_name = 'Splice.Amulet:Amulet'::text))
--               Rows Removed by Filter: 316677
--         ->  Parallel Hash  (cost=2856.24..2856.24 rows=58824 width=63) (actual time=37.535..37.541 rows=33333 loops=3)
--               Buckets: 131072  Batches: 1  Memory Usage: 10464kB
--               ->  Parallel Seq Scan on test_owners t  (cost=0.00..2856.24 rows=58824 width=63) (actual time=17.817..23.058 rows=33333 loops=3)
-- Planning Time: 2.526 ms
-- JIT:
--   Functions: 33
-- "  Options: Inlining false, Optimization false, Expressions true, Deforming true"
-- "  Timing: Generation 5.405 ms (Deform 2.918 ms), Inlining 0.000 ms, Optimization 2.957 ms, Emission 50.635 ms, Total 58.997 ms"
-- Execution Time: 2430.153 ms

-- 5. create index and redo anti-join in point 4.

CREATE INDEX idx_dso_acs_pn_tid_croe_owner
    ON dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, amulet_round_of_expiry, (create_arguments->>'owner'))
    WHERE amulet_round_of_expiry IS NOT NULL AND (create_arguments->>'owner') IS NOT NULL;

CREATE INDEX idx_test_owners_party_id ON test_owners (party_id);
-- Result:
----------
-- Gather  (cost=3602.14..83776.09 rows=99480 width=1587) (actual time=22.513..955.847 rows=470003 loops=1)
--   Workers Planned: 2
--   Workers Launched: 2
--   ->  Parallel Hash Anti Join  (cost=2602.14..72828.09 rows=41450 width=1587) (actual time=12.587..763.415 rows=156668 loops=3)
--         Hash Cond: ((d.create_arguments ->> 'owner'::text) = t.party_id)
--         ->  Parallel Index Scan using dso_acs_store_sid_pn_tid_croe on dso_acs_store d  (cost=0.42..69490.63 rows=82900 width=1587) (actual time=0.108..568.973 rows=166667 loops=3)
--               Index Cond: ((store_id = 5) AND (migration_id = 0) AND (package_name = 'splice-amulet'::text) AND (template_id_qualified_name = 'Splice.Amulet:Amulet'::text) AND (amulet_round_of_expiry < 900))
--               Filter: ((create_arguments ->> 'owner'::text) IS NOT NULL)
--         ->  Parallel Hash  (cost=2080.88..2080.88 rows=41667 width=63) (actual time=11.962..11.963 rows=33333 loops=3)
--               Buckets: 131072  Batches: 1  Memory Usage: 10464kB
--               ->  Parallel Index Only Scan using idx_test_owners_party_id on test_owners t  (cost=0.42..2080.88 rows=41667 width=63) (actual time=0.040..4.604 rows=33333 loops=3)
--                     Heap Fetches: 0
-- Planning Time: 0.436 ms
-- Execution Time: 990.116 ms

-- Comments:
------------
-- Somehow it prefers dso_acs_store_sid_pn_tid_croe over idx_dso_acs_pn_tid_croe_owner
-- Note: creating such an index might be counter-productive because Transfer instructions, Locked Amulet and Amulet
-- each have a different way of structuring informees

-- 6. helpers
select template_id_qualified_name, count(*) from dso_acs_store group by template_id_qualified_name

    SET random_page_cost = 4.0;
