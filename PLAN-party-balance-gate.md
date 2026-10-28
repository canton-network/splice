# Plan: stop reward collection for parties with a persistently high balance (issue #7979)

Branch: `isegall/internal-party-balance` (already created from `origin/main`).
Issue: https://github.com/hyperledger-labs/splice/issues/7979 ("Prevent high CC balance on validator admin party").

Follow `AGENTS.md` (DCO sign-off with `git commit -s`, no Co-Authored-By trailer, `[ci]` in the last commit message on branches, scalafmt, Scala partial-method ban, etc.).

## Goal

If a wallet-onboarded (internal) party's balance (unlocked + locked amulets) has been continuously above
`maxAllowedPartyBalance` for longer than `maxTimeAboveBalanceLimit`, the treasury service must stop using
all reward/coupon contracts as transfer inputs for that party. Merging amulets must keep working.

Decisions already made (do not revisit):
- Applies to every onboarded party, not only the validator operator.
- Gate is applied inside `TreasuryService` by filtering out reward inputs;
- Observation of the balance happens in `TreasuryService.getTransferContextAndInputs`.
- Persistence is a new DB table (not the key-value store), keyed per party.
- New store lives in the wallet app's store package (`apps/wallet/.../wallet/store/`).
- Default `maxAllowedPartyBalance = 100000`.
- The 24h window is configurable, default 24h.
- Out of scope: the "after 4 weeks assert on startup that the config is <= some ceiling" item from the issue.

## Starting point: prior work to cherry-pick

Three commits exist locally (branch `isegall/operator-balance-monitor`, not on origin) implementing an
operator-only version. Cherry-pick them in order, then rework:

```
git cherry-pick d351af27aa 8f0e286328 1ddb743c6c
```

- `d351af27aa` adds `OperatorBalanceMonitor` (KV-store backed), config `maxAllowedOperatorBalance`, wiring through
  `ValidatorApp` -> `UserWalletManager` -> `UserWalletService` -> `UserWalletAutomationService` ->
  `CollectRewardsAndMergeAmuletsTrigger`, and `OperatorBalanceMonitorTest`. It also touches
  `test-full-class-names-non-integration.log` (a generated list of test classes; keep it consistent with the final
  test class names).
- `8f0e286328` gates `CollectRewardsAndMergeAmuletsTrigger` (to be removed below).
- `1ddb743c6c` adds an integration test case to `TimeBasedTreasuryIntegrationTest` (to be reworked below).

If the cherry-picks conflict, resolve them; alternatively `git show <sha>` and re-apply by hand. Squashing the
final result into fewer commits is fine. If `git fetch origin` over SSH fails, fetch over
`https://github.com/hyperledger-labs/splice`.

Files of reference (paths under `apps/wallet/src/main/scala/org/lfdecentralizedtrust/splice/wallet/` unless noted):
- `automation/OperatorBalanceMonitor.scala` (from the cherry-pick; becomes `PartyBalanceMonitor`)
- `treasury/TreasuryService.scala` (`getTransferContextAndInputs` ~line 922, `constructTransferInputs` ~line 1080)
- `UserWalletService.scala` (constructs `TreasuryService`, ~line 65, the only call site)
- `UserWalletManager.scala`, `automation/UserWalletAutomationService.scala`, `automation/CollectRewardsAndMergeAmuletsTrigger.scala`
- `store/db/DbUserWalletStore.scala` (style reference for a Slick/`DbStorage` store)
- `apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/ValidatorApp.scala` and `config/ValidatorAppConfig.scala`

## Working rule: tests land with the code

Every step below ends with its own tests, run before moving on, and a commit (`git commit -s`) for that step.
Do not defer tests to the end (Step 1, the migration, has no test of its own; Step 2's store test exercises it). Per-step run commands: `direnv exec . sbt -batch "<project>/testOnly <FQ suite>" > <file> 2>&1`,
then grep `^\[error\]` and `Tests:`.

## Step 1: DB migration

Add `apps/common/src/main/resources/db/migration/canton-network/postgres/stable/V081__party_balance_monitor.sql`
(V080 is currently the latest; check for a newer number after rebasing on `origin/main`). Include the copyright
header used by the other migrations (`-- Copyright (c) 2026 Digital Asset ...` + `-- SPDX-License-Identifier: Apache-2.0`).
Read `apps/common/src/main/resources/db/migration/README.md` first.

```sql
-- Last time the balance of a wallet party was observed at or below the allowed limit.
create table wallet_party_balance_monitor
(
    -- The wallet store this row belongs to (same id space as user_wallet_acs_store.store_id).
    -- A wallet store is per party, so this identifies the party.
    store_id             int    not null primary key,
    -- Micros since epoch.
    last_below_limit_at  bigint not null
);
```

Also search the repo for any schema lint, schema documentation, or table lists (e.g. domain-migration
dump/reset/cleanup code, "all tables" checks) that must include the new table, and update them.

## Step 2: Store (wallet app, `wallet/store/`)

- `store/PartyBalanceMonitorStore.scala`: trait with
  - `getLastBelowLimit()(implicit tc: TraceContext): Future[Option[CantonTimestamp]]`
  - `setLastBelowLimit(timestamp: CantonTimestamp)(implicit tc: TraceContext): Future[Unit]`
  - An instance is bound to one wallet store (one party); the party is not a parameter.
- `store/db/DbPartyBalanceMonitorStore.scala`: `DbStorage`-based implementation. `setLastBelowLimit` is an upsert
  (`insert ... on conflict (store_id) do update set last_below_limit_at = ...`). Follow the Slick `sql"..."`
  style of `DbUserWalletStore`, including how `store_id` is obtained.
- No in-memory implementation; tests use the DB-backed store like other store tests.
- New files need the copyright header used elsewhere in the repo (see the `project-conventions` skill).

Tests for this step: `DbPartyBalanceMonitorStoreTest` (wallet store tests dir, modeled on an existing Db store test
using the shared Postgres test setup): get on empty returns None; set then get; overwrite with a newer and an older
value; two store ids (i.e., two parties) are isolated. Run it before moving on.

## Step 3: Rework the monitor: `OperatorBalanceMonitor` -> `PartyBalanceMonitor`

Rename the class and file (`automation/` -> consider moving to `treasury/` since the treasury is its only user; keep
the package consistent with imports). Changes relative to the cherry-picked version:
- Constructor takes `PartyBalanceMonitorStore` (already bound to the party), `maxAllowedBalance: BigDecimal`,
  `maxTimeAboveLimit: java.time.Duration` (converted from config), `clock`, `loggerFactory`, instead of
  `KeyValueStore`.
- Replace `keyValueStore.readValueAndLogOnDecodingFailure/setValue` with the store's get/set.
- Remove the `lastBelowLimitKey` and `maxTimeAboveLimit` constants from the companion object (window now comes from config).
- Keep the existing semantics exactly:
  - in-memory cache of the last-below-limit timestamp (loaded lazily on the first `observe`)
  - balance <= limit: persist `now` (write on each observation is acceptable as in the original, but prefer only
    writing when the cached value is older than a threshold if that is trivial; otherwise keep as is)
  - first observation above the limit after startup: continue from the persisted value; if none, or the persisted
    value is in the future, start counting from `now` (never act on a balance not observed for the full duration)
  - debug log on balance change, info log on persist
  - `hasBeenAboveLimitFor(duration)` and `shouldCollectRewards = !hasBeenAboveLimitFor(maxTimeAboveLimit)`
- Log a single `warn` when the gate transitions from open to closed (and an `info` when it reopens), not on every
  call, to avoid log spam and `checkErrors` noise.

Tests for this step: port `OperatorBalanceMonitorTest` (from the cherry-pick) to `PartyBalanceMonitorTest`, backed by
the DB store (or a trivial in-test fake of `PartyBalanceMonitorStore` if the DB setup is too heavy for a unit test).
Cover: persistence across a new monitor instance; gate closes only after the configured duration (simulated clock);
the configured window, not a constant, is respected (two monitors with different windows); future persisted
timestamp ignored; no persisted value and balance above limit starts counting from now; balance dropping back
below the limit reopens the gate; two parties have independent state; the open-to-closed warn is logged exactly once
(wrap in `loggerFactory.assertLogs`). Update `test-full-class-names-non-integration.log` to the final test class
names if the repo expects it (check how `d351af27aa` changed it). Run it before moving on.

## Step 4: Wire it through `TreasuryService`

- `UserWalletManager`: remove the `Option.when(endUserParty == store.walletKey.validatorParty)(...)` monitor
  creation. For every onboarded party, create a `PartyBalanceMonitor` (using the party's logger factory) and pass it
  to `UserWalletService` -> `TreasuryService`. `UserWalletManager` needs the `DbStorage` (or the store) instead of
  `operatorBalanceStore: KeyValueStore`, plus `maxAllowedPartyBalance` and `maxTimeAboveBalanceLimit`.
- `ValidatorApp.scala` (~line 741): pass the storage and the two config values instead of `internalStore` and
  `config.maxAllowedOperatorBalance`.
- `UserWalletService`: replace the `operatorBalanceMonitorO: Option[OperatorBalanceMonitor]` parameter with a
  (non-optional) `PartyBalanceMonitor` passed into `new TreasuryService(...)`. Remove it from the
  `UserWalletAutomationService` construction.
- `TreasuryService`: add the monitor as a constructor parameter. In `getTransferContextAndInputs`:
  1. Observe the balance first: unlocked balance (reuse existing data; `userStore.getAmuletBalanceWithHoldingFees(round)`
     with the latest open round number is what the cherry-picked trigger used) plus
     `userStore.getLockedAmuletBalance()`, then `monitor.observe(total)`.
  2. If `monitor.shouldCollectRewards` is false, do NOT fetch reward inputs: use `(BigDecimal(0), Seq.empty)` (matching
     each getter's tuple shape) for all of: validator rewards, validator faucets, validator liveness activity records,
     app rewards, reward coupon V2, SV reward coupons, unclaimed activity records, development fund coupons.
     Amulet inputs are unchanged.
  3. Merge-only batches: with all reward totals 0, `shouldMergeOnlyTransferRun` still decides based on the number of
     amulets, so merging continues. Verify this holds and that the empty-rewards path produces a valid transfer.
  4. Make sure the observation failing (e.g. no open round / DB error) does not break amulet operations in a way
     that wasn't already possible; prefer failing the batch the same way other store/scan lookups there do.

Tests for this step:
- Find the existing `TreasuryService` unit tests (`grep -rl TreasuryService apps/wallet/src/test apps/app/src/test`)
  and keep them passing with the new constructor parameter (pass a monitor with a `Long.MaxValue` limit so the gate stays open).
- Add unit-level coverage of the gate if `TreasuryService` is unit-testable with a fake store/scan: with the gate
  closed, none of the eight reward getters is used and amulet inputs are still selected; with it open, rewards are
  included; a merge-only batch still runs with the gate closed when there are enough amulets.
- Add the integration test (below) in this step, since it is the first point where the end-to-end behavior exists.
  Rework the case added in `1ddb743c6c` in `TimeBasedTreasuryIntegrationTest` to use a non-operator onboarded party
  with app reward coupons and a config override for `maxAllowedPartyBalance` (see `ConfigTransforms.scala`; the
  config option itself is added in Step 6, so either do Step 6 first or add the config field here and the
  cluster/docs wiring in Step 6). Assert (with `actAndCheck`/`eventually`, `perTestCaseName` for names): rewards are
  collected while below the limit; after the balance is above the limit for longer than the window (advance
  simulated time), rewards are no longer collected but merging still happens; once the balance drops below the
  limit, collection resumes. Wrap the expected warn in `loggerFactory.assertLogs`.
  Integration tests need Canton: `./start-canton.sh -s -p local` (simtime), then `direnv exec . sbt -batch
  "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.TimeBasedTreasuryIntegrationTest -- -z \"<name>\""`.
  Restart Canton if `nix/canton-sources.json` changed. Redirect sbt output to a file and grep `^\[error\]` / `Tests:`.

## Step 5: Revert the trigger-level gate

In `CollectRewardsAndMergeAmuletsTrigger.scala`, remove `operatorBalanceMonitorO`, `observeOperatorBalance`, the
`shouldCollectRewards` branch and the warn message, and restore `collectRewardsAndMergeAmulets` to call
`doCollectRewardsAndMergeAmulets` logic as on `origin/main` (compare with `git diff origin/main -- <file>` to ensure
the file ends up identical to main apart from intentional changes; ideally no diff). Remove the monitor parameter
from its constructor call in `UserWalletAutomationService.scala` and from `UserWalletAutomationService`'s own
constructor.

Tests for this step: run the existing tests of `CollectRewardsAndMergeAmuletsTrigger` and the wallet automation
(`grep -rl CollectRewardsAndMerge apps/*/src/test`), e.g. `WalletRewardsTimeBasedIntegrationTest` and
`TimeBasedTreasuryIntegrationTest`, to confirm behavior is unchanged and the Step 4 integration test still passes
once the trigger gate is gone.

## Step 6: Config

In `ValidatorAppConfig.scala` (`ValidatorAppBackendConfig`), replace `maxAllowedOperatorBalance` with:
- `maxAllowedPartyBalance: BigDecimal = BigDecimal(100000)`: max balance (unlocked + locked) an onboarded
  party may hold; above it for longer than `maxTimeAboveBalanceLimit`, the party's rewards are no longer
  collected by the treasury. The default is 100,000, so the gate is active
  out of the box. Existing integration tests that run in simulated time and hold more than 100,000 for over 24h
  (simulated) would now stop collecting rewards: find such suites (e.g. those that tap large amounts and advance time
  by days; run the time-based wallet/treasury/reward suites) and override `maxAllowedPartyBalance` to a large value
  in their config transform where they break (see `ConfigTransforms.scala`), keeping the new integration test on the
  low default or a dedicated override.
- `maxTimeAboveBalanceLimit: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(24)`.
Follow AGENTS.md config conventions. Then check whether the options need wiring in `cluster/images/*/app.conf`,
`cluster/compose/`, `cluster/helm/`, `cluster/pulumi/`, `docs/src/`; if cluster files change run
`make update-expected`. Add a release note entry if the repo keeps one (look for `docs/src/release_notes.rst`).

## Step 7: Final test pass

All tests were written in the steps above. Here, rerun them all together (store test, monitor test, treasury tests,
config test, integration tests) after rebasing on the latest `origin/main`, and fix anything broken by the rebase.

## Step 8: Verify

- `direnv exec . sbt -batch "apps-wallet/Test/compile" "apps-validator/Test/compile" "apps-app/Test/compile"` (fatal warnings,
  unused privates/params are errors).
- Run the new/changed unit tests with `testOnly`; run the integration test as above. Never run bare `sbt test`.
- `sbt "scalafmtOnly <files...>"`, then `sbt formatFix` if needed (note scalafix may rewrite files).
- Check `git diff origin/main --stat` contains no leftovers of the operator-only version
  (`OperatorBalanceMonitor`, `maxAllowedOperatorBalance`, `validator_operator_last_below_balance_limit`).

## Commits / PR

`git commit -s`, no Co-Authored-By trailer; end the final commit message with `[ci]`. Reference `#7979` in the title
(`... (#7979)`). Do not push or open a PR unless asked.
