# 10271 - SvTimeBasedRoundMgmtIntegrationTest "doubled tickDuration": IssuingMiningRound 1 not created within the 20 s check because sv1's CalculateRewardsV2 confirmation waited 21 s in globalSequencerSv1's `insert block` 40001 retry chain (run 37013108911)

Family L (reference sequencer `insert block` SQLSTATE 40001 retry storm), fourth occurrence. This is the first where
the storm hits one sequencer's own inserts while blocks from the other three keep flowing. main 76624c8cf4, job
`simtime (3)`, 11 of 12 tests passed. In "round management with scheduled config change of doubled tickDuration",
the clue "advance to OpenMiningRound 5" waits up to 20 s (default `eventually()`) for IssuingMiningRound 1. That
needs sv1's `CalculateRewardsTrigger` to confirm CalculateRewardsV2 for round 1. Scan had the root hash ready by
13:41:52, the confirmation transaction was sequenced at 13:41:52.84, and sv1Participant sent its approval at
13:41:53.882. The approval then sat in globalSequencerSv1's store queue: its `insert block` failed with 40001
eight times, with backoff growing from 0.131 s to 5.612 s, until 13:42:14.925. The mediators finalized at
13:42:15.083 and the trigger completed at 13:42:15.801, 4.6 s after the clue had failed at 13:42:11.242.

- Run: https://github.com/canton-network/splice/actions/runs/37013108911, main 76624c8cf4 ("Scan: list peers' bulk
  storage objects through the Scan connections ..."), job 110857707106 `ci / scala_test_sim_time / simtime (3)`.
  Only failed job, one attempt.
- Runtime canton: 3.6.0-snapshot.20261001.20345.0.v85a9270a (`git show 76624c8cf4:nix/canton-sources.json | grep -m1 '"version"'`).
- Component: test (20 s budget on a check that spans an ordering round trip); contention itself is Canton / test infra.
- Artifact: `logs-simtime-3` in `log/10271/logs-simtime-3/`. The failing test's environment is `config=f6bf81a8`.

## 1. Failing assertion

```
$ sed 's/\x1b\[[0-9;]*[mJK]//g' log/10271/job.log | grep -a -E 'Tests: succeeded|\*\*\* FAILED|was not equal|SvTimeBasedRoundMgmtIntegrationTest.scala:[0-9]+\)$|^\S+ \[error\] \s+org' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++' | cut -c1-200
[info] - round management with scheduled config change of doubled tickDuration *** FAILED ***
[info]   List(0) was not equal to List(0, 1) (SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment.scala:94)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest.$anonfun$new$23(SvTimeBasedRoundMgmtIntegrationTest.scala:150)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest.$anonfun$new$18(SvTimeBasedRoundMgmtIntegrationTest.scala:149)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest.$anonfun$new$18$adapted(SvTimeBasedRoundMgmtIntegrationTest.scala:86)
[info] Tests: succeeded 11, failed 1, canceled 0, ignored 0, pending 0
[error] 	org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest
```

`SvTimeBasedRoundMgmtIntegrationTest.scala:150` is `assertTickDurationOfIssuingRound(Map(0L -> ..., 1L -> ...))` in
the "advance to OpenMiningRound 5" clue. The helper (`SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment.scala:90-103`)
is `eventually() { getSortedIssuingRounds(...).map(_.data.round.number) shouldBe ... }`.

## 2. Test timeline: the clue ran for exactly 20 s

```
$ zcat log/10271/logs-simtime-3/canton_network_test.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T13:41:02" and ."@timestamp" <= "2026-10-02T13:42:22" and .logger_name == "o.l.s.i.t.SvTimeBasedRoundMgmtIntegrationTest:SvTimeBasedRoundMgmtIntegrationTest" and (.message|test("^Starting creating environment for|^(Running|Finished|Failed) clue: advance to OpenMiningRound"))) | "\(."@timestamp") \(.message|.[0:150])"'
2026-10-02T13:41:02.797Z Starting creating environment for SvTimeBasedRoundMgmtIntegrationTest, test 'round management with scheduled config change of doubled tickDuration':
2026-10-02T13:41:44.757Z Running clue: advance to OpenMiningRound 4
2026-10-02T13:41:51.222Z Finished clue: advance to OpenMiningRound 4
2026-10-02T13:41:51.223Z Running clue: advance to OpenMiningRound 5
2026-10-02T13:42:11.242Z Failed clue: advance to OpenMiningRound 5
2026-10-02T13:42:21.755Z Starting creating environment for SvTimeBasedRoundMgmtIntegrationTest, test 'round management with scheduled config change of reduced tickDuration':
```

## 3. sv1's CalculateRewardsTrigger for round 1: three quick scan retries, then one 24 s attempt

```
$ zcat log/10271/logs-simtime-3/canton_network_test.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T13:41:50" and ."@timestamp" <= "2026-10-02T13:42:22" and (.logger_name|test("CalculateRewardsTrigger:SvTimeBasedRoundMgmtIntegrationTest/config=f6bf81a8/SV=sv1$")) and (.message|test("^Processing Task|^The operation .processTaskWithRetry. failed with a retryable|^Completed processing"))) | "\(."@timestamp") \(.message|gsub("\n";" ")|.[0:150])"'
2026-10-02T13:41:51.114Z The operation 'processTaskWithRetry' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: For round 1: our own Scan has not 
2026-10-02T13:41:51.323Z The operation 'processTaskWithRetry' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: For round 1: our own Scan has not 
2026-10-02T13:41:51.656Z The operation 'processTaskWithRetry' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: For round 1: our own Scan has not 
2026-10-02T13:42:15.801Z Completed processing with outcome: created confirmation for CalculateRewardsV2 round 1, processingDelay=PT0S
```

The three `has not yet computed the root hash` retries end at 13:41:51.656. The next attempt submits the
confirmation, which reaches the participant at 13:41:52.373 (section 4), and the trigger completes at 13:42:15.801.

## 4. The command on the Canton side: sequenced in 0.5 s, approval in flight 21 s

```
$ zcat log/10271/logs-simtime-3/canton-simtime.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T13:41:52" and ."@timestamp" <= "2026-10-02T13:42:16" and (.message|test("createStartProcessingRewardsV2Confirmation.*from ledger-api server|Phase 1 completed: Submitting|Phase 3: Validating Transaction request=1970-01-01T01:11:00.000349Z|Phase 4: Sending for request=1970-01-01T01:11:00.000349Z|Phase 5: Received 1 response.s. for request=1970-01-01T01:11:00.000349Z|Phase 6: Finalized request=RequestId.1970-01-01T01:11:00.000349Z|Send of .f75b6cb5|Storing at offset=652 ")) and (.logger_name|test("participant=sv1Participant|mediator=globalMediatorSv1|sequencer=globalSequencerSv1"))) | "\(."@timestamp") \(.logger_name|sub(":.*";"")|sub(".*\\.";"")) \(.message|gsub("\n";" ")|.[0:130])"'
2026-10-02T13:41:52.054Z TransactionProcessor Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-2-f6bf81a8::1220b6bddf06..., command-i
2026-10-02T13:41:52.373Z CantonSyncService Received submit-transaction org.lfdecentralizedtrust.splice.sv.createStartProcessingRewardsV2Confirmation_ff67acc49a8d7df1cdd81abd
2026-10-02T13:41:52.604Z TransactionProcessor Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-2-f6bf81a8::1220b6bddf06..., command-i
2026-10-02T13:41:53.431Z TransactionProcessor Phase 3: Validating Transaction request=1970-01-01T01:11:00.000349Z with 1 envelope(s)
2026-10-02T13:41:53.882Z TransactionProcessor Phase 4: Sending for request=1970-01-01T01:11:00.000349Z with msgId=f75b6cb5-e21c-4a97-b78e-350a338ac409 approved=1, rejected=0, a
2026-10-02T13:41:57.673Z TransactionProcessor Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-2-f6bf81a8::1220b6bddf06..., command-i
2026-10-02T13:42:14.946Z BlockChunkProcessor Processing block 2021, data chunk 0. Last chunk ts=1970-01-01T01:11:00.000409Z, last seq event ts=Some(1970-01-01T00:40:40.003594Z
2026-10-02T13:42:15.077Z ConfirmationRequestAndResponseProcessor Phase 5: Received 1 response(s) for request=1970-01-01T01:11:00.000349Z.
2026-10-02T13:42:15.083Z ConfirmationRequestAndResponseProcessor Phase 6: Finalized request=RequestId(1970-01-01T01:11:00.000349Z) with verdict Approve at 1970-01-01T01:12:00.000349Z
2026-10-02T13:42:15.493Z ParallelIndexerSubscription Phase 7: Storing at offset=652 SequencedTransactionAccepted(   recordTime = 1970-01-01T01:11:00.000349Z,   updateId = 12208dffb540
```

The request (`01:11:00.000349`) is validated and approved by sv1Participant 1.5 s after it reached the participant. The only
confirmer is sv1, because the environment runs `initDsoWithSv1Only()`. Its response (`f75b6cb5`) is only included
in block 2021 at 13:42:14.946. The `Phase 1 completed` lines at 13:41:52.054 and 13:41:57.673 are other
submissions by the same party.

## 5. globalSequencerSv1: its own `insert block` fails with 40001 for 21 s

```
$ zcat log/10271/logs-simtime-3/canton-simtime.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T13:41:53.8" and ."@timestamp" <= "2026-10-02T13:42:15.1" and (.logger_name|test("(DbStorageSingle|ReferenceSequencerDriver):sequencer=globalSequencerSv1/")) and (.message|test("insert block. has failed|Now retrying operation .insert block|enqueued reference sequencer store request|Stored batch"))) | "\(."@timestamp") \(.message|gsub("\n";" ")|.[0:140])"'
2026-10-02T13:41:53.846Z The operation 'insert block' has failed with an exception. Retrying after 0.131s. 
2026-10-02T13:41:53.886Z enqueued reference sequencer store request with tag send and sequencing time (ms since epoch) 4260000452
2026-10-02T13:41:53.977Z Now retrying operation 'insert block'. 
2026-10-02T13:41:54.007Z The operation 'insert block' has failed with an exception. Retrying after 0.365s. 
2026-10-02T13:41:54.372Z Now retrying operation 'insert block'. 
2026-10-02T13:41:54.729Z The operation 'insert block' has failed with an exception. Retrying after 0.727s. 
2026-10-02T13:41:55.456Z Now retrying operation 'insert block'. 
2026-10-02T13:41:56.181Z The operation 'insert block' has failed with an exception. Retrying after 1.467s. 
2026-10-02T13:41:57.649Z Now retrying operation 'insert block'. 
2026-10-02T13:41:57.673Z The operation 'insert block' has failed with an exception. Retrying after 2.731s. 
2026-10-02T13:41:57.679Z enqueued reference sequencer store request with tag send and sequencing time (ms since epoch) 4260000701
2026-10-02T13:42:00.405Z Now retrying operation 'insert block'. 
2026-10-02T13:42:00.450Z The operation 'insert block' has failed with an exception. Retrying after 3.291s. 
2026-10-02T13:42:03.741Z Now retrying operation 'insert block'. 
2026-10-02T13:42:03.926Z The operation 'insert block' has failed with an exception. Retrying after 5.383s. 
2026-10-02T13:42:09.309Z Now retrying operation 'insert block'. 
2026-10-02T13:42:09.312Z The operation 'insert block' has failed with an exception. Retrying after 5.612s. 
2026-10-02T13:42:14.925Z Now retrying operation 'insert block'. 
2026-10-02T13:42:14.929Z Stored batch of requests
2026-10-02T13:42:14.932Z Stored batch of requests
2026-10-02T13:42:15.090Z enqueued reference sequencer store request with tag send and sequencing time (ms since epoch) 4260001897
```

The approval is enqueued at 13:41:53.886 (sequencing time 4260000452 ms = `01:11:00.000452`, the timestamp it is
delivered at in block 2021). It is stored together with the queued batch only at 13:42:14.929, when the eighth
retry succeeds. Ordering itself did not stop: the other sequencers' inserts went through, and all four drivers
read new blocks throughout the window:

```
$ zcat log/10271/logs-simtime-3/canton-simtime.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T13:41:53.8" and ."@timestamp" <= "2026-10-02T13:42:14.9" and (.logger_name|test("ReferenceSequencerDriver:sequencer=globalSequencer")) and (.message|test("^New blocks"))) | "\(.logger_name|capture("sequencer=(?<s>globalSequencerSv[0-9])").s) \(.message|capture("starting at height (?<h>[0-9]+)").h)"' | awk '{n[$1]++; if(!($1 in lo)) lo[$1]=$2; hi[$1]=$2} END{for(s in n) print s, n[s], "batches of new blocks, heights", lo[s], "..", hi[s]}' | sort
globalSequencerSv1 99 batches of new blocks, heights 1899 .. 2019
globalSequencerSv2 99 batches of new blocks, heights 1899 .. 2019
globalSequencerSv3 99 batches of new blocks, heights 1899 .. 2019
globalSequencerSv4 98 batches of new blocks, heights 1899 .. 2018
```

## Verdict

- Family L, fourth occurrence (10139, 10197, 10256 before). Flake: the contention is the reference sequencer's
  serializable `insert block` on one shared Postgres (Canton / test infra), and the backoff reached 5.612 s here
  (5.583 s in 10256, 8.6 s in 10197). New shape: only globalSequencerSv1's own writes stalled, so only submissions
  routed through sv1's sequencer were delayed, while the global synchronizer kept producing blocks.
- Fix, test side, same pattern as `ray/fix-10197-bft-read-confirmation-wait` (a 90 s budget on a check that
  depends on one ordering round trip): `s11/fix-10271-issuing-round-wait-budget` (180cae90f8) changes
  `assertTickDurationOfIssuingRound`'s `eventually()` to `eventually(90.seconds)`. All 10 call sites are in
  SvTimeBasedRoundMgmtIntegrationTest, and each waits for round automation that needs at least one confirmation
  round trip.
- Verified: `sbt apps-app/Test/scalafmtCheck` passes. Not verified: compile or a run of the suite. The fix does not
  reduce the contention; a stall longer than 90 s would still fail.
