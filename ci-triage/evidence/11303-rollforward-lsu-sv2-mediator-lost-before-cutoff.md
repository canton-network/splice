# 11303 11312 - RollForwardLsuDR: pre-DR SV onboarding does not finish before the 300 s LSU sequencing cutoff (runs 42639148146, 42658360509)

NEW (parent 11303, duplicate 11312; related to 11255): since #8234 (2026-11-06) the pre-DR sequencers of
`RollForwardLsuDRIntegrationTest` are DA BFT, so the joining SVs' BFT onboarding (ordering topology 1 to 2 to 4) now
runs inside the test's fixed budget: the pre-DR synchronizer stops sequencing at suite start plus 300 s
(`lsu-repair.global-max-sequencing-time-exclusive`, test line 89). Any init step of a joining SV that still needs a
sequenced submission after that never completes. Both runs fail in "Start nodes before DR" with an sv2 init timeout,
with two different delays in front of the cutoff: 11303 loses the MED::sv2 mediator broadcasts at the 1 to 4 step
(sections 1 to 9), 11312 starts about 70 s slower and stalls 18 s at the 2 to 4 step, so the joiners reach their last
ledger submission only around the cutoff (section 10). The 11255 window contributes to both, but in 11312 the
4-sequencer topology was active only 19 s before the cutoff, so the budget, not one particular 11255 shape, is the
common cause (section 11).

11303: main 92705421fd, job `roll-forward-lsu (1)`, Canton 3.7.2, tests
succeeded 0, failed 1. `RollForwardLsuDRIntegrationTest` failed in its first `startAllSync` (clue "Start nodes before
DR") because sv2 never finished initialization: it waited from 15:21:52 for its mediator to get unlimited traffic, which
needs `MED::sv2` in the MediatorSynchronizerState. All SVs proposed that change at 15:21:48-49, the instant the
ordering topology went from 1 to 4 sequencers (activation 15:21:48.324642). All four first broadcasts were lost: sv1
and sv3 were rejected with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`, the batches
of sv2 and sv4 were never ordered. sv1 and sv2 re-sent at 15:22:19 (accepted), but the queued copies of sv3 and sv4
were dropped when their apps switched the participants to their own sequencers (15:22:07, 15:22:12). The SV apps
re-proposed only at 15:23:38 and 15:23:49, after the pre-DR sequencers' `global-max-sequencing-time-exclusive`
(15:23:15.611637, suite start plus 300 s, test line 89) had passed; sv4's re-push was ordered and then refused with
`SEQUENCING_TIME_NOT_ADMISSIBLE`, and the third signature never arrived. Since #8234 (2026-11-06) the pre-DR
sequencers of this test are DA BFT, which brings the 1 to 4 onboarding window into the 5 min budget.

- Run (11303): https://github.com/canton-network/splice/actions/runs/42639148146, main 92705421fd ("Remove CometBFT
  support from pulumi (#8315)"), job 129236978278 `ci / scala_test_roll_forward_lsu / roll-forward-lsu (1)`, attempt 1.
- Run (11312): https://github.com/canton-network/splice/actions/runs/42658360509, main 91715b25c0 ("set temp
  thresholds (#8376)"), job 129303361898 `ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)`, attempt 1.
- Runtime canton: 3.7.2 at both shas (`nix/canton-sources.json`).
- Component: test time budget (pre-DR sequencing cutoff); Canton BFT sequencer onboarding (11255 window) as the delay.

Artifacts: `log/11303/job.log`, `log/11303/logs-roll-forward-lsu-1/` (artifact `logs-roll-forward-lsu-1`, created
2026-11-09T15:27:59Z). `L=log/11303/logs-roll-forward-lsu-1`,
`F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/RollForwardLsuDRIntegrationTest\/config=8d8ae105\///; s/\\n */ /g'`.

## 1. Failing assertion

Job resolution (recipe 1; this sandbox's gh has no `--allow-escape-sequences`, the log was fetched without it):

```
RUN=42639148146; export NAME='roll-forward-lsu (1)'
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
git show 92705421fd:nix/canton-sources.json | grep -m1 version
```
```
129236978278 attempt=1 ci / scala_test_roll_forward_lsu / roll-forward-lsu (1)
  "version": "3.7.2",
```

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11303/job.log | grep -a -A12 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | head -13
```
```
[info] - roll forward LSU DR *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$startAllSync$2(SpliceTests.scala:448)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$startAllSync$2$adapted(SpliceTests.scala:448)
[info]   at scala.collection.immutable.List.foreach(List.scala:323)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.startAllSync(SpliceTests.scala:448)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.startAllSync$(SpliceTests.scala:446)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.startAllSync(RollForwardLsuDRIntegrationTest.scala:44)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.$anonfun$new$3(RollForwardLsuDRIntegrationTest.scala:225)
```

Line 225 is the first `startAllSync`, inside `clue("Start nodes before DR")`, before any DR step:

```
git show 92705421fd:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala | sed -n '224,226p'
```
```
      clue("Start nodes before DR") {
        startAllSync((aliceValidatorBackend +: allNodes)*)
      }
```

## 2. Which app did not initialize

```
zcat $L/canton_network_test.clog.gz | grep -a -E "Starting test suite|Running clue: Start nodes before DR|Timeout while waiting for initialization|Test failed: " | sed -E "$F" | cut -c1-200
zcat $L/canton_network_test.clog.gz | grep -a -E '"message":"(sv[1-4]) app initialization: (Initialization complete|JoiningNodeInitializer joining Dso with key started)' | sed -E "$F" | cut -c1-150
```
```
2026-11-09T15:18:15.648Z Starting test suite 'RollForwardLsuDRIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-09T15:18:25.697Z Running clue: Start nodes before DR",[o.l.s.i.t.RollForwardLsuDRIntegrationTest:RollForwardLsuDRIntegrationTest] DEBUG
2026-11-09T15:26:43.062Z Timeout while waiting for initialization of sv2",[o.l.s.c.SvAppBackendReference:app=sv2] ERROR
2026-11-09T15:26:49.071Z Test failed: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR', message: Condition never became true within 5 minutes, location: SeeStackDepthException",[c.d.c.LogReporter
2026-11-09T15:19:05.781Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:SV=sv3] DEBUG
2026-11-09T15:19:05.781Z sv4 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:SV=sv4] DEBUG
2026-11-09T15:19:05.781Z sv2 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:SV=sv2] DEBUG
2026-11-09T15:20:09.818Z sv1 app initialization: Initialization complete, running on version 0.10.2-ci-snapshot.20261109.1.0.v92705421-dirty",[o.l.s.s
2026-11-09T15:22:08.773Z sv4 app initialization: Initialization complete, running on version 0.10.2-ci-snapshot.20261109.1.0.v92705421-dirty",[o.l.s.s
2026-11-09T15:22:09.436Z sv3 app initialization: Initialization complete, running on version 0.10.2-ci-snapshot.20261109.1.0.v92705421-dirty",[o.l.s.s
```

sv2, sv3 and sv4 join in parallel; sv3 and sv4 complete 3 min 53 s after suite start, sv2 never does.

## 3. sv2 waits for its mediator's unlimited traffic, which needs MED::sv2 to be a sequencer member

```
zcat $L/canton_network_test.clog.gz | grep -a -E 'Onboarded sequencer SEQ::sv2|Mediator has been granted unlimited traffic' | grep -a -E 'Onboarded|Waiting until|Success' | sed -E "$F" | cut -c1-170
zcat $L/canton_network_test.clog.gz | grep -a 'SV=sv2"' | grep -a "failed with a retryable error (full stack trace omitted): NOT_FOUND: No traffic state found for member MED::sv2" | awk -F'"' 'NR==1{f=$4} {l=$4; n++} END{print n" retries, first "f", last "l}'
zcat $L/canton_network_test.clog.gz | grep -a -E 'Member MED::sv2::[0-9a-f]+\.\.\. does not yet have a traffic state, skipping' | awk -F'"' 'NR==1{f=$4} {l=$4; n++} END{print n" lines \"Member MED::sv2... does not yet have a traffic state, skipping\", first "f", last "l}'
```
```
2026-11-09T15:21:49.162Z Onboarded sequencer SEQ::sv2::1220538104bf...",[o.l.s.s.LocalSynchronizerNode:SV=sv2] INFO
2026-11-09T15:21:52.475Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv2] INFO
2026-11-09T15:21:52.650Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
2026-11-09T15:21:53.061Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-09T15:22:00.018Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-09T15:22:05.219Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
80 retries, first 2026-11-09T15:21:52.481Z, last 2026-11-09T15:26:41.994Z
744 lines "Member MED::sv2... does not yet have a traffic state, skipping", first 2026-11-09T15:21:47.089Z, last 2026-11-09T15:25:14.454Z
```

The grant comes from `SvOnboardingUnlimitedTrafficTrigger`, which only acts on members the sequencer already has a
traffic state for (it logs and skips the others):

```
git show 92705421fd:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/singlesv/onboarding/SvOnboardingUnlimitedTrafficTrigger.scala | sed -n '68,75p'
```
```
        ) { memberId =>
          for {
            stateO <- sequencerAdminConnection.lookupSequencerTrafficControlState(memberId)
          } yield {
            if (stateO.isEmpty) {
              // This can happen for mediators which are registered in DsoRules before they connect.
              logger.info(s"Member $memberId does not yet have a traffic state, skipping")
            }
```

No sequencer had a traffic state for MED::sv2 until the end, i.e. MED::sv2 never became an active mediator.

## 4. The MediatorSynchronizerState change for MED::sv2 is proposed at the 1 to 4 ordering topology step

All four SV apps propose adding MED::sv2 (threshold of the decentralized namespace: 3 of 4 owners) at 15:21:48-49:

```
zcat $L/canton_network_test.clog.gz | grep -a 'Submitted proposal MediatorSynchronizerState' | grep -a 'for Add mediator MED::sv2' | awk -F'"' '$4<"2026-11-09T15:22"' | sed -E "$F" | sed -E 's/(threshold = 2), active = .*/\1, ...) for Add mediator MED::sv2 .../' | cut -c1-200
```
```
2026-11-09T15:21:48.560Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::12202de5a37d..., group = 0, threshold = 2, ...) for Add mediator MED::sv2 ...
2026-11-09T15:21:48.593Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::12202de5a37d..., group = 0, threshold = 2, ...) for Add mediator MED::sv2 ...
2026-11-09T15:21:49.095Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::12202de5a37d..., group = 0, threshold = 2, ...) for Add mediator MED::sv2 ...
2026-11-09T15:21:49.629Z Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::12202de5a37d..., group = 0, threshold = 2, ...) for Add mediator MED::sv2 ...
```

The pre-DR ordering topology switched from 1 to 4 sequencers at 15:21:48.324642 (sv1's epochs):

```
S=$L/canton-standalone-roll-forward-lsu-before-dr.clog.gz
zcat $S | grep -a -E 'New epoch 3[67] has started' | grep -a 'sequencer=sv1Standalone' | sed -E "$F" | sed -E 's/(SEQ::sv[1-4])::\.\.[0-9a-f]{4}/\1/g; s/ordering topology = OrderingTopology\( *activationTime = ([^,]*), *size = ([0-9]+).*/ordering topology = OrderingTopology(activationTime = \1, size = \2, .../' | cut -c1-260
zcat $S | grep -a 'Rejecting submission request by Some(PAR::sv' | awk -F'"' '$4>="2026-11-09T15:21:40" && $4<"2026-11-09T15:23"' | sed -E "$F" | cut -c1-235
```
```
2026-11-09T15:21:44.428Z New epoch 36 has started with leaders = List(SEQ::sv1)and blacklisted nodes = List(); ordering topology = OrderingTopology(activationTime = 2026-11-09T15:21:44.084173Z, size = 1, ...
2026-11-09T15:21:48.653Z New epoch 37 has started with leaders = List(SEQ::sv2, SEQ::sv3, SEQ::sv4, SEQ::sv1)and blacklisted nodes = List(); ordering topology = OrderingTopology(activationTime = 2026-11-09T15:21:48.324642Z, size = 4, ...
2026-11-09T15:21:49.111Z Rejecting submission request by Some(PAR::sv1::12206eb0a49d...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), r
2026-11-09T15:21:49.149Z Rejecting submission request by Some(PAR::sv1::12206eb0a49d...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), r
2026-11-09T15:21:49.170Z Rejecting submission request by Some(PAR::sv2::122035f1c580...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), r
2026-11-09T15:21:49.343Z Rejecting submission request by Some(PAR::sv4::1220d4fea10a...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), r
2026-11-09T15:21:49.429Z Rejecting submission request by Some(PAR::sv3::12201a98e3d7...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), r
2026-11-09T15:21:49.645Z Rejecting submission request by Some(PAR::sv3::12201a98e3d7...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), r
```

## 5. All four first broadcasts of the change (tx 14ac80e317e0) are lost

Per participant: push, message id, outcome (canton.clog, all SV participants then connected to `SEQ::sv1`):

```
C=$L/canton.clog.gz
zcat $C | grep -a -E 'participant=sv[1-4]Participant' | grep -a -E 'Attempting to push 1 topology transactions .*14ac80e317e0|Sending message ID (15827f79|a6112483|1f676e7f|42cb26c8|908ccd43|a004611c)|Sequencer send \[(a6112483|1f676e7f|42cb26c8)|responded the following for the given topology transactions: List\(\((Failed|Accepted),TxHash\(SHA-256:14ac80e317e0' | awk -F'"' '$4>="2026-11-09T15:21:48.5" && $4<"2026-11-09T15:22:20"' | sed -E 's/"logger_name":"[^"]*participant=(sv[1-4]Participant)[^"]*".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-200
```
```
2026-11-09T15:21:48.559Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:14ac80e317e0...))",[sv4Participant] DEBUG
2026-11-09T15:21:48.564Z Sending message ID 15827f79-17f9-4cc3-a274-b9e88551a561 to sequencer SEQ::sv1::1220888850ba...",[sv4Participant] DEBUG
2026-11-09T15:21:48.592Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:14ac80e317e0...))",[sv2Participant] DEBUG
2026-11-09T15:21:48.598Z Sending message ID a6112483-7d5e-4c52-9608-9ffef5166f48 to sequencer SEQ::sv1::1220888850ba...",[sv2Participant] DEBUG
2026-11-09T15:21:49.094Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:14ac80e317e0...))",[sv1Participant] DEBUG
2026-11-09T15:21:49.100Z Sending message ID 1f676e7f-e6a9-40ed-afb0-efa3f280d680 to sequencer SEQ::sv1::1220888850ba...",[sv1Participant] DEBUG
2026-11-09T15:21:49.628Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:14ac80e317e0...))",[sv3Participant] DEBUG
2026-11-09T15:21:49.633Z Sending message ID 42cb26c8-13bd-4e77-979d-7cc81573c05b to sequencer SEQ::sv1::1220888850ba...",[sv3Participant] DEBUG
2026-11-09T15:22:09.239Z Sequencer send [a6112483-7d5e-4c52-9608-9ffef5166f48] has timed out at 2026-11-09T15:22:09.032533Z",[sv2Participant] DEBUG
2026-11-09T15:22:09.243Z Synchronizer 'global' responded the following for the given topology transactions: List((Failed,TxHash(SHA-256:14ac80e317e0...)))",[sv2Participant] DEBUG
2026-11-09T15:22:09.633Z Sequencer send [1f676e7f-e6a9-40ed-afb0-efa3f280d680] has timed out at 2026-11-09T15:22:09.449783Z",[sv1Participant] DEBUG
2026-11-09T15:22:09.634Z Synchronizer 'global' responded the following for the given topology transactions: List((Failed,TxHash(SHA-256:14ac80e317e0...)))",[sv1Participant] DEBUG
2026-11-09T15:22:09.982Z Sequencer send [42cb26c8-13bd-4e77-979d-7cc81573c05b] has timed out at 2026-11-09T15:22:09.807931Z",[sv3Participant] DEBUG
2026-11-09T15:22:09.983Z Synchronizer 'global' responded the following for the given topology transactions: List((Failed,TxHash(SHA-256:14ac80e317e0...)))",[sv3Participant] DEBUG
2026-11-09T15:22:19.249Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:14ac80e317e0...))",[sv2Participant] DEBUG
2026-11-09T15:22:19.252Z Sending message ID 908ccd43-79d2-45da-b421-710c1cd375d5 to sequencer SEQ::sv1::1220888850ba...",[sv2Participant] DEBUG
2026-11-09T15:22:19.437Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:14ac80e317e0...)))",[sv2Participant] DEBUG
2026-11-09T15:22:19.638Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:14ac80e317e0...))",[sv1Participant] DEBUG
2026-11-09T15:22:19.641Z Sending message ID a004611c-a775-4a7b-9542-2cf9f39f026d to sequencer SEQ::sv1::1220888850ba...",[sv1Participant] DEBUG
2026-11-09T15:22:19.853Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:14ac80e317e0...)))",[sv1Participant] DEBUG
```

1f676e7f (sv1) and 42cb26c8 (sv3) are the rejected submissions of section 4 (15:21:49.111 and 15:21:49.645). The
sends of sv4 (15827f79) and sv2 (a6112483) were accepted into batches but never reached an output block; the retries
of sv2 and sv1 at 15:22:19 were ordered on all four sequencers:

```
for id in 15827f79-17f9-4cc3-a274-b9e88551a561 a6112483-7d5e-4c52-9608-9ffef5166f48 908ccd43-79d2-45da-b421-710c1cd375d5 a004611c-a775-4a7b-9542-2cf9f39f026d; do printf '%s output-block lines: ' $id; zcat $S | grep -a "$id" | grep -a -c 'in output block'; done
zcat $S | grep -a 'a6112483-7d5e-4c52-9608-9ffef5166f48' | grep -a 'LocalBatchCreated' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"(.{140}).*/\1 \2.../'
zcat $S | grep -a '15827f79-17f9-4cc3-a274-b9e88551a561' | grep -a 'LocalBatchCreated' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"(.{140}).*/\1 \2.../'
```
```
15827f79-17f9-4cc3-a274-b9e88551a561 output-block lines: 0
a6112483-7d5e-4c52-9608-9ffef5166f48 output-block lines: 0
908ccd43-79d2-45da-b421-710c1cd375d5 output-block lines: 4
a004611c-a775-4a7b-9542-2cf9f39f026d output-block lines: 4
2026-11-09T15:21:48.655Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..0345) with reference epoch 37 for batch received from local me...
2026-11-09T15:21:48.587Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..a166) with reference epoch 36 for batch received from local me...
```

After 15:22:19 the change has the signatures of sv1 and sv2, one short of the threshold of 3.

## 6. The queued copies of sv3 and sv4 are dropped when their apps switch sequencer connections

```
zcat $C | grep -a -E 'participant=sv[34]Participant' | grep -a -E 'Requeuing and stopping due to closing|Not requeuing topology transactions|Requeuing and backing off|Won.t schedule the task .*markDone|Connected to synchronizer and starting synchronisation|Nothing pending' | awk -F'"' '$4>="2026-11-09T15:22:00" && $4<"2026-11-09T15:23"' | sed -E 's/"logger_name":"[^"]*participant=(sv[1-4]Participant)[^"]*".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-200
zcat $L/canton_network_test.clog.gz | grep -a -E 'SV=sv[34]"' | grep -a -E 'Updating to new synchronizer connection config' | awk -F'"' '$4>="2026-11-09T15:22:00" && $4<"2026-11-09T15:23"' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{80}).*SV=(sv[34])".*/\1 [\3] \2.../'
```
```
2026-11-09T15:22:07.306Z Requeuing and stopping due to closing/synchronizer-disconnect",[sv4Participant] INFO
2026-11-09T15:22:07.306Z Not requeuing topology transactions due to an ongoing shutdown.",[sv4Participant] INFO
2026-11-09T15:22:07.456Z Connected to synchronizer and starting synchronisation: Synchronizer 'global'",[sv4Participant] INFO
2026-11-09T15:22:07.529Z Nothing pending. Marking as done.",[sv4Participant] DEBUG
2026-11-09T15:22:09.983Z Requeuing and backing off due to error The synchronizer Synchronizer 'global' failed the following topology transactions: List(SHA-256:14ac80e317e0...)",[sv3Participant] INFO
2026-11-09T15:22:12.872Z Connected to synchronizer and starting synchronisation: Synchronizer 'global'",[sv3Participant] INFO
2026-11-09T15:22:12.893Z Nothing pending. Marking as done.",[sv3Participant] DEBUG
2026-11-09T15:22:19.984Z Won't schedule the task 'com.digitalasset.canton.topology.QueueBasedSynchronizerOutbox.markDone' as this object is closing",[sv3Participant] DEBUG
2026-11-09T15:22:07.248Z [sv4] Updating to new synchronizer connection config for synchronizer Synchronizer 'gl...
2026-11-09T15:22:12.713Z [sv3] Updating to new synchronizer connection config for synchronizer Synchronizer 'gl...
```

sv3 had requeued the change after its timeout at 15:22:09; the reconnect at 15:22:12 replaced the outbox and the new
one had nothing pending. sv4's copy was still in flight (batch never ordered) when its outbox closed at 15:22:07.

## 7. The SV apps re-propose after the pre-DR synchronizer's sequencing cutoff

The splice side retries the whole proposal only after its `check established` wait gives up (about 110 s after the
first proposal):

```
zcat $L/canton_network_test.clog.gz | grep -a -E "The operation 'Add mediator MED::sv2::[0-9a-f]+\.\.\.' failed with a retryable error" | sed -E "$F" | sed -E 's/(failed with a retryable error).*\[/\1 ... [/' | head -3
```
```
2026-11-09T15:23:38.601Z The operation 'Add mediator MED::sv2::12202ba6f828...' failed with a retryable error ... [o.l.s.e.ParticipantAdminConnection:SV=sv4] INFO
2026-11-09T15:23:40.014Z The operation 'Add mediator MED::sv2::12202ba6f828...' failed with a retryable error ... [o.l.s.e.ParticipantAdminConnection:SV=sv2] INFO
2026-11-09T15:23:44.870Z The operation 'Add mediator MED::sv2::12202ba6f828...' failed with a retryable error ... [o.l.s.e.ParticipantAdminConnection:SV=sv1] INFO
```

By then the pre-DR sequencers refuse everything: their configured bound is 15:23:15.611637, the first refusal is at
15:23:16, and sv4's re-push at 15:23:38 is ordered and then refused:

```
zcat $L/canton_network_test.clog.gz | grep -a -m1 'Running clue: Starting external Canton process roll-forward-lsu-before-dr' | grep -a -oE 'sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-exclusive=[^,)]*'
zcat $S | grep -a -c 'SEQUENCING_TIME_NOT_ADMISSIBLE'
zcat $S | grep -a 'SEQUENCING_TIME_NOT_ADMISSIBLE' | sed -n '1p' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{190}).*/\1 \2.../'
zcat $S | grep -a 'd700a7ff-939b-44a7-a063-772920b7b76d' | grep -a -E 'Request to send submission|SEQUENCING_TIME_NOT_ADMISSIBLE' | head -2 | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{210}).*/\1 \2.../; s/1220[0-9a-f]{60}/../g'
```
```
sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-exclusive=2026-11-09T15:23:15.611637Z
1068
2026-11-09T15:23:16.165Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,9a1cdaf7): The sequencing time [2026-11-09T15:23:15.994873Z] is not admissible (sequencing time is at or after the upper bound (2026-11-09T15:23:15.611637Z))...
2026-11-09T15:23:38.858Z Request to send submission with id d700a7ff-939b-44a7-a063-772920b7b76d with max sequencing time 2026-11-09T15:23:58.852070Z from PAR::sv4::1220d4fea10a... to Set(All). ","logger_name":"c.d.c.s.s.b.BlockSequenc...
2026-11-09T15:23:38.969Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,92eceb13): The sequencing time [2026-11-09T15:23:38.809369Z] is not admissible (sequencing time is at or after the upper bound (2026-11-09T15:23:15.611637Z)) for Send(2026-11-09...
```

The bound is fixed when the suite object is constructed (suite start 15:18:15.648, section 2) as now plus 300 s;
`startAllSync` begins 10 s later:

```
git show 92705421fd:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala | sed -n '89,92p;210p'
```
```
  val maxSequencingTime = CantonTimestamp.now().plusSeconds(300)
  // 5s chosen by fair dice roll
  val lowerBoundSequencingTimeExclusive = maxSequencingTime.plusSeconds(5)
  val upgradeTime = lowerBoundSequencingTimeExclusive
        Seq(s"parameters.lsu-repair.global-max-sequencing-time-exclusive=${maxSequencingTime}"),
```

## 8. Why this test now has a BFT onboarding window: #8234

```
git log --format='%h %ad %ae %s' --date=short 92705421fd -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala | head -3
git show b77153c658 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala | grep -E '^[-+] .*(enableBftSequencer|TODO)'
git show 12f28d5707 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala | grep -E '^[-+] '
```
```
b77153c658 2026-11-06 31539813+rautenrieth-da@users.noreply.github.com Use DA BFT in all tests (#8234)
a82a341201 2026-10-27 martin.florian@digitalasset.com Disable key export from participants by default (#7977)
12f28d5707 2026-10-23 pawel.perek@digitalasset.com Raise max sequencing time in RollForwardLsuDRIntegrationTest to 5mins (#7921)
+        // TODO (#8263): remove, EnvironmentDefinition already enables DA BFT for all nodes
-      enableBftSequencer = false,
-        enableBftSequencer = true,
-  val maxSequencingTime = CantonTimestamp.now().plusSeconds(240)
+  val maxSequencingTime = CantonTimestamp.now().plusSeconds(300)
```

Before #8234 the pre-DR Canton (`roll-forward-lsu-before-dr`) ran with `enableBftSequencer = false`, so there was no
1 to 4 ordering topology step inside the 5 min budget.

## 9. Duplicate check

11255 (open, assignee rautenrieth-da) describes the same window; its comment names the rejection and the lost-batch
shapes and a case where the sv2 and sv4 mediators were never added:

```
gh api "repos/DACH-NY/cn-test-failures/issues/11255/comments?per_page=100" | jq -r '.[0].body' | grep -E 'P2P connectivity is not ready|11242|ordering topology grows' | cut -c1-400
```
```
While the global ordering topology grows from 1 to 4 sequencers, submissions are rejected, put into batches that are never ordered, or wait for an epoch that stalls until the newcomers have joined. Senders then wait for their max sequencing time. Under wall clock that costs about 60 s and usually only trips a circuit breaker or a client timeout; under simulated time it never comes, because nothing
16:25:50.961 Rejecting submission request by Some(PAR::sv1::12202b9dfdf2...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
- #11242 (simtime): the send was accepted but put into a batch that sv1 disseminated once to its three not yet authenticated peers and never mentioned again; the sv2 and sv4 mediators were never added.
```

Earlier `roll-forward-lsu` issues are other failures (search over issues updated since 2026-10-26, `log/issues-recent.jsonl`
built by the main thread with recipe 11):

```
jq -r 'select((.title + " " + (.body // "")) | test("roll-forward-lsu|RollForwardLsu")) | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10])"' log/issues-recent.jsonl | sort
for r in 10816 10821 10868 10869 10925 10941 10946 10977 10991 11001 11014 11042 11044 11082 11089 11108 11121 11132 11224; do printf '%s: ' $r; gh api "repos/DACH-NY/cn-test-failures/issues/$r/comments?per_page=100" | jq -r '.[0].body // "-"' | head -1 | cut -c1-90; done
for r in 10925 10927 10939; do gh api repos/DACH-NY/cn-test-failures/issues/$r | jq -r '"#\(.number) [\(.state)] \(.title[0:120])"'; done
```
```
#10816 closed/completed 2026-10-28
#10821 closed/completed 2026-10-28
#10868 closed/completed 2026-10-29
#10869 closed/completed 2026-10-29
#10925 open/reopened 2026-10-30
#10941 closed/not_planned 2026-10-30
#10946 closed/not_planned 2026-10-30
#10977 closed/not_planned 2026-10-30
#10991 closed/not_planned 2026-10-30
#11001 closed/not_planned 2026-10-30
#11014 closed/not_planned 2026-10-30
#11042 closed/not_planned 2026-10-30
#11044 closed/not_planned 2026-10-30
#11082 closed/not_planned 2026-10-30
#11089 closed/not_planned 2026-10-30
#11108 closed/not_planned 2026-10-31
#11121 closed/not_planned 2026-11-01
#11132 closed/not_planned 2026-11-02
#11224 closed/duplicate 2026-11-05
#11303 open/- 2026-11-09
#11312 open/- 2026-11-09
10816: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 
10821: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 
10868: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 
10869: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 
10925: Duplicate of #10927
10941: Duplicate of #10939
10946: Duplicate of #10939
10977: Duplicate of #10939
10991: Duplicate of #10939
11001: Duplicate of #10939
11014: Duplicate of #10939
11042: Duplicate of #10939 (fixed by canton-network/splice#8150)
11044: Duplicate of #10939 (fixed by canton-network/splice#8150)
11082: Duplicate of #10939: re-run of run 41047059702 on 5adf8c654f, which predates the fix canto
11089: Duplicate of #10939: re-run of run 41047059702 on 5adf8c654f, which predates the fix canto
11108: Duplicate of #10927
11121: Duplicate of #10927
11132: Duplicate of #10927
11224: Same run, nix setup failed on the GitHub 504 for the lnav zip.
#10925 [open] roll-forward-lsu: outbox pushes identity in another PV representation than the LSU successor holds
#10927 [closed] PV 36 nightly: joining SV nodes' outbox cannot push identity (OTK serial 1, PV 37 form), log check fails
#10939 [closed] main: Sv1BootstrapProtocolVersion36IntegrationTest does not compile after #7026 (participant admin API auth)
```

10816 to 10869 are the test compile break of 2026-10-28 (#8109); 10941 to 11089 the compile break of 2026-10-30
(10939); 10925 and 11108 to 11132 the PV representation of the outbox identity push (10927, family X2); 11224 a nix
setup failure. None is this shape. 11312 is section 10.

## 10. 11312: same test, same cutoff, different delay in front of it

Artifacts: `log/11312/job.log`, `log/11312/logs-roll-forward-lsu-0/` (artifact `logs-roll-forward-lsu-0`, created
2026-11-09T17:10:53Z). `L=log/11312/logs-roll-forward-lsu-0`, `S=$L/canton-standalone-roll-forward-lsu-before-dr.clog.gz`.

### 10.1 Job, failure, cutoff

```
RUN=42658360509; export NAME='roll-forward-lsu (0)'
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
git show 91715b25c0:nix/canton-sources.json | grep -m1 version
sed -E 's/\x1b\[[0-9;]*m//g' log/11312/job.log | grep -a -A14 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -a -E 'FAILED|IllegalStateException|RollForwardLsuDRIntegrationTest.scala:225' | head -3
zcat $L/canton_network_test.clog.gz | grep -a -m1 'Running clue: Starting external Canton process roll-forward-lsu-before-dr' | grep -a -oE 'sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-exclusive=[^,)]*'
zcat $L/canton_network_test.clog.gz | grep -a -E "Timeout while waiting for initialization|Test failed: " | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/'
printf 'SEQUENCING_TIME_NOT_ADMISSIBLE: '; zcat $S | grep -a -c 'SEQUENCING_TIME_NOT_ADMISSIBLE'
printf 'P2P connectivity is not ready: '; zcat $S | grep -a -c 'P2P connectivity is not ready'
printf 'Not requeuing topology transactions: '; zcat $L/canton.clog.gz | grep -a -c 'Not requeuing topology transactions'
```
```
129303361898 attempt=1 ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)
  "version": "3.7.2",
[info] - roll forward LSU DR *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.$anonfun$new$3(RollForwardLsuDRIntegrationTest.scala:225)
sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-exclusive=2026-11-09T17:05:30.733648Z
2026-11-09T17:09:44.090Z Timeout while waiting for initialization of sv2
2026-11-09T17:09:44.942Z Test failed: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR', message: Condition never became true withi
SEQUENCING_TIME_NOT_ADMISSIBLE: 1456
P2P connectivity is not ready: 0
Not requeuing topology transactions: 0
```

No rejected sends and no dropped outbox copies: not the 11303 mechanism.

### 10.2 Startup phases, 11303 versus 11312

Phase markers of the SV apps (same function on both artifacts):

```
phases() { zcat "$1" | grep -a -E '"message":"(Starting test suite|Running clue: Start nodes before DR|sv[1-4] app initialization: (Initialize app started|JoiningNodeInitializer joining Dso with key started|Initialization complete)|Got .DSO party ID from sponsoring SV|Packages vetting completed|Requesting to be onboarded via the sponsor SV|DSO party is now hosted|Onboarded sequencer|Onboarding mediator|Success: Mediator has been granted unlimited traffic|Timeout while waiting for initialization)' \
 | sed -E 's/\{"@timestamp":"[0-9-]+T([^"]+)","message":"([^"]{0,60})[^"]*".*(SV=sv[1-4]|app=sv[1-4]|reporter=scala-test|RollForwardLsuDRIntegrationTest\]?)".*/\1 \3 \2/; s/ app initialization: / /; s/Initialization complete, running on/Initialization complete/' \
 | awk '{k=$2" "$3" "$4" "$5; if(!(k in s)){s[k]=1; print}}' | cut -c1-120; }
echo "=== 11303"; phases log/11303/logs-roll-forward-lsu-1/canton_network_test.clog.gz
echo "=== 11312"; phases log/11312/logs-roll-forward-lsu-0/canton_network_test.clog.gz
```
```
=== 11303
15:18:15.648Z reporter=scala-test Starting test suite 'RollForwardLsuDRIntegrationTest'...
15:18:25.697Z RollForwardLsuDRIntegrationTest Running clue: Start nodes before DR
15:18:33.656Z SV=sv1 sv1 Initialize app started
15:18:34.018Z SV=sv2 sv2 Initialize app started
15:18:34.381Z SV=sv3 sv3 Initialize app started
15:18:34.784Z SV=sv4 sv4 Initialize app started
15:19:05.781Z SV=sv3 sv3 JoiningNodeInitializer joining Dso w
15:19:05.781Z SV=sv4 sv4 JoiningNodeInitializer joining Dso w
15:19:05.781Z SV=sv2 sv2 JoiningNodeInitializer joining Dso w
15:20:09.587Z SV=sv3 Got 'DSO party ID from sponsoring SV': DSO-8d8ae105-8d8ae105
15:20:09.818Z SV=sv1 sv1 Initialization complete 
15:20:11.272Z SV=sv4 Got 'DSO party ID from sponsoring SV': DSO-8d8ae105-8d8ae105
15:20:11.621Z SV=sv2 Got 'DSO party ID from sponsoring SV': DSO-8d8ae105-8d8ae105
15:21:14.033Z SV=sv3 Packages vetting completed
15:21:14.271Z SV=sv3 Requesting to be onboarded via the sponsor SV
15:21:16.140Z SV=sv4 Packages vetting completed
15:21:16.263Z SV=sv4 Requesting to be onboarded via the sponsor SV
15:21:17.991Z SV=sv2 Packages vetting completed
15:21:18.045Z SV=sv2 Requesting to be onboarded via the sponsor SV
15:21:22.040Z SV=sv4 DSO party is now hosted in the candidate SV participant PAR:
15:21:24.033Z SV=sv2 DSO party is now hosted in the candidate SV participant PAR:
15:21:25.607Z SV=sv3 DSO party is now hosted in the candidate SV participant PAR:
15:21:49.162Z SV=sv2 Onboarded sequencer SEQ::sv2::1220538104bf...
15:21:49.481Z SV=sv3 Onboarded sequencer SEQ::sv3::12208a62e011...
15:21:49.965Z SV=sv4 Onboarded sequencer SEQ::sv4::122041d5446a...
15:21:52.475Z SV=sv2 Onboarding mediator
15:21:52.650Z SV=sv3 Onboarding mediator
15:21:53.061Z SV=sv4 Onboarding mediator
15:22:00.018Z SV=sv4 Success: Mediator has been granted unlimited traffic
15:22:05.219Z SV=sv3 Success: Mediator has been granted unlimited traffic
15:22:08.773Z SV=sv4 sv4 Initialization complete 
15:22:09.436Z SV=sv3 sv3 Initialization complete 
15:26:43.062Z RollForwardLsuDRIntegrationTest Timeout while waiting for initialization of sv2
=== 11312
17:00:30.767Z reporter=scala-test Starting test suite 'RollForwardLsuDRIntegrationTest'...
17:00:41.385Z RollForwardLsuDRIntegrationTest Running clue: Start nodes before DR
17:00:48.310Z SV=sv1 sv1 Initialize app started
17:00:48.657Z SV=sv2 sv2 Initialize app started
17:00:49.000Z SV=sv3 sv3 Initialize app started
17:00:49.425Z SV=sv4 sv4 Initialize app started
17:01:19.856Z SV=sv2 sv2 JoiningNodeInitializer joining Dso w
17:01:19.856Z SV=sv3 sv3 JoiningNodeInitializer joining Dso w
17:01:19.856Z SV=sv4 sv4 JoiningNodeInitializer joining Dso w
17:02:22.874Z SV=sv4 Got 'DSO party ID from sponsoring SV': DSO-9bc5a4a3-9bc5a4a3
17:02:23.104Z SV=sv3 Got 'DSO party ID from sponsoring SV': DSO-9bc5a4a3-9bc5a4a3
17:02:23.250Z SV=sv2 Got 'DSO party ID from sponsoring SV': DSO-9bc5a4a3-9bc5a4a3
17:02:26.570Z SV=sv1 sv1 Initialization complete 
17:03:50.153Z SV=sv4 Packages vetting completed
17:03:50.856Z SV=sv4 Requesting to be onboarded via the sponsor SV
17:03:54.721Z SV=sv2 Packages vetting completed
17:03:56.128Z SV=sv2 Requesting to be onboarded via the sponsor SV
17:03:56.409Z SV=sv3 Packages vetting completed
17:03:57.564Z SV=sv3 Requesting to be onboarded via the sponsor SV
17:04:15.004Z SV=sv4 DSO party is now hosted in the candidate SV participant PAR:
17:04:16.746Z SV=sv2 DSO party is now hosted in the candidate SV participant PAR:
17:04:21.860Z SV=sv3 DSO party is now hosted in the candidate SV participant PAR:
17:04:50.668Z SV=sv4 Onboarded sequencer SEQ::sv4::12204b722fc4...
17:04:58.340Z SV=sv4 Onboarding mediator
17:05:08.829Z SV=sv4 Success: Mediator has been granted unlimited traffic
17:05:12.054Z SV=sv3 Onboarded sequencer SEQ::sv3::12209285f20f...
17:05:15.711Z SV=sv2 Onboarded sequencer SEQ::sv2::122011f16c9d...
17:05:18.887Z SV=sv3 Onboarding mediator
17:05:21.659Z SV=sv2 Onboarding mediator
17:05:30.112Z SV=sv2 Success: Mediator has been granted unlimited traffic
17:05:32.444Z SV=sv3 Success: Mediator has been granted unlimited traffic
17:09:44.090Z RollForwardLsuDRIntegrationTest Timeout while waiting for initialization of sv2
```

Offsets from suite start (cutoff at +5:00 in both): DSO party ID from the sponsor +1:54 to +1:56 (11303) and +1:52
(11312); packages vetting completed +2:58 to +3:02 versus +3:19 to +3:26 (vetting 63 to 67 s versus 87 to 93 s); DSO
party hosted +3:06 to +3:10 versus +3:44 to +3:51; sequencers onboarded +3:33 to +3:34 (all three at once) versus
+4:20, +4:41, +4:45. 11312 is about 70 s behind 11303 before any BFT topology change; in 11312 no joining SV logs
"Initialization complete". Why vetting and DSO hosting were slower was not investigated.

### 10.3 The ordering topology grows 1 to 2 to 4 and stalls 18 s at the second step

```
zcat $S | grep -a -E 'New epoch [0-9]+ has started' | grep -a 'sequencer=sv1Standalone' | awk -F'"' '$4>="2026-11-09T17:04:40" && $4<"2026-11-09T17:05:40"' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n */ /g' | sed -E 's/(SEQ::sv[1-4])::\.\.[0-9a-f]{4}/\1/g; s/ordering topology = OrderingTopology\( *activationTime = ([^,]*), *size = ([0-9]+).*/ordering topology = OrderingTopology(activationTime = \1, size = \2, .../' | cut -c1-250
zcat $S | grep -a 'Delivering block' | grep -a 'sequencer=sv1Standalone' | grep -a -E 'Delivering block (538|539|540|619|620) ' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Delivering block ([0-9]+) to post-ordering \(epoch = ([0-9]+), block.s BFT time = ([^,]+), block size = ([0-9]+).*/\1 block \2 epoch \3 bft=\4 size=\5/'
```
```
2026-11-09T17:04:44.917Z New epoch 29 has started with leaders = List(SEQ::sv1)and blacklisted nodes = List(); ordering topology = OrderingTopology(activationTime = 2026-11-09T17:04:44.270594Z, size = 1, ...
2026-11-09T17:04:50.200Z New epoch 30 has started with leaders = List(SEQ::sv1, SEQ::sv4)and blacklisted nodes = List(); ordering topology = OrderingTopology(activationTime = 2026-11-09T17:04:49.526356Z, size = 2, ...
2026-11-09T17:05:04.886Z New epoch 31 has started with leaders = List(SEQ::sv4, SEQ::sv1)and blacklisted nodes = List(); ordering topology = OrderingTopology(activationTime = 2026-11-09T17:04:49.526356Z, size = 2, ...
2026-11-09T17:05:11.612Z New epoch 32 has started with leaders = List(SEQ::sv1, SEQ::sv2, SEQ::sv3, SEQ::sv4)and blacklisted nodes = List(); ordering topology = OrderingTopology(activationTime = 2026-11-09T17:05:11.048612Z, size = 4, ...
2026-11-09T17:05:29.472Z New epoch 33 has started with leaders = List(SEQ::sv1, SEQ::sv2, SEQ::sv3)and blacklisted nodes = List(SEQ::sv4); ordering topology = OrderingTopology(activationTime = 2026-11-09T17:05:11.048612Z, size = 4, ...
2026-11-09T17:05:35.584Z New epoch 34 has started with leaders = List(SEQ::sv2, SEQ::sv3, SEQ::sv1)and blacklisted nodes = List(SEQ::sv4); ordering topology = OrderingTopology(activationTime = 2026-11-09T17:05:11.048612Z, size = 4, ...
2026-11-09T17:05:11.330Z block 538 epoch 31 bft=2026-11-09T17:05:11.047611Z size=1
2026-11-09T17:05:11.388Z block 539 epoch 31 bft=2026-11-09T17:05:11.048611Z size=0
2026-11-09T17:05:29.283Z block 540 epoch 32 bft=2026-11-09T17:05:11.049611Z size=0
2026-11-09T17:05:29.442Z block 619 epoch 32 bft=2026-11-09T17:05:11.128611Z size=0
2026-11-09T17:05:29.567Z block 620 epoch 33 bft=2026-11-09T17:05:23.261823Z size=21
```

The first epoch with four sequencers (32, from 17:05:11.6) delivered nothing until 17:05:29.28, then only empty
blocks; the next epoch blacklists sv4. This is the "epoch that stalls until the newcomers have joined" shape of 11255,
and it ends 1.5 s before the cutoff (17:05:30.733648).

### 10.4 What the joiners still needed: the `OnboardedAfterDelay` node config submission

After "SV nodes have been granted unlimited traffic" the join submits `DsoRules_SetSynchronizerNodeConfig`
(`reconcileSynchronizerNodeConfigIfRequired(..., OnboardedAfterDelay)`) before it completes:

```
git show 91715b25c0:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/joining/JoiningNodeInitializer.scala | sed -n '532,535p;546,553p'
```
```
            _ <- waitForSvToObtainUnlimitedTraffic(
              currentNode,
              decentralizedSynchronizer,
            )
        }
      _ = dsoAutomationService.registerPostUnlimitedTrafficTriggers()
      _ <-
        if (!config.shouldSkipSynchronizerInitialization) {
          synchronizerNodeReconciler
            .reconcileSynchronizerNodeConfigIfRequired(
              synchronizerNodeService.nodes,
              decentralizedSynchronizer,
```

(the next line, 554, is `OnboardedAfterDelay,`).

All three joiners reach that step; none completes it:

```
zcat $L/canton_network_test.clog.gz | grep -a -E 'SV=sv[2-4]"' | grep -a -E "Got 'SV nodes have been granted unlimited traffic'|The operation 'setting domain config for [^']*' failed with a retryable error" | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,100})[^"]*".*(SV=sv[2-4])".*/\1 [\3] \2/; s/1220[0-9a-f]{60}/../g' | awk '{k=$2" "substr($0,40,40); if(!(k in s)){s[k]=1; print}}'
```
```
2026-11-09T17:05:11.754Z [SV=sv4] Got 'SV nodes have been granted unlimited traffic': ()
2026-11-09T17:05:30.958Z [SV=sv2] Got 'SV nodes have been granted unlimited traffic': ()
2026-11-09T17:05:33.343Z [SV=sv3] Got 'SV nodes have been granted unlimited traffic': ()
2026-11-09T17:07:11.785Z [SV=sv4] The operation 'setting domain config for digital-asset-eng-4-9bc5a4a3::12205b26ff3d...' failed with 
2026-11-09T17:07:30.992Z [SV=sv2] The operation 'setting domain config for digital-asset-eng-2-9bc5a4a3::1220bc9e58b2...' failed with 
2026-11-09T17:07:33.384Z [SV=sv3] The operation 'setting domain config for digital-asset-eng-3-9bc5a4a3::12203b723351...' failed with 
```

sv2 (and sv3) submit after the cutoff and are refused at the sequencer. sv2's two submissions at 17:05:30.99:

```
zcat $L/canton.clog.gz | grep -a 'participant=sv2Participant' | awk -F'"' '$4>="2026-11-09T17:05:30.99" && $4<"2026-11-09T17:05:32"' | grep -a -E 'Submitted commands are|Sending message ID' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/'
for M in ac3d38eb-a317-4938-abeb-e3b9429d0be1 e97d50a5-7615-4831-90ab-e089645185ef; do printf '%s: ' $M; zcat $S | grep -a "$M" | grep -a -m1 -oE 'SEQUENCING_TIME_NOT_ADMISSIBLE\([^)]*\): The sequencing time \[[^]]*\] is not admissible \(sequencing time is at or after the upper bound \([^)]*\)\)|validated to: Deliver'; done
```
```
2026-11-09T17:05:30.994Z Submitted commands are: 'exercise @Splice.DsoRules:DsoRules DsoRules_SubmitStatusReport'
2026-11-09T17:05:30.998Z Submitted commands are: 'exercise @Splice.DsoRules:DsoRules DsoRules_SetSynchronizerNodeConfig'
2026-11-09T17:05:31.062Z Sending message ID ac3d38eb-a317-4938-abeb-e3b9429d0be1 to sequencer SEQ::sv1::122039a39d9c...
2026-11-09T17:05:31.062Z Sending message ID e97d50a5-7615-4831-90ab-e089645185ef to sequencer SEQ::sv1::122039a39d9c...
2026-11-09T17:05:31.535Z Sending message ID tick-f81aa0fb-4ffd-4152-bad1-2f510f390fd6 to sequencer SEQ::sv1::122039a39d9c...
ac3d38eb-a317-4938-abeb-e3b9429d0be1: SEQUENCING_TIME_NOT_ADMISSIBLE(9,62e91e31): The sequencing time [2026-11-09T17:05:30.967841Z] is not admissible (sequencing time is at or after the upper bound (2026-11-09T17:05:30.733648Z))
e97d50a5-7615-4831-90ab-e089645185ef: SEQUENCING_TIME_NOT_ADMISSIBLE(9,42fbdfe8): The sequencing time [2026-11-09T17:05:30.967840Z] is not admissible (sequencing time is at or after the upper bound (2026-11-09T17:05:30.733648Z))
```

The refusal ids 62e91e31 and 42fbdfe8 are the trace ids of sv2's `SubmitAndWait` and `SubmitAndWaitForTransaction`
calls (tid 62e91e31d1f7..., 42fbdfe873b3..., both `DEADLINE_EXCEEDED` at 17:07:30.99).

sv4 submitted at 17:05:11.79, 0.2 s after the 2 to 4 step, and its confirmation request sat in the stalled epoch:

```
zcat $L/canton.clog.gz | grep -a 'participant=sv4Participant' | grep -a -oE '"@timestamp":"[^"]+","message":"Registering handle for \(3a6491d1[^"]{0,160}' | sed -E 's/"@timestamp":"([^"]+)","message":"/\1 /'
zcat $L/canton.clog.gz | grep -a 'participant=sv4Participant' | grep -a -E "ChangeIdHash\(f078f392d099" | awk -F'"' '$4<"2026-11-09T17:05:12"' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\\n */ /g' | grep -a -oE "^[^ ]+ Registering submission InFlightSubmission\( change ID hash = [^,]*, submission id = [^,]*, submission synchronizer = [^,]*, message UUID = [^,]*"
zcat $S | grep -a '1bdde3d1-f906-481d-ba8e-69bec86a00b3' | grep -a -E "sends request with id|LocalBatchCreated|validated to" | grep -a 'sv1Standalone' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"(.{150}).*/\1 \2.../'
```
```
2026-11-09T17:05:11.789Z Registering handle for (3a6491d1-c20d-4b32-8490-00a3355d983c,sv4-9bc5a4a3,Set(digital-asset-eng-4-9bc5a4a3::12205b26ff3d9e9ff0950d179c1d254d6813a8ea64c45f1c41d98004cb1c8a8f4429),Some(9492fff6-
2026-11-09T17:05:11.811Z Registering submission InFlightSubmission( change ID hash = ChangeIdHash(f078f392d099...), submission id = 9492fff6-0996-425f-83d2-a6d0af984097, submission synchronizer = global-domain::1220cbcf171c..., message UUID = 1bdde3d1-f90...
2026-11-09T17:05:11.956Z 'PAR::sv4::12205b26ff3d...' sends request with id '1bdde3d1-f906-481d-ba8e-69bec86a00b3' of size 5052 bytes with 3 envelopes to MGR=1, PAR=4.","logger...
2026-11-09T17:05:11.976Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..e748) with reference epoch 32 for batch received from local mempool cont...
2026-11-09T17:05:29.589Z At block 620, the submission request 1bdde3d1-f906-481d-ba8e-69bec86a00b3 at 2026-11-09T17:05:23.261823Z validated to: Deliver","logger...
```

Command 3a6491d1 is sv4's `DsoRules_SetSynchronizerNodeConfig` (`SubmitAndWaitForTransaction`, tid 0761664e, sent
17:05:11.785, `DEADLINE_EXCEEDED` at 17:07:11.784). Its confirmation request reached the participants and mediators
only at 17:05:29.6, 1.1 s before the cutoff; the confirmation responses and the verdict were not followed.

## 11. Comparison and decision

| | 11303 | 11312 |
|---|---|---|
| cutoff (suite start + 300 s) | 15:23:15.611637 | 17:05:30.733648 |
| first 4-sequencer epoch | 15:21:48.65 (1 to 4) | 17:05:11.61 (1 to 2 to 4) |
| margin from 4 sequencers to cutoff | 87 s | 19 s |
| delay at the BFT step | four MED::sv2 broadcasts lost, re-proposal after 110 s | first 4-node epoch delivers nothing for 18 s |
| step that misses the cutoff | MED::sv2 mediator state (sv2 only) | SetSynchronizerNodeConfig (sv2, sv3; sv4 1.1 s before) |
| `P2P connectivity is not ready` (pre-DR log) / `Not requeuing topology transactions` (canton.clog), lines | 28 / 3 | 0 / 0 |
| SEQUENCING_TIME_NOT_ADMISSIBLE lines (pre-DR log) | 1068 | 1456 |

The 11303 counts of the row above, with the same greps as section 10.1:

```
L3=log/11303/logs-roll-forward-lsu-1
printf 'P2P connectivity is not ready: '; zcat $L3/canton-standalone-roll-forward-lsu-before-dr.clog.gz | grep -a -c 'P2P connectivity is not ready'
printf 'Not requeuing topology transactions: '; zcat $L3/canton.clog.gz | grep -a -c 'Not requeuing topology transactions'
```
```
P2P connectivity is not ready: 28
Not requeuing topology transactions: 3
```

Hit rate: 2 of the 18 post-merge main runs that contain #8234 failed `roll-forward-lsu`, both on 2026-11-09 (11303,
11312); the 4 runs since 2026-11-06 without #8234 passed:

```
git log -1 --format='%h %cd' --date=iso-strict b77153c658
for RUN in $(gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&per_page=40&created=>=2026-11-06" --jq '.workflow_runs[] | .id'); do gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?per_page=100" --jq '[.jobs[] | select(.name|test("/ roll-forward-lsu \\([01]\\)$")) | "\(.name|sub(".*/ ";""))=\(.conclusion // .status)"] | sort | join(" ")' | sed "s/^/$RUN /"; done
n=0; m=0; for x in $(gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&per_page=40&created=>=2026-11-06" --jq '.workflow_runs[] | "\(.id):\(.head_sha)"'); do s=${x#*:}; git cat-file -e $s 2>/dev/null || git fetch -q origin $s 2>/dev/null; if git merge-base --is-ancestor b77153c658 $s 2>/dev/null; then n=$((n+1)); else m=$((m+1)); echo "pre-8234: ${x%%:*} ${s:0:10}"; fi; done; echo "runs containing #8234: $n, without: $m"
```
```
b77153c658 2026-11-06T14:34:52+01:00
42658360509 roll-forward-lsu (0)=failure roll-forward-lsu (1)=success
42646231899 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42639148146 roll-forward-lsu (0)=success roll-forward-lsu (1)=failure
42639073931 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42637895044 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42634636103 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42623223930 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42620710625 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42618450792 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42608940134 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42591855865 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42288740264 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42258277175 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42253557708 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42253265180 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42248944117 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42230239729 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42226645323 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42214429624 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42209420702 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42205124002 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
42199308951 roll-forward-lsu (0)=success roll-forward-lsu (1)=success
pre-8234: 42214429624 97df2c4399
pre-8234: 42209420702 d2badce083
pre-8234: 42205124002 04c342f6fd
pre-8234: 42199308951 9f88d4ce64
runs containing #8234: 18, without: 4
```

Each run has two shards and the DR suite runs in one of them (the split order varies), so one failed shard per run is
one failed DR test.

The two runs share the test, the failing step and the fixed cutoff; the delays in front of it differ and both are
ordinary for a BFT network growing to 4 sequencers (11255). In 11312 the slower startup alone left 19 s between the
4-sequencer topology and the cutoff, less than one join step needs even without a stall. So the budget is the root
cause of this pair, and 11255's fix would only shrink one of the delays. The budget was set when the pre-DR
sequencers were reference sequencers (section 8); #8234 made them BFT on 2026-11-06.

## Verdict

New root cause, test: `RollForwardLsuDRIntegrationTest`'s pre-DR sequencing cutoff of suite start plus 300 s is too
short since #8234 made the pre-DR sequencers DA BFT; parent 11303, 11312 its duplicate. Flake on main: 2 of the 18
post-merge runs with #8234 (section 11), both on 2026-11-09. Each run shows a different 11255 delay
(lost broadcasts, epoch stall) in front of the cutoff; neither recovers because nothing can be sequenced after it.

11303 chain: 1 to 4 ordering topology at 15:21:48.32; the four first broadcasts of `MED::sv2` lost (two rejected,
two batches never ordered); sv3 and sv4 drop their copies on the sequencer connection switch (15:22:07, 15:22:12);
only sv1 and sv2 sign in time (15:22:19, threshold 3); the re-proposals start at 15:23:38, after the 15:23:15.6 cutoff
(sv4's re-push followed to its refusal; 1068 refusal log lines in the pre-DR Canton log, the first at 15:23:16); sv2
never gets its mediator, its init wait times out at 15:26:43.

11312 chain: startup about 70 s slower than 11303 before any BFT change; 2 to 4 ordering topology at 17:05:11.6, the
first 4-node epoch stalls until 17:05:29.3; sv2 and sv3 send their `OnboardedAfterDelay` node config after the cutoff
17:05:30.73 (refused), sv4's sits in the stalled epoch; sv2's init times out at 17:09:44.

Fix (test, `RollForwardLsuDRIntegrationTest.scala` line 89): give the pre-DR phase more headroom than 300 s. It costs
the same amount of test time, since the test sleeps until `upgradeTime = maxSequencingTime + 5 s` after the DR
restart (lines 91, 92, 281, 282). Alternatively start the joining SVs one at a time before DR. 11255's fix (sequential
onboarding, canton-network/splice#8356 in part) reduces the delays but does not restore the margin in 11312.
Assignee: none, by the user's decision on 2026-11-09. The suggestion had been pawelperek-da, who owns line 89 by git
blame (12f28d5707, #7921 raised it from 240 s to 300 s); the PR that brought the BFT window into the budget is #8234
(rautenrieth-da).

Not verified: why vetting and DSO hosting took about 20 to 40 s longer in 11312; how close passing runs since #8234
get to the cutoff (passing runs upload no log artifact; `gh api .../runs/42646231899/artifacts` lists no
`logs-roll-forward-lsu-*`); why the 11303 proposal retry waits about 110 s; the confirmation responses of sv4's
request in 11312.
