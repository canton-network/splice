# Progress: party balance gate (issue #7979)

Plan: `PLAN-party-balance-gate.md` (same directory). Branch: `isegall/internal-party-balance`.
Steps 1–4 are committed and the Step 4 integration test passes. Next: Steps 5–8.

## Commits so far (oldest first, on top of `dee813861c`)

| Commit | Content |
|---|---|
| `c40a8e21d0` | Step 1+2: migration `V081__party_balance_monitor.sql` (table `wallet_party_balance_monitor`), added to the `TRUNCATE` list in `SpliceDbTest.scala`; `wallet/store/PartyBalanceMonitorStore.scala` (trait) and `wallet/store/db/DbPartyBalanceMonitorStore.scala` (upsert on `store_id`, takes the wallet `AcsStoreId`). |
| `e1eca2fcc6`, `0f80248f47`, `486503bcca` | Cherry-picks of the operator-only prototype (`d351af27aa 8f0e286328 1ddb743c6c`). Partly superseded by later commits; leftovers removed in Step 5. |
| `505c7adad0` | Step 3: `wallet/treasury/PartyBalanceMonitor.scala` + `PartyBalanceMonitorTest` (9 tests, in-memory fake store, passing). `test-full-class-names-non-integration.log` updated. |
| `d150151e04` | Step 4: wiring + gate in `TreasuryService` + reworked integration test. |
| `708c9f747f`, `2b4d6a949b`, `7b0b732648` | `fixup!` commits for `d150151e04` (integration test fix), `c40a8e21d0` and `505c7adad0` (copyright year 2026 -> 2024, as the `copyright` hook enforces). Squash with `git rebase -i --autosquash`. |

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

## Integration test (Step 4) — passing

`TimeBasedTreasuryIntegrationTest`, case `"stop collecting a party's rewards once its balance was above the limit for too long"`.
aliceValidator config: `maxAllowedPartyBalance = 100`, window 24h. Uses alice (non-operator):
1. app reward for alice is collected while below the limit;
2. tap 200, advance 25h with `advanceTimeAndWaitForRoundAutomation` → expects the warn `rewards will no longer be collected`;
3. with `CollectRewardsAndMergeAmuletsTrigger` paused, new collectable reward + tap 10 (2 amulets); after resuming, expects 1 amulet and the coupon still present;
4. `p2pTransfer` 150 to bob → coupon gets collected.

The 25h jump must use `advanceTimeAndWaitForRoundAutomation`, not plain `advanceTime`: otherwise the next
`advanceRoundsToNextRoundOpening` starts before the round automation caught up and sees two rounds advance instead of one.
The whole suite (4 cases) passes with the low limit of 100. `checkErrors` not run yet (`log/` has stale entries).

Run (needs Canton, simtime):
```
./start-canton.sh -s -p local
direnv exec . sbt -batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.TimeBasedTreasuryIntegrationTest" > it.log 2>&1
grep -E '^\[error\]|Tests:|FAILED' it.log
```

Environment notes (sandbox):
- Memory: sbt `-Xmx16G` (from `.envrc.vars`) + Canton OOMs. Use `ADDITIONAL_JAVA_TOOLS_OPTIONS="-Xms3g -Xmx5g" ./start-canton.sh ...` and `SBT_OPTS="-Xmx6G -Xms2G -Xss6M -XX:+UseG1GC -XX:ReservedCodeCacheSize=512M"`. Run `./stop-canton.sh local` first if a stale tmux `canton` session remains.
- Don't commit while a test runs: the `copyright` pre-commit hook starts an sbt server (`sbt --client headerCreate`), which competes for memory and the sbt lock. Stop it afterwards with `sbt --client shutdown`.
- The `copyright` hook runs `headerCreate` on the whole project, so a commit fails if any other tracked file still has a wrong header (even if unstaged).
- If `/nix` is read-only and direnv is missing, `direnv exec .` / `nix print-dev-env` don't work; source the exported variables of the cached `.direnv/flake-profile` JSON plus `.envrc.vars` instead (and set `USER`, which `build-tools/get-snapshot-version` needs).

## Remaining

- Step 5: remove the trigger-level gate and `OperatorBalanceMonitor` (see plan).
- Step 6: default `maxAllowedPartyBalance = 1000`, fix affected tests, cluster/docs wiring, release notes, config test.
- Steps 7–8: rebase on `origin/main` (check migration number V081 is still free), full test pass, compile all, format, check no `OperatorBalanceMonitor` / `maxAllowedOperatorBalance` / `validator_operator_last_below_balance_limit` leftovers.
- Commits: `git commit -s`, **no Co-Authored-By trailer**, last commit message ends with `[ci]`. Don't push unless asked.
