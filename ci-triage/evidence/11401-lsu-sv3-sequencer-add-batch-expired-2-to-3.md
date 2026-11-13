# 11401 - logical-sync-upgrade (0): sv3 sequencer add misses its max sequencing time in the 2 -> 3 epoch, sv3 initializes 5 s after the 5 min wait (run 43295852356)

DUPLICATE of 11255 (BFT onboarding window, the wall-clock shape of 11350). Main 1fb5a7382f, job logical-sync-upgrade
(0), Canton 3.7.3. LsuIntegrationTest "cancel a scheduled logical synchronizer upgrade" fails in `initDso` with
`Condition never became true within 5 minutes` for sv3's SV app. sv2, sv4 and sv3 join through sv1. The global
ordering topology goes from 2 to 3 nodes at 20:53:57.086 (the newcomer sv2 joins that epoch only at 20:54:25.580).
The four SV participants build the SequencerSynchronizerState proposal adding SEQ::sv3 at 20:53:57.5-.8; sv1's
sequencer puts sv4's and sv2's sends into a batch with reference epoch 27 at 20:53:57.877, which is first proposed
in epoch 28 at 20:54:26.040, after the requests' max sequencing time 20:54:17.756. Both outboxes report `Failed` at
20:54:26.4, sv1's `onboard/sv/sequencer` handler returns 503 at 38 s (four times, 20:54:35-20:55:54), and the add
only becomes effective with sv4's re-proposal at 20:55:51.6. sv3's SV app finishes initialization at 20:56:07.523,
5 s after the test gave up (20:56:02.095). Test 2 then fails as a consequence (sv3Validator `Wait for user failed`
at its start, `checkSerial1Sequencers` size mismatch). Not caused by #8421.

- Run: https://github.com/canton-network/splice/actions/runs/43295852356, main 1fb5a7382f (#8421), job 131454732645
  `ci / scala_test_logical_sync_upgrade / logical-sync-upgrade (0)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at 1fb5a7382f).
- Component: Canton BFT ordering (ordering stall in the epoch after a topology change, wall clock).

## 1. Job: both LsuIntegrationTest tests fail

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11401/job.txt | grep -a -A14 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | head -18
```
```
[info] - cancel a scheduled logical synchronizer upgrade *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$initDso$3(SpliceTests.scala:313)
...
[info]   at org.lfdecentralizedtrust.splice.integration.tests.LsuIntegrationTest.$anonfun$new$1(LsuIntegrationTest.scala:227)
--
[info] - upgrade synchronizer to new physical synchronizer without downtime *** FAILED ***
[info]   Vector(DsoSequencer(0, SEQ::sv1::1220b31bd858..., "http://localhost:5108", "Digital-Asset-2", 2026-11-12T20:52:16.023581Z), DsoSequencer(0, SEQ::sv2::1220281ae124..., ...
[info]   at org.lfdecentralizedtrust.splice.integration.tests.LsuIntegrationTest$$anonfun$$nestedInanonfun$checkSerial1Sequencers$2$1.applyOrElse(LsuIntegrationTest.scala:342)
```

## 2. Timeline

Artifact `logs-logical-sync-upgrade-0` in `log/11401/logs-logical-sync-upgrade-0/`.
```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Starting '|Test (succeeded|failed): |Wait for user failed" | <timestamp message>
zcat canton_network_test.clog.gz | grep -a 'SV=sv3"' | grep -a -E 'app initialization: .*(Initialize app started|joining Dso with key|Initialize app finished)'
```
```
2026-11-12T20:50:46.992Z Starting test suite 'LsuIntegrationTest'...
2026-11-12T20:50:53.990Z Starting 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade'...
2026-11-12T20:56:02.095Z Test failed: 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade', message: Condition never became true within 5 minutes, location: SeeStackDepthException
2026-11-12T20:56:09.376Z Starting 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'...
2026-11-12T20:56:09.491Z sv3Validator app initialization: Wait for user failed
2026-11-12T20:58:47.382Z Test failed: 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime', message: Vector(DsoSequencer(0, SEQ::sv1::1220b31bd858..., ...
2026-11-12T20:50:58.210Z sv3 app initialization: Initialize app started
2026-11-12T20:51:38.291Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started
2026-11-12T20:56:06.356Z sv3 app initialization: JoiningNodeInitializer joining Dso with key finished after PT4M28.064521383S
2026-11-12T20:56:07.523Z sv3 app initialization: Initialize app finished after PT5M9.313180312S
```
Where sv3's join spent its time (operation, first, last, count):
```
zcat canton_network_test.clog.gz | grep -a 'SV=sv3"' | awk '/T20:5[45]|T20:56:0[0-6]/' | grep -a -oE '"@timestamp":"[^"]+","message":"(The operation .[^:]{0,110}|Waiting [^"]{0,120}|Success: [^"]{0,100})' | <first, last, count per message>
```
```
2026-11-12T20:54:00.127Z 2026-11-12T20:55:51.343Z 73 The operation 'check established Add sequencer SEQ
2026-11-12T20:54:02.390Z 2026-11-12T20:55:50.991Z 69 The operation 'Check whether Sequencer is added to the topology state
2026-11-12T20:54:35.164Z 2026-11-12T20:55:54.923Z 4 The operation 'Onbarding sequencer through sponsoring SV' failed with
2026-11-12T20:55:53.491Z 2026-11-12T20:55:53.491Z 1 Success: Add sequencer SEQ::sv3::12203f7c45c1...
2026-11-12T20:55:54.429Z 2026-11-12T20:55:54.429Z 1 Success: Add mediator MED::sv3::122022feafcd...
2026-11-12T20:56:03.953Z 2026-11-12T20:56:03.953Z 1 Success: Mediator has been granted unlimited traffic
```
```
zcat canton_network_test.clog.gz | grep -a 'SV=sv3"' | grep -a "Onbarding sequencer through sponsoring SV' failed" | cut -c1-330
```
```
2026-11-12T20:54:35.164Z The operation 'Onbarding sequencer through sponsoring SV' failed with a retryable HTTP error: ... HTTP 503 Service Unavailable POST at '/api/sv/v0/onboard/sv/sequencer' on 127.0.0.1:5114. Command failed, message: The server is taking too long to respond ...
2026-11-12T20:55:13.391Z ... HTTP 503 Service Unavailable POST at '/api/sv/v0/onboard/sv/sequencer' ...
2026-11-12T20:55:51.631Z ... HTTP 503 Service Unavailable POST at '/api/sv/v0/onboard/sv/sequencer' ...
2026-11-12T20:55:54.923Z ... HTTP 429 Too Many Requests POST at '/api/sv/v0/onboard/sv/sequencer' ... SERVER_OVERLOADED(2,0): Reached the limit of concurrent requests for com.digitalasset.canton.sequencer.admin...
```
Before that, sv3's join had already spent 20:51:38-20:52:25 on `Get DSO party ID from sponsoring SV` and
20:52:27-20:53:39 on DAR uploads.

## 3. The sv3 add: built at the 2 -> 3 step, batch proposed after its max sequencing time

```
zcat canton.clog.gz | grep -a -E "New epoch [0-9]+ .*size = [2-9]" | grep -a 'T20:5[3-4]' | <first per sequencer>
zcat canton.clog.gz | grep -a 'T20:5[345]' | grep -a 'SEQ::sv3::12203f7c45c1' | grep -a 'SequencerSynchronizerState' | grep -a -E 'Attempting to build|Persisted topology' | grep -a -E 'participant=sv[1-4]Participant/psid=global|sequencer=globalSequencerSv1'
```
```
2026-11-12T20:53:57.086Z New epoch 27 has started ... size = 3 sequencer=globalSequencerSv1
2026-11-12T20:54:18.031Z New epoch 27 has started ... size = 3 sequencer=globalSequencerSv4
2026-11-12T20:54:25.580Z New epoch 27 has started ... size = 3 sequencer=globalSequencerSv2
2026-11-12T20:54:26.034Z New epoch 28 has started ... size = 3 sequencer=globalSequencerSv1
2026-11-12T20:53:57.546Z Attempting to build participant=sv1Participant
2026-11-12T20:53:57.644Z Attempting to build participant=sv3Participant
2026-11-12T20:53:57.735Z Attempting to build participant=sv4Participant
2026-11-12T20:53:57.846Z Attempting to build participant=sv2Participant
2026-11-12T20:54:36.837Z Persisted sequencer=globalSequencerSv1
2026-11-12T20:55:51.682Z Attempting to build participant=sv4Participant
2026-11-12T20:55:51.920Z Persisted sequencer=globalSequencerSv1
```
Signatures of the two serial-4 states on globalSequencerSv1 (namespaces: sv1 122019f9229d, sv4 122067cbf1af, sv3
1220d1c9eda6):
```
zcat canton.clog.gz | grep -a 'T20:54:36.837Z\|T20:55:51.920Z' | grep -a 'globalSequencerSv1' | grep -a 'Persisted topology' | grep -a SequencerSynchronizerState | sed -E 's/\\n/\n/g' | grep -E 'signatures|proposal|serial'
```
```
      serial = 4,
    signatures = 1220d1c9eda6...,
    proposal
      serial = 4,
    signatures = Seq(122019f9229d..., 122067cbf1af..., 1220d1c9eda6...)
```
sv4's push (sv2's is the same, 0.1 s later):
```
zcat canton.clog.gz | awk '/T20:53:5[7-9]|T20:54:[0-3]/' | grep -a -E 'QueueBasedSynchronizerOutbox:participant=sv[24]Participant/psid=global' | grep -a -E 'Attempting to push|responded'
zcat canton.clog.gz | grep -a 'a34357d1-d124-41d2-a820-09f022391175' | grep -a -E 'max sequencing time|LocalBatchCreated'
zcat canton.clog.gz | grep -a '122078ff41712fa33c5b397c792ff7f4cdf0fb118ae4af410d728ead3df436fdd631' | grep -a -E 'LocalBatchCreated|CreateProposal'
```
```
2026-11-12T20:53:57.756Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:644115f50457...)) | sv4
2026-11-12T20:53:57.867Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:644115f50457...)) | sv2
2026-11-12T20:54:26.426Z Synchronizer 'global' responded the following for the given topology transactions: List((Failed,TxHash(SHA-256:644115f50457...))) | sv2
2026-11-12T20:54:26.441Z Synchronizer 'global' responded the following for the given topology transactions: List((Failed,TxHash(SHA-256:644115f50457...))) | sv4
2026-11-12T20:53:57.763Z Request to send submission with id a34357d1-d124-41d2-a820-09f022391175 with max sequencing time 2026-11-12T20:54:17.756104Z from PAR::sv4::122067cbf1af... to Set(All). | BlockSequencer sequencer=globalSequencerSv1
2026-11-12T20:53:57.877Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(122078ff4171...) with reference epoch 27 for batch received from local mempool containing 3 requests (... messageId=a34357d1-...
2026-11-12T20:54:26.040Z Availability.Consensus.CreateProposal: providing proposal with batch IDs List(BatchId(12206e02e844...), BatchId(1220a2b217fa...), ... (contains 122078ff4171...)
```
Epoch 27 (the first 3-node epoch) ran 20:53:57.086-20:54:26.034 on sv1, 29 s, until sv2 entered it at 20:54:25.580;
the batch built 0.8 s after its start was ordered only in epoch 28, 8 s past the max sequencing time.

## Verdict

Duplicate of 11255: the ordering stall of the epoch after a topology step (3 nodes, newcomer sv2 not yet in the
epoch) drops the sv3 sequencer-add signatures of sv2 and sv4 (wall-clock analogue of the batch-never-ordered variant;
same window as 11350, where the retry still fit). Here the joins of sv2, sv4 and sv3 through sv1 plus the slow sv3
DAR uploads leave no margin, and sv3 is ready 5 s after the test's 5 min wait.
