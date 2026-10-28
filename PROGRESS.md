# Progress: party balance gate (issue #7979)

Plan: `PLAN-party-balance-gate.md` (same directory). Branch: `isegall/internal-party-balance`.
Steps 1–4 are committed. Next: run the Step 4 integration test, then Steps 5–8.

## Commits so far (oldest first, on top of `dee813861c`)

| Commit | Content |
|---|---|
| `c40a8e21d0` | Step 1+2: migration `V081__party_balance_monitor.sql` (table `wallet_party_balance_monitor`), added to the `TRUNCATE` list in `SpliceDbTest.scala`; `wallet/store/PartyBalanceMonitorStore.scala` (trait) and `wallet/store/db/DbPartyBalanceMonitorStore.scala` (upsert on `store_id`, takes the wallet `AcsStoreId`). |
| `e1eca2fcc6`, `0f80248f47`, `486503bcca` | Cherry-picks of the operator-only prototype (`d351af27aa 8f0e286328 1ddb743c6c`). Partly superseded by later commits; leftovers removed in Step 5. |
| `505c7adad0` | Step 3: `wallet/treasury/PartyBalanceMonitor.scala` + `PartyBalanceMonitorTest` (9 tests, in-memory fake store, passing). `test-full-class-names-non-integration.log` updated. |
| `d150151e04` | Step 4: wiring + gate in `TreasuryService` + reworked integration test (compiled, **not yet run**). |

## Decisions made along the way (deviations from / refinements of the plan)

- **No `DbPartyBalanceMonitorStoreTest`**: user deemed it overkill and it was deleted. The DB store is covered only indirectly (integration test).
- **PartyBalanceMonitor** (user-requested refinements):
  - No "persisted timestamp in the future" check — clock skew is not a concern.
  - `observe` = `updateLastBelowLimit(balance, now).map(_ => logGateTransition(balance))`.
  - `updateLastBelowLimit`: if above limit and cache is set → no-op; otherwise reads the store, and either uses the stored value (above limit) or writes `now`; result is cached as `newLastBelowLimit`.
  - Writing the timestamp is logged at **debug**; info/warn only on gate transitions (warn when closing, info when reopening). `gateClosed: AtomicBoolean` is kept: it is needed to detect the transition, since the gate closes by time passing, not by a change in `lastBelowLimit`.
- **Store wiring**: instead of passing `DbStorage` + store id through `UserWalletManager`, `UserWalletStore` got `def partyBalanceMonitorStore: PartyBalanceMonitorStore`, implemented in `DbUserWalletStore` (lazy val, using its `acsStoreId`). `UserWalletManager` creates a `PartyBalanceMonitor` for every onboarded party.
- **TreasuryService**: new ctor param `partyBalanceMonitor`. In `getTransferContextAndInputs`, `observeBalanceAndCheckRewardCollection(openRound.payload.round.number)` (unlocked via `getAmuletBalanceWithHoldingFees` + `getLockedAmuletBalance`) runs after listing amulets; if it returns false, all 8 reward getters are skipped (`(BigDecimal(0), Seq.empty)`, and `(0, Set.empty, Seq.empty)` for validator rewards). Merge-only still decided by amulet count.
- **No TreasuryService unit tests exist** (it needs a live ledger connection), so gate coverage is only via the integration test.
- **Config (done early, in Step 4)**: `ValidatorAppBackendConfig.maxAllowedOperatorBalance` replaced by `maxAllowedPartyBalance: BigDecimal = BigDecimal(Long.MaxValue)` and `maxTimeAboveBalanceLimit: NonNegativeFiniteDuration = 24h`. **Default intentionally still `Long.MaxValue`**; Step 6 changes it to 1000 and handles fallout in existing tests, plus cluster/docs wiring and the config parsing test.
- **Step 5 leftovers in place**: `UserWalletService` passes `None` as the old monitor to `UserWalletAutomationService`; `automation/OperatorBalanceMonitor.scala`, the `operatorBalanceMonitorO` param and the gate in `CollectRewardsAndMergeAmuletsTrigger` still exist. Step 5 removes them (trigger file should end identical to `origin/main`).

## Integration test (Step 4) — not yet run

`TimeBasedTreasuryIntegrationTest`, case `"stop collecting a party's rewards once its balance was above the limit for too long"`.
aliceValidator config: `maxAllowedPartyBalance = 100`, window 24h. Uses alice (non-operator):
1. app reward for alice is collected while below the limit;
2. tap 200, advance 25h → expects the warn `rewards will no longer be collected` (inside `assertEventuallyLogsSeq`);
3. with `CollectRewardsAndMergeAmuletsTrigger` paused, new collectable reward + tap 10 (2 amulets); after resuming, expects 1 amulet and the coupon still present;
4. `p2pTransfer` 150 to bob → coupon gets collected.

Possible issues to watch when running: the reopen `info` log / other WARNs needing suppression; whether the coupon from (3) survives until (4) (no rounds are advanced in between, so it should).

Run (needs Canton, simtime):
```
./start-canton.sh -s -p local
direnv exec . sbt -batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.TimeBasedTreasuryIntegrationTest -- -z \"stop collecting a party\"" > it.log 2>&1
grep -E '^\[error\]|Tests:|FAILED' it.log
```
Also run the whole `TimeBasedTreasuryIntegrationTest` suite: the low limit (100) applies to all its test cases (one taps 1100 but doesn't advance 24h).

Memory note: on a 15 GB machine this was OOM-killed (`.envrc.vars` sets sbt `-Xmx16G`, Canton uses `-Xmx8g`). If memory is tight: `ADDITIONAL_JAVA_TOOLS_OPTIONS="-Xms3g -Xmx5g" ./start-canton.sh ...` and `direnv exec . env SBT_OPTS="-Xmx6G -Xms2G -Xss6M -XX:+UseG1GC -XX:ReservedCodeCacheSize=512M" sbt ...`. Run `./stop-canton.sh local` first if a stale tmux `canton` session remains.

## Remaining

- Run/fix the Step 4 integration test (amend into `d150151e04` or add a fixup commit).
- Step 5: remove the trigger-level gate and `OperatorBalanceMonitor` (see plan).
- Step 6: default `maxAllowedPartyBalance = 1000`, fix affected tests, cluster/docs wiring, release notes, config test.
- Steps 7–8: rebase on `origin/main` (check migration number V081 is still free), full test pass, compile all, format, check no `OperatorBalanceMonitor` / `maxAllowedOperatorBalance` / `validator_operator_last_below_balance_limit` leftovers.
- Commits: `git commit -s`, **no Co-Authored-By trailer**, last commit message ends with `[ci]`. Don't push unless asked.
