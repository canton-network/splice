# 11688 - simtime (3): TimeBasedTestNetPreview setup, sv3 and sv4 mediator adds never ordered at the 1 -> 4 step (run 44814088068)

DUPLICATE of 11255 (family B, batch-never-ordered variant of the rejected-send shape under simulated time, as 11326
and the 2026-11-11 hits 11358 11362 11368 11374). Post-merge main a0e7406501 (#8632), job
`ci / scala_test_sim_time / simtime (3)`, Canton 3.7.4. The first suite (GovernanceLockTimeBased, 8 tests) passed. In
the setup of the second suite, TimeBasedTestNetPreviewIntegrationTest, the BFT ordering topology of the global
synchronizer grew 1 -> 4 at 21:07:03.081 (epoch 73, no blacklisting). The four SV participants pushed their signatures
on the MediatorSynchronizerState adds of MED::sv3 and MED::sv4 between 21:07:02.376 and 21:07:03.005. globalSequencerSv1
accepted every push into its mempool; the two earliest (sv1 and sv4 for MED::sv3) were output in block 1389 at
21:07:02.866, the other four (sv2 and sv3 for MED::sv3, sv1 and sv4 for MED::sv4), accepted 78 to 370 ms before the
switch, were never ordered: their batches carry reference epoch 72 and the trace ends with `Dropping network message to
unknown SEQ::sv2/sv3/sv4 (possibly unauthenticated as of yet)` at 21:07:03.171. MED::sv3 stayed at 2 signatures, MED::sv4
at none; sv3 and sv4 waited on `Mediator has been granted unlimited traffic` from 21:07:08 until the 5 min init wait
timed out at 21:10:37.810. The next suite then failed its sv1 init wait (blast radius) and its teardown leaked the
environment (family H, 11347 shape), so the remaining tests failed at `Creating fixture` with
`Could not create Prometheus HTTP server`. Report kept: 8 succeeded, 5 failed, 1 suite aborted.

- Run: https://github.com/canton-network/splice/actions/runs/44814088068, main a0e7406501 ("Deflake the onboarding
  snapshot resume step in SvOnboardingAddlIntegrationTest (#8632)"), job 136558231592
  `ci / scala_test_sim_time / simtime (3)`.
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at a0e7406501).
- Component: Canton BFT ordering (sequencer onboarding window under sim time).

Artifact: `log/11688/logs-simtime-3/` (`gh run download 44814088068 --repo canton-network/splice -n logs-simtime-3`),
job log `log/11688/job.log` (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/136558231592/logs`),
`log/11688/job.clean.log` is the job log after `sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //'`.
In the commands `A=log/11688/logs-simtime-3`, `fmt` is the generic formatter from recipes section 4,
`log/11688/tnp.jsonl` is `zcat $A/canton_network_test.clog.gz | grep -a 'TimeBasedTestNetPreviewIntegrationTest/config='`
and `W=log/11688/canton-window.jsonl` is `zcat $A/canton-simtime.clog.gz | grep -a 'T21:0[5-9]:\|T21:10:'`.

The same run also has a failed `simtime (0)` (job 136558231666, ref 11687, also a duplicate of 11255).

## 1. Job log: five failed tests, one aborted suite

```
grep -a -E 'Suites: completed|Tests: succeeded|\*\*\* ABORTED \*\*\*|\*\*\* FAILED \*\*\*|Cause: |Run completed' log/11688/job.clean.log | cut -c1-220 | head -18
```
```
[info] - TestNet initializes correctly *** FAILED ***
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info] - contact point gets updated *** FAILED ***
[info] - lastActiveAt gets updated *** FAILED ***
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
[info]   Cause: java.net.BindException: Address already in use
[info] - unclaimed ValidatorLivenessActivityRecord contracts should be expired *** FAILED ***
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
[info]   Cause: java.net.BindException: Address already in use
[info] - amulet price votes and metrics *** FAILED ***
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
[info]   Cause: java.net.BindException: Address already in use
[info] org.lfdecentralizedtrust.splice.integration.tests.WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
[info]   Cause: java.net.BindException: Address already in use
[info] Run completed in 21 minutes, 0 seconds.
[info] Suites: completed 4, aborted 1
[info] Tests: succeeded 8, failed 5, canceled 0, ignored 0, pending 0
```
The first failure is `EnvironmentSetupException: Creating environment failed at step Running setup
(EnvironmentSetup.scala:235)` with the stack head `ConsoleMacros$utils$.retry_until_true` /
`HttpAppReference.waitForInitialization` (SpliceInstanceReference.scala:197) / `SvAppReference.waitForInitialization`
/ `EnvironmentDefinition$.waitForNodeInitialization` (EnvironmentDefinition.scala:665). `contact point gets updated`
fails with `The app state of sv1Scan is currently not accessible. Node doesn't have any app state.` from
`UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` (UpdateHistorySanityCheckPlugin.scala:58). The "custom
container" annotation is the runner's wording for a failed step, not an infra fault.

## 2. Test log: suite timeline and the init timeouts

```
zcat $A/canton_network_test.clog.gz | grep -a -E "Starting test suite|(Completed|Aborted) test suite|Timeout while waiting for initialization|Test failed: " | fmt 200 | sed -E 's/,\[.*app=(sv[0-9])\] ERROR/ [\1]/; s/",\[c.d.c.LogReporter.*//' | cut -c1-200
```
```
2026-11-20T20:54:42.311Z Starting test suite 'GovernanceLockTimeBasedIntegrationTest'...
2026-11-20T21:05:00.919Z Completed test suite 'GovernanceLockTimeBasedIntegrationTest'.
2026-11-20T21:05:00.992Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...
2026-11-20T21:10:37.810Z Timeout while waiting for initialization of sv3" [sv3]
2026-11-20T21:10:38.131Z Test failed: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:235), l
2026-11-20T21:10:38.141Z Completed test suite 'TimeBasedTestNetPreviewIntegrationTest'.
2026-11-20T21:10:38.154Z Starting test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'...
2026-11-20T21:15:39.718Z Timeout while waiting for initialization of sv1" [sv1]
2026-11-20T21:15:39.721Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/contact point gets updated', message: The app state of sv1Scan is currently not accessible. Node doesn't have an
2026-11-20T21:15:39.751Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/lastActiveAt gets updated', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scal
2026-11-20T21:15:39.786Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/unclaimed ValidatorLivenessActivityRecord contracts should be expired', message: Creating environment failed at 
2026-11-20T21:15:39.797Z Completed test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'.
2026-11-20T21:15:40.510Z Starting test suite 'SvTimeBasedAmuletPriceIntegrationTest'...
2026-11-20T21:15:40.548Z Test failed: 'SvTimeBasedAmuletPriceIntegrationTest/amulet price votes and metrics', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
2026-11-20T21:15:40.554Z Completed test suite 'SvTimeBasedAmuletPriceIntegrationTest'.
2026-11-20T21:15:40.562Z Starting test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'...
2026-11-20T21:15:40.893Z Aborted test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'.
```
The 21:10:37 timeout is the cause. The sv1 init timeout of the next suite is the setup-failure blast radius of family
B (the failed setup leaves the synchronizer as it was; sv1 of config=273e0cab logs `Success: SV1 has uploaded the
initial set of packages` at 21:10:39.173 and no later `Success` line); the three `Creating fixture` failures and the
abort are the teardown leak cascade of family H (11347 shape, section 1). They were not analysed beyond this timeline.

## 3. What sv3 and sv4 waited on

```
grep -a 'SV=sv[1-4]"' log/11688/tnp.jsonl | grep -a -E '"message":"sv[1-4] app initialization: (Initialization complete|JoiningNodeInitializer joining Dso with key (finished|failed))' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//' | cut -c1-200
grep -a 'Mediator has been granted unlimited traffic' log/11688/tnp.jsonl | grep -a -E 'Waiting until|Success' | sed -E 's/"logger_name":"[^"]*(SV=sv[0-9])".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-140
```
```
2026-11-20T21:05:44.844Z sv1 app initialization: Initialization complete, running on version 0.10.4-ci-snapshot.20261120.1.0.va0e74065-dirty
2026-11-20T21:07:26.708Z sv2 app initialization: JoiningNodeInitializer joining Dso with key finished after PT1M48.668361719S
2026-11-20T21:07:28.791Z sv2 app initialization: Initialization complete, running on version 0.10.4-ci-snapshot.20261120.1.0.va0e74065-dirty
2026-11-20T21:10:38.041Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-20T21:10:38.055Z sv4 app initialization: JoiningNodeInitializer joining Dso with key failed
```
```
2026-11-20T21:07:08.244Z Waiting until Mediator has been granted unlimited traffic",[SV=sv3]
2026-11-20T21:07:08.276Z Waiting until Mediator has been granted unlimited traffic",[SV=sv4]
2026-11-20T21:07:08.826Z Waiting until Mediator has been granted unlimited traffic",[SV=sv2]
2026-11-20T21:07:21.945Z Success: Mediator has been granted unlimited traffic",[SV=sv2]
```
sv3 and sv4 have no `Success` line. MED::sv3 and MED::sv4 never enter the mediator state:
```
grep -a 'SV=sv[34]"' log/11688/tnp.jsonl | awk '/No traffic state found for member MED::sv3/{a++} /No traffic state found for member MED::sv4/{b++} /does not yet have a traffic state, skipping/{c++} /Condition is not yet observed/{d++} END{print "noTrafficStateSv3",a+0,"noTrafficStateSv4",b+0,"skipping",c+0,"notYetObserved",d+0}'
```
```
noTrafficStateSv3 123 noTrafficStateSv4 119 skipping 791 notYetObserved 654
```
The proposals of the four SV apps (MED::sv2 was added before, `Success: Add mediator MED::sv2` at 21:07:02.907):
```
grep -a -E 'Submitted proposal MediatorSynchronizerState' log/11688/tnp.jsonl | grep -a -E 'MED::sv[34]' | sed -E 's/"logger_name":"[^"]*(SV=sv[0-9])".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/Submitted proposal MediatorSynchronizerState.*for (Add mediator MED::sv[0-9])[^"]*/Submitted proposal ... for \1 .../'
```
```
2026-11-20T21:07:02.364Z Submitted proposal ... for Add mediator MED::sv3 ...",[SV=sv1]
2026-11-20T21:07:02.463Z Submitted proposal ... for Add mediator MED::sv3 ...",[SV=sv2]
2026-11-20T21:07:02.530Z Submitted proposal ... for Add mediator MED::sv3 ...",[SV=sv4]
2026-11-20T21:07:02.790Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv4]
2026-11-20T21:07:02.855Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv1]
2026-11-20T21:07:02.949Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv2]
2026-11-20T21:07:03.006Z Submitted proposal ... for Add mediator MED::sv3 ...",[SV=sv3]
2026-11-20T21:07:03.029Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv3]
2026-11-20T21:08:56.364Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv1]
2026-11-20T21:08:57.064Z Submitted proposal ... for Add mediator MED::sv3 ...",[SV=sv2]
2026-11-20T21:09:00.120Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv2]
2026-11-20T21:09:00.317Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv4]
2026-11-20T21:09:06.719Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv3]
2026-11-20T21:09:07.462Z Submitted proposal ... for Add mediator MED::sv3 ...",[SV=sv3]
```
The resubmissions from 21:08:56 follow `check established Add mediator ... Total maximum number of retries 35
exceeded. Giving up.`; none is observed.

## 4. Confirming grep of family B: 1 -> 4 switch, batches created just before it never ordered

Ordering topology of the global synchronizer on globalSequencerSv1:
```
grep -a 'New epoch [0-9]* has started with leaders' $W | grep -a globalSequencerSv1 | grep -a 'T21:0[67]:' | fmt 9999 | sed -E 's/New epoch ([0-9]+) .*blacklisted nodes = (List\([^)]*\)).*size = ([0-9]+).*/epoch \1 blacklisted \2 size \3/' | cut -c1-120
```
```
2026-11-20T21:06:23.093Z epoch 69 blacklisted List() size 1
2026-11-20T21:06:41.392Z epoch 70 blacklisted List() size 1
2026-11-20T21:06:48.527Z epoch 71 blacklisted List() size 1
2026-11-20T21:06:55.900Z epoch 72 blacklisted List() size 1
2026-11-20T21:07:03.081Z epoch 73 blacklisted List() size 4
2026-11-20T21:07:25.062Z epoch 74 blacklisted List() size 4
2026-11-20T21:07:33.819Z epoch 75 blacklisted List() size 4
2026-11-20T21:07:43.839Z epoch 76 blacklisted List() size 4
2026-11-20T21:07:55.142Z epoch 77 blacklisted List() size 4
```
```
grep -a 'Authenticated P2P nodes count' $W | grep -a globalSequencerSv1 | fmt 200 | sed -E 's/",\[.*//; s/, dissemination and ordering.*//; s/, ordering may.*//'
```
```
2026-11-20T21:07:03.076Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2
2026-11-20T21:07:08.755Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2
2026-11-20T21:07:08.791Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3
2026-11-20T21:07:08.901Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3
2026-11-20T21:07:08.917Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3
```
Every topology push of the SV participants on global from 21:07:02.1 to the switch, with its batch on
globalSequencerSv1 and whether a block with its trace id was output (`log/11688/pushes.txt` holds timestamp, tx hash,
participant and trace id of each `Attempting to push` line):
```
grep -a 'T21:07:0[23]' $W | grep -a 'Attempting to push' | grep -a 'psid=global-domain' | sed -E 's/\{"@timestamp":"([^"]+)".*TxHash\(SHA-256:([0-9a-f]+)\.\.\.\).*participant=([a-z0-9A-Z]+)\/.*"trace-id":"([0-9a-f]+)".*/\1 \2 \3 \4/' > log/11688/pushes.txt
while read ts h p t; do out=$(grep -a "$t" $W | grep -a -m1 -o 'Block [0-9]* being output'); batch=$(grep -a "$t" $W | grep -a -m1 'LocalBatchCreated' | sed -E 's/\{"@timestamp":"[^T]*T([^"]+)".*BatchId\(1220[0-9a-f]{60}([0-9a-f]{4})\) with reference epoch ([0-9]+).*/\1 batch ..\2 epoch \3/'); echo "$ts $h $p | ${batch:-no own batch line} | ${out:-NOT OUTPUT}"; done < log/11688/pushes.txt
```
```
2026-11-20T21:07:02.179Z 77000b6b8387 sv4Participant | 21:07:02.223Z batch ..2d9e epoch 72 | Block 1388 being output
2026-11-20T21:07:02.262Z 77000b6b8387 sv3Participant | no own batch line | Block 1388 being output
2026-11-20T21:07:02.306Z 77000b6b8387 sv2Participant | no own batch line | Block 1388 being output
2026-11-20T21:07:02.376Z 6707994b55ed sv1Participant | 21:07:02.543Z batch ..8dee epoch 72 | Block 1389 being output
2026-11-20T21:07:02.588Z 6707994b55ed sv4Participant | 21:07:02.653Z batch ..6972 epoch 72 | Block 1389 being output
2026-11-20T21:07:02.703Z 6707994b55ed sv2Participant | 21:07:02.863Z batch ..68a5 epoch 72 | NOT OUTPUT
2026-11-20T21:07:02.928Z 3b9da18bfa22 sv1Participant | 21:07:02.977Z batch ..1a6e epoch 72 | NOT OUTPUT
2026-11-20T21:07:02.974Z 3b9da18bfa22 sv4Participant | no own batch line | NOT OUTPUT
2026-11-20T21:07:03.005Z 6707994b55ed sv3Participant | no own batch line | NOT OUTPUT
```
6707994b55ed is the MED::sv3 add and 3b9da18bfa22 the MED::sv4 add, both proposals for serial 3 (77000b6b8387 is
the MED::sv2 add, established 21:07:02.907):
```
grep -a -E 'hash = SHA-256:(6707994b55ed|3b9da18bfa22)' $W | grep -a 'Adding StoredTopologyTransaction' | grep -a 'participant=sv1Participant' | sed -E 's/\{"@timestamp":"([^"]+)".*threshold = ([0-9]+),\\n +active = Seq\(([^)]*)\)\\n +\),\\n +serial = ([0-9]+).*hash = SHA-256:([0-9a-f]+).*/\1 hash \5 serial \4 threshold \2 active \3/; s/::1220[0-9a-f]+\.\.\.//g' | head -2
```
```
2026-11-20T21:07:02.354Z hash 6707994b55ed serial 3 threshold 1 active MED::sv1, MED::sv2, MED::sv3
2026-11-20T21:07:02.852Z hash 3b9da18bfa22 serial 3 threshold 1 active MED::sv1, MED::sv2, MED::sv4
```
The four pushes
from 21:07:02.703 on were all accepted (`Mempool accepting client request with tag 'send'` at 21:07:02.714, .943, .984
and 21:07:03.026, `SendAsync ... succeeded(OK)`), 370 to 55 ms before the switch, and never validated:
```
for m in d3410c1b-25a1-46e9-9f1c-77e603eb55b6 5c4b5d10-cfd0-449a-9cb8-185e6ca7b09e 7db57379-fc53-475e-ad9e-4d2820b113b2 f010e941-067b-442b-83c1-1f7e5aafe505 8755dcdf-fd3c-4672-ac58-aefa1dac11a2; do echo "$m lines=$(grep -a -c $m $W) validatedTo=$(grep -a $m $W | grep -a -c 'validated to')"; done
```
```
d3410c1b-25a1-46e9-9f1c-77e603eb55b6 lines=27 validatedTo=4
5c4b5d10-cfd0-449a-9cb8-185e6ca7b09e lines=12 validatedTo=0
7db57379-fc53-475e-ad9e-4d2820b113b2 lines=12 validatedTo=0
f010e941-067b-442b-83c1-1f7e5aafe505 lines=12 validatedTo=0
8755dcdf-fd3c-4672-ac58-aefa1dac11a2 lines=12 validatedTo=0
```
(d3410c1b is the sv1 push of 21:07:02.376 for comparison, `validated to: Deliver` at block 1389; the other four are
the message ids of the pushes of 21:07:02.703, .928, .974 and 21:07:03.005, in that order.) The last lines of the
trace of the sv2 push (the sv1 push of 21:07:02.928 ends the same way at 21:07:03.171):
```
t=$(sed -n 6p log/11688/pushes.txt | cut -d' ' -f4); grep -a $t $W | fmt 200 | grep -a -E 'LocalBatchCreated|Dropping' | sed -n '1,4p'
```
```
2026-11-20T21:07:02.863Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..68a5) with reference epoch 72 for batch received from local mempool containing 1 requests (tag=se
2026-11-20T21:07:03.172Z Dropping network message to unknown SEQ::sv2::..c2ab (possibly unauthenticated as of yet)",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-do
2026-11-20T21:07:03.172Z Dropping network message to unknown SEQ::sv3::..214f (possibly unauthenticated as of yet)",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-do
2026-11-20T21:07:03.172Z Dropping network message to unknown SEQ::sv4::..d250 (possibly unauthenticated as of yet)",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-do
```
So the MED::sv3 add has the sequenced signatures of sv1 and sv4 only and the MED::sv4 add has none. The requests have
`max sequencing time 1970-01-01T00:15:03Z` against a sim clock at 00:14:43, which the suite never reaches, so the
participants keep the flush open (`Another flush cycle is currently ongoing`) and the re-proposals of section 3 are
not pushed.

Ruling out the lost-verdict and blacklisting shapes; the only synchronous rejections in the window are sv1's own
`SetTrafficPurchased` submissions for MED::sv2, which were retried and succeeded (section 3, 21:07:21.945):
```
awk '/currently blacklisted/{b++} /Failed to send result/{f++} /Another flush cycle is currently ongoing/{a++} /authenticated = 1 < dissemination quorum = 2/{q++} /Rejecting submission request by/{r++} /Rejecting submission request \(/{s++} /Dropping network message to unknown/{d++} END{print "blacklisted",b+0,"failedSendResult",f+0,"anotherFlush",a+0,"quorumText",q+0,"rejectedBy",r+0,"rejectedSynchronously",s+0,"droppingUnknown",d+0}' $W
grep -a 'authenticated = 1 < dissemination quorum = 2' $W | sed -E 's/.*"logger_name":"c\.d\.c\.[a-z.]*\.([A-Za-z]+):.*/\1/' | sort | uniq -c
```
```
blacklisted 0 failedSendResult 0 anotherFlush 29 quorumText 30 rejectedBy 0 rejectedSynchronously 6 droppingUnknown 39
      6 ApiRequestLogger
      6 BlockSequencer
      6 MempoolModule
      6 SequencerClientImplPekko
      6 TrafficPurchasedSubmissionHandler
```
(the 6 `SetTrafficPurchasedRequest(MED::sv2..., 1, ...)` calls on globalSequencerSv1 between 21:07:03.083 and
21:07:07.851 each fail with `TRAFFIC_CONTROL_TOP_UP_SUBMISSION_FAILED ... P2P connectivity is not ready`; the seventh
at 21:07:11.344 succeeds.)

## Verdict

Duplicate of 11255 ("Timeouts due to changing bft ordering topology", open, assignee rautenrieth-da), family B under
simulated time, batch-never-ordered variant (11326; 11358 11362 11368 11374), here in the TimeBasedTestNetPreview
setup (as 11337, 11399, 11409, 11410) and hitting two joining mediators at once. Flake, cause in Canton BFT ordering:
requests accepted into the mempool of the only sequencer just before the ordering topology grows 1 -> 4 are batched
for the old epoch and then never ordered while the newcomers are unauthenticated, and the participant does not
resend a topology broadcast before its max sequencing time, which the sim clock never reaches. The splice-side
mitigation is splice#8363 ("Onboard SV sequencers one at a time in tests"), still open on 2026-11-22 (last update
2026-11-09). No fix branch: the mitigation already exists as an open PR and the cause is not in splice.

Not verified: the signature threshold the two serial-3 proposals needed (only that neither was established); what the BFT
availability module did with the four batches after 21:07:03.172 (no later line carries their trace ids); the
follow-on failures (sv1 init timeout of ValidatorLicenseMetadataTimeBased, the leaked environment) beyond their
timeline.
