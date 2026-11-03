# 11144 - TrafficBasedRewardsDryRunTimeBasedIntegrationTest: round 4 totals read before its parallel reward computation committed (run 41492215719)

New (family AD, provisional letter). Test-side race on main 5fdbcdce19 ("[ci] Bump nixpkgs (#8181)"), job simtime
(3), canton 3.7.1. The test pauses sv1Scan verdict ingestion for rounds 3 to 10; when ingestion resumes, sv1Scan's
RewardComputationTrigger (a PollingParallelTaskExecutionTrigger) gets rounds 3 to 10 as one batch at
17:57:27.407 and computes them in parallel. Round 10 commits at .433, rounds 4 and 5 only at .510. The test waits
(eventually) only for round 10 to be Ok, then reads rounds 0 to 10 once without retry; it read round 4 at .495 to
.497 and got `Undetermined` (no totals row yet, round beyond the ingested boundary means "retry"). Not a product
bug: Undetermined is the documented retry answer. 1 of 12 tests failed; no checkErrors finding involved.

- Run: https://github.com/canton-network/splice/actions/runs/41492215719, main 5fdbcdce19 ("[ci] Bump nixpkgs (#8181)"), job 125438719877 `ci / scala_test_sim_time / simtime (3)`, attempt 1.
- Runtime canton: 3.7.1.
- Component: test (`TrafficBasedRewardsTimeBasedIntegrationTest.scala`, shared base of the DryRun variant).

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11144/job.log | grep -a -A2 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -3
```
```
[info] - CIP-104 reward accounting pipeline works *** FAILED ***
[info]   The partial function passed as the second parameter to inside was not defined at the value passed as the first parameter to inside, which was: RewardAccountingActivityTotalsUndetermined(RewardAccountingActivityTotalsUndetermined(Undetermined)) Round 4 should have totals (TrafficBasedRewardsTimeBasedIntegrationTest.scala:467)
[info]   org.scalatest.exceptions.TestFailedException:
```

## 2. Duplicate check

No family, packet or recent issue (export issues-since-2026-10-20, 721 issues) has this assertion. Earlier packets on
this suite are different mechanisms: 10142 (family G, SummarizingMiningRoundTrigger retry budget), 10170 (D, sim-time
jump), 10197 (L, BFT read confirmation).
```
python3 -I -c '<scan of title, body, comments>' issues-since-2026-10-20.jsonl 'should have totals' 'ActivityTotalsUndetermined' 'TrafficBasedRewardsDryRun' 'CIP-104 reward accounting'
grep -l 'ActivityTotalsUndetermined\|should have totals' ci-triage/evidence/*.md
```
```
(no output from the scan)
(no output from the grep)
```

## 3. Suite timeline

```
zcat log/11144/art/canton_network_test.clog.gz | grep -a -E 'Starting test suite|Starting creating environment|Test failed' | grep -a TrafficBasedRewardsDryRun   # timestamp and message fields
```
```
2026-11-02T17:54:27.129Z Starting test suite 'TrafficBasedRewardsDryRunTimeBasedIntegrationTest'...
2026-11-02T17:54:27.416Z Starting creating environment for TrafficBasedRewardsDryRunTimeBasedIntegrationTest, test 'CIP-104 reward accounting pipeline works':
2026-11-02T17:57:50.251Z Test failed: 'TrafficBasedRewardsDryRunTimeBasedIntegrationTest/CIP-104 reward accounting pipeline works', message: The partial function passed as the second pa
```

## 4. sv1Scan computes rounds 3 to 10 in parallel, out of order

Rounds 0 to 2 are computed one at a time while ingestion is active. After the paused-ingestion section all of 3 to
10 are picked up at 17:57:27.407; round 10 commits at .433, rounds 4 and 5 at .510.
```
zcat log/11144/art/canton_network_test.clog.gz | grep -a 'TrafficBasedRewardsDryRun' | grep -a 'scan=sv1Scan' \
  | grep -a -E 'RewardComputationTrigger|DbScanAppRewardsStore' | (extract timestamp, logger, message)
```
```
2026-11-02T17:55:23.012Z  RewardComputationTrigger"INFO" Processing\nTask(roundNumber = 2)
2026-11-02T17:55:23.022Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 2.
2026-11-02T17:57:27.407Z  RewardComputationTrigger"INFO" Processing\nTask(roundNumber = 3)
2026-11-02T17:57:27.407Z  RewardComputationTrigger"INFO" Processing\nTask(roundNumber = 4)
2026-11-02T17:57:27.407Z  RewardComputationTrigger"INFO" Processing\nTask(roundNumber = 5)
2026-11-02T17:57:27.408Z  RewardComputationTrigger"INFO" Processing\nTask(roundNumber = 6)
2026-11-02T17:57:27.415Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 3.
2026-11-02T17:57:27.416Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 6.
2026-11-02T17:57:27.423Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 7.
2026-11-02T17:57:27.425Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 8.
2026-11-02T17:57:27.431Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 9.
2026-11-02T17:57:27.433Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 10.
2026-11-02T17:57:27.510Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 4.
2026-11-02T17:57:27.510Z  DbScanAppRewardsStore"DEBUG" Computed and stored rewards for round 5.
```
No pruning ran (PruneRewardAccountingTrigger only logged `Skipping pruning of round 0 ... still within the
retention period` until shutdown), so the missing row is "not yet written", not "deleted".

## 5. The test read round 4 before it committed

The `eventually` on round 10 succeeds at .451 (after round 10 committed at .433); the one-shot loop then reads
rounds 0 to 4 between .457 and .497, 13 ms before round 4 committed.
```
zcat log/11144/art/canton_network_test.clog.gz | grep -a 'TrafficBasedRewardsDryRun' | grep -a HttpRequestLogger | grep -a 'scan=sv1Scan' \
  | grep -a -E 'reward-accounting-process/rounds/[0-9]+/(activity-totals|root-hash).*(received request|Responding with entity)' | awk '/17:57:27/'
```
```
2026-11-02T17:57:27.451Z	/rounds/10/activity-totals : Responding with entity data: {\
2026-11-02T17:57:27.457Z	/rounds/0/activity-totals : received request.
...
2026-11-02T17:57:27.486Z	/rounds/3/activity-totals : received request.
2026-11-02T17:57:27.489Z	/rounds/3/activity-totals : Responding with entity data: {\
2026-11-02T17:57:27.492Z	/rounds/3/root-hash : received request.
2026-11-02T17:57:27.493Z	/rounds/3/root-hash : Responding with entity data: {\
2026-11-02T17:57:27.495Z	/rounds/4/activity-totals : received request.
2026-11-02T17:57:27.497Z	/rounds/4/activity-totals : Responding with entity data: {\
```

## 6. Why the answer is Undetermined, and that the test assumes ordered completion

At 5fdbcdce19, `HttpScanHandler.getRewardAccountingActivityTotals` returns `ingestionStatusForRound` when no totals
row exists; `DbAppActivityRecordStore.ingestionStatusForRound` returns `Undetermined` ("ingestion is still catching
up; retry") for a round beyond `earliest_ingested_round`. `RewardComputationTrigger` extends
`PollingParallelTaskExecutionTrigger` and returns every eligible round as a task, so a backlog completes in any
order. The test only waits for the last round:
```
git show 5fdbcdce19:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsTimeBasedIntegrationTest.scala | sed -n 456,467p
```
```
    clue("Scan computes activity totals through round 10") {
      eventually() {
        sv1ScanBackend.getRewardAccountingActivityTotals(10L) shouldBe an[
          GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsOk
        ]
      }
    }

    val totalsByRound: Map[Long, definitions.RewardAccountingActivityTotalsOk] =
      clue("Rounds 0..10 activity totals and root hash are computed") {
        (0L to 10L).map { round =>
          val totalsOk = inside(sv1ScanBackend.getRewardAccountingActivityTotals(round)) {
```
Unchanged on main 5c53b74294 (2026-11-03): same lines 456 to 467, and no commit to the test or the trigger in
`5fdbcdce19..5c53b74294`.

## Verdict

New family AD, first hit. Flake, test-side. Fix in
`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsTimeBasedIntegrationTest.scala`,
`assertRewardCalcs`, lines 456 to 462: make the `eventually()` wait for every round, e.g.
`(0L to 10L).foreach(r => sv1ScanBackend.getRewardAccountingActivityTotals(r) shouldBe an[...Ok])` (and the same
for `getRewardAccountingRootHash`), instead of round 10 only.
Affects both TrafficBasedRewardsTimeBasedIntegrationTest and the DryRun variant (shared base). No fix branch. Not
verified: the response body for round 4 (logged on the following line, not extracted; the assertion message shows
it), and whether root hash rows are written in the same transaction as the totals (the handler comment only says so for
activity and reward totals).
