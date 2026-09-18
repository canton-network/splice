# 10175 - UnclaimedActivityRecordIntegrationTest "An UnclaimedActivityRecord gets expired": pausing alice's merge trigger times out after 5 s because the trigger is stuck retrying a deadline-exceeded Daml error on the already expired record (run 35368993884)

Recurrence of the race #5176 (ffabdb982e, 2026-04-22, "Fix timing issue in unclaimed activity test", cn-test-failures
7864) tried to fix by widening the record's expiry from 5 s to 10 s. The first trigger block took 10.6 s this time, so
the record expired before the block ended, and the one-instant resume of alice's merge trigger between the two blocks
was enough for it to start a task it could not finish.

- Run: https://github.com/canton-network/splice/actions/runs/35368993884, main 6d59d2b131 ("Wait until the settlement
  venue's participant sees both allocations ... (#7416)"), job 105678546586 `wall-clock-time (1)`, canton
  3.6.0-snapshot.20260916.20284.0.vf27c4824. The run was still in progress (docker-compose (1)) when triaged.
- Only other failed job: none. Suite list of the shard: AnsIntegrationTest SvDevNetReonboardingIntegrationTest
  SvIdentitiesDumpIntegrationTest SvMergeSvRewardStateIntegrationTest UnclaimedActivityRecordIntegrationTest
  UpdateHistoryIntegrationTest ValidatorSequencerConnectionIntegrationTest WalletSubscriptionsIntegrationTest
  WalletTxLogWithSynchronizerFeesIntegrationTest.

## 1. Classification

```
gh api repos/canton-network/splice/actions/jobs/105678546586/logs > log/10175/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10175/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/10175/job.log | grep -a -A12 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -14
```
```
[info] *** 1 TEST FAILED ***
[info] - An UnclaimedActivityRecord gets expired *** FAILED ***
[info] Tests: succeeded 21, failed 1, canceled 0, ignored 0, pending 0
[info]   A timeout occurred waiting for a future to complete. Waited 5 seconds. (TriggerTestUtil.scala:93)
[info]   at org.lfdecentralizedtrust.splice.util.TriggerTestUtil$.$anonfun$setTriggersWithin$1(TriggerTestUtil.scala:93)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.UnclaimedActivityRecordIntegrationTest.$anonfun$new$10(UnclaimedActivityRecordIntegrationTest.scala:196)
```
`TriggerTestUtil.scala:93` is `triggersToPauseAtStart.foreach(_.pause().futureValue)` (5 s patience from
`ScalaFuturesWithPatience`); line 196 is the second `setTriggersWithin` of the test, which pauses only
`mergeAmuletsTrigger(aliceValidatorBackend, aliceUserName)`. `PollingTrigger.pause()` (apps/common ...
automation/PollingTrigger.scala:244-256) completes only when the currently running task finishes.

## 2. Timeline (artifact logs-wall-clock-time-1, canton_network_test.clog.gz)

```
zcat $A | grep -a UnclaimedActivityRecordIntegrationTest | grep -a -E 'Pausing triggers for block|Resuming triggers after block|Running clue|Finished clue|Test failed' | <ts+message>
zcat $A | grep -a -E 'AllocateUnallocatedUnclaimedActivityRecordTrigger|ExpiredUnclaimedActivityRecordTrigger' | grep -a -v DEBUG | <ts+message>
zcat $A | grep -a 'Running command GetActiveContracts' | grep -a 'DSO-6674e0e8' | <ts only>          # the check's polls
```
```
16:48:49.752  Pausing triggers for block: [CollectRewardsAndMergeAmuletsTrigger(alice), ExpiredUnallocated..., ExpiredUnclaimed...]
16:48:49.753  Running clue: (act) Creating vote request            -> expiresAt = now + 10 s = 16:48:59.753
16:48:50.163  Running clue: (check) UnclaimedActivityRecord has been created
16:48:53.126  sv1 AllocateUnallocatedUnclaimedActivityRecordTrigger: Processing AssignedContract(UnallocatedUnclaimedActivityRecord ...)
16:48:55.472  sv1 AllocateUnallocatedUnclaimedActivityRecordTrigger: Completed processing with outcome: allocated unallocated unclaimed activity record
check polls:  50.168 50.185 50.211 50.258 50.345 50.514 50.841 51.489 52.777 55.344 | 00.352
16:49:00.366  Finished clue: (check) UnclaimedActivityRecord has been created      (block 1 took 10.6 s)
16:49:00.366  Resuming triggers after block: [merge(alice), Expired...]
16:49:00.367  Pausing triggers for block: [CollectRewardsAndMergeAmuletsTrigger(alice)]
16:49:05.369  Resuming triggers after block (finally of the failed pause)
16:49:05.724  sv1 ExpiredUnclaimedActivityRecordTrigger: Processing ReadyTask(readyAt = 16:49:00.723635Z, UnclaimedActivityRecord ...)
16:49:06.380  sv1 ExpiredUnclaimedActivityRecordTrigger: Completed processing with outcome: archived expired unclaimed activity record
16:49:12.885  Test failed: ... Waited 5 seconds
```
The check polls with `actAndCheck`'s exponential backoff capped at 5 s (SpliceTests.scala:353-364,
`maxPollInterval = 5.seconds`). The allocation landed 128 ms after the 16:48:55.344 poll, so the next poll came at
16:49:00.352, 600 ms after the record's `expiresAt`.

## 3. What alice's merge trigger did in the 5 s (same artifact)

```
zcat $A | grep -a 'endUserParty=alice__wallet__user' | grep -a -E 'CollectRewardsAndMergeAmuletsTrigger|TreasuryService' | grep -a -E 'T16:49:0' | grep -a -v DEBUG | <ts+message>
```
```
16:49:00.366  TreasuryService: Received operation AmuletOperation(op = CO_MergeTransferInputs(unit()), priority = Low)
16:49:00.401  TreasuryService: Batch failed with FAILED_PRECONDITION: DAML_FAILURE(9,...): stdlib.daml.com/deadline-exceeded: Ledger time is at or past deadline 'UnclaimedActivityRecord.expiresAt' at 2026-09-18T16:48:59.753193Z, failing all operations
16:49:00.401  CollectRewardsAndMergeAmuletsTrigger: The operation 'Collect rewards and merge amulets' failed with a retryable error ... category=Some(InvalidGivenCurrentSystemStateOther)
16:49:00.401  ... New kind of error: transient error (request infinite retries). Retrying after a number of 0 failures, and after 200 milliseconds.
16:49:00.614  Batch failed ... deadline-exceeded ... Retrying after 1 failures, 283 ms
16:49:00.912  Batch failed ... Retrying after 2 failures, 695 ms
16:49:01.622  Batch failed ... Retrying after 3 failures, 912 ms
16:49:02.549  Batch failed ... Retrying after 4 failures, 1979 ms
16:49:04.542  Batch failed ... Retrying after 5 failures, 3340 ms
16:49:07.883  Now retrying operation 'Collect rewards and merge amulets'.          (record archived at 16:49:06.380; pause had already timed out at 16:49:05.369)
```
The wallet lists every `UnclaimedActivityRecord` of the user as a transfer input without looking at `expiresAt`
(`UserWalletStore.listUnclaimedActivityRecords` -> `TreasuryService.getUnclaimedActivityRecordsAndQuantity`,
TreasuryService.scala:1272-1292), unlike the `minTtl` filters in `RewardSharingTrigger` and
`MintingDelegationCollectRewardsTrigger`. Daml rejects the expired input with deadline-exceeded, the trigger classifies
that as retryable and keeps the same task alive with growing backoff, and `pause()` cannot complete until the DSO's
expiry trigger archives the record.

## 4. Causal chain

1. `expiresAt` is taken as `Instant.now() + 10 s` at 16:48:49.753 (the value #5176 widened from 5 s).
2. Block 1 ends only at 16:49:00.366: the allocation lands at 16:48:55.472, right after a poll, and the check's
   backoff has reached its 5 s cap. The record is already expired.
3. Block 1's `finally` resumes alice's merge trigger; the test re-pauses it 1 ms later, but the poll loop has already
   started a task that includes the expired record as an input.
4. The task fails with deadline-exceeded and retries inside the same task; `pause()` waits for the task, and the 5 s
   `futureValue` patience runs out at 16:49:05.369. The expiry trigger archives the record at 16:49:06.380.

## 5. Verdict

Test flake, recurrence of 7864/#5176 with a different failure point; the 10 s margin is not the real fix. The test
must not let the merge trigger run between the two blocks at all. Fix branch
`ray/fix-10175-unclaimed-activity-record-keep-merge-paused` (a107408328, off main 6d59d2b131): the merge trigger is
paused by an outer `setTriggersWithin` for the whole flow, the inner block pauses only the two expiry triggers, and the
"gets archived" check runs while the merge trigger is still paused (the same shape the first test in the file already
uses with the allocate triggers). Verified: `apps-app/Test/scalafmtCheck` only; not compiled or run here.

App-side note, not written: the wallet could skip `UnclaimedActivityRecord` inputs whose `expiresAt` has passed (or is
within a small ttl), as the reward-sharing and minting-delegation triggers do, instead of retrying until the DSO
archives them.
