# 11371 11372 - RollForwardLsuDR: pre-DR SV onboarding misses the 300 s sequencing cutoff again (runs 43072152132, 43073759004)

DUPLICATE of 11303 (family AK). Both are post-merge main runs on 2026-11-11, job `roll-forward-lsu (0)`, tests
succeeded 0, failed 1, then the suite ABORTED in teardown (`ShutdownFailedException: Unable to close ...
SpliceEnvironment`, in-flight join commands of the never-initialized SVs). `RollForwardLsuDRIntegrationTest` fails in
its first `startAllSync` (clue "Start nodes before DR", line 225): the 5 min wait is `waitForInitialization` of one
app, which never finishes because the pre-DR synchronizer stops sequencing at suite start plus 300 s
(`lsu-repair.global-max-sequencing-time-exclusive`, test line 89) while a joining SV still has join steps to sequence.
- 11371 (08774bc437, Canton 3.7.3-snapshot.20261109): the 11312/11328 shape. Ordering topology 3 to 4 sequencers at
  18:09:28.7, 9 s before the bound 18:09:37.796976; sv3 sends its last join step `DsoRules_SetSynchronizerNodeConfig`
  at 18:09:42.180, after the bound; every send after the bound is refused with `SEQUENCING_TIME_NOT_ADMISSIBLE`; the
  test times out on `sv3` (SvAppReference).
- 11372 (915616a77d, Canton 3.7.3-snapshot.20261111): new variant of the same budget. The joins of sv2, sv3, sv4 run
  in parallel and late (sv2 gets `SvOnboardingConfirmed` only at 18:20:02). At 18:20:27.66 the ordering topology goes 1
  to 2 sequencers (sv1, sv3; strongQuorum 2), but the sv3 BFT sequencer only starts its onboarding state transfer at
  18:21:00.7, so epoch 40 cannot order anything from 18:20:29 to 18:21:08. sv2 sends `add sv to Dso` at 18:20:30.85,
  it is batched into epoch 40 and refused at 18:21:08.737 (bound 18:20:56.897578). The test times out on `sv2Scan`
  (ScanAppReference), which waits for the DSO party in the user metadata of sv2.
Not hard broken: roll-forward-lsu (0) failed in 4 of 34 jobs on the 32 post-merge main runs of 2026-11-10/11 (11328,
11336, 11371, 11372); 3b5b82822d (#8432), 1ee8c88066 and ee8772cc0b (Canton bump) passed, and so did the next run
d356bcf573. #8432 is not involved: no NO_TEMPLATES_FOR_PACKAGE_NAME_AND_QUALIFIED_NAME and no WARN/ERROR from its
triggers in either run.

- Run (11371): https://github.com/canton-network/splice/actions/runs/43072152132, main 08774bc437 ("Add missing release
  notes for the TBAR v1.1 SV app and UI changes (#8442)"), job 130703038018 `roll-forward-lsu (0)`, attempt 1.
- Run (11372): https://github.com/canton-network/splice/actions/runs/43073759004, main 915616a77d ("Allow 80 s for the
  claimed SV reward coupons check (#8413)"), job 130709572782 `roll-forward-lsu (0)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (11371), 3.7.3-snapshot.20261111.20904.0.vbe797d9d
  (11372, after #8441).
- Component: test time budget (pre-DR sequencing cutoff); DA BFT sequencer onboarding delays as the trigger.

Job ids were given in the round brief (job logs `log/inbox-1111b/<job id>.log`, ANSI stripped); the per-run scan in
section 8 lists the same ids as the only `roll-forward-lsu (0)` job of each run. Artifacts:
`log/<ref>/logs-roll-forward-lsu-0/` (artifact `logs-roll-forward-lsu-0`, created 2026-11-11T18:15:18Z for 11371 and
2026-11-11T18:25:05Z for 11372). `D=log/<ref>/logs-roll-forward-lsu-0`,
`F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/RollForwardLsuDRIntegrationTest\/config=[0-9a-f]+\///'`.

## 1. Failing assertion, Canton pin

```
for J in 130703038018 130709572782; do echo "=== $J"; grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|ABORTED|Run completed|##\[error\]' log/inbox-1111b/$J.log | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-300; done
```
```
=== 130703038018
...
[info]  *** ABORTED ***
[info] *** 1 SUITE ABORTED ***
[info] *** 1 TEST FAILED ***
[info] - roll forward LSU DR *** FAILED ***
[info] Run completed in 10 minutes, 25 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
[info] org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest *** ABORTED ***
=== 130709572782
...
[info] - roll forward LSU DR *** FAILED ***
[info] Run completed in 8 minutes, 56 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
```

```
for J in 130703038018 130709572782; do echo "=== $J"; grep -a -B3 -A25 'FAILED \*\*\*' log/inbox-1111b/$J.log | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | cut -c1-300 | head -60; done
```
```
=== 130703038018
[info] - roll forward LSU DR *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$startAllSync$2(SpliceTests.scala:448)
...
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.$anonfun$new$3(RollForwardLsuDRIntegrationTest.scala:225)
=== 130709572782
[info] - roll forward LSU DR *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.ScanAppReference.waitForInitialization(ScanAppReference.scala:78)
...
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.$anonfun$new$3(RollForwardLsuDRIntegrationTest.scala:225)
```

The abort is the teardown after the failure:
```
for J in 130703038018 130709572782; do echo "== $J"; grep -a -m3 -E 'ShutdownFailedException|did not complete within' log/inbox-1111b/$J.log | sed -E 's/^[^Z]*Z //' | cut -c1-220; done
```
```
== 130703038018
[info]   com.digitalasset.canton.lifecycle.ShutdownFailedException: Unable to close 'org.lfdecentralizedtrust.splice.environment.SpliceEnvironment@7a166d52'.
...
== 130709572782
[info]   com.digitalasset.canton.lifecycle.ShutdownFailedException: Unable to close 'org.lfdecentralizedtrust.splice.environment.SpliceEnvironment@68ffaf8b'.
```

```
for s in 08774bc 915616a; do git show $s:nix/canton-sources.json | grep -m1 version; done
```
```
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
  "version": "3.7.3-snapshot.20261111.20904.0.vbe797d9d",
```

Test source (last change #8234; line 89 is the bound, line 225 the failing `startAllSync`):
```
git show 915616a:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala | sed -n 88,89p
git log --format='%h %ad %an %s' --date=short -5 915616a -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala
```
```
  // This needs to be long enough in the future that we can start Canton and initialize for SVs.
  val maxSequencingTime = CantonTimestamp.now().plusSeconds(300)
b77153c658 2026-11-06 Robert Autenrieth Use DA BFT in all tests (#8234)
a82a341201 2026-10-27 Martin Florian Disable key export from participants by default (#7977)
12f28d5707 2026-10-23 Pawel Perek Raise max sequencing time in RollForwardLsuDRIntegrationTest to 5mins (#7921)
546ee3d36c 2026-10-22 moritzkiefer-da Remove protocol version local configuration flag (#7896)
aee8ea3da1 2026-10-20 Julien Tinguely Enable pv 37 alpha version (#7756)
```
(The author name in the third line is printed with a non-ASCII letter by git; written here in ASCII.)

## 2. Suite timeline and the bound

```
for R in 11371 11372; do D=log/$R/logs-roll-forward-lsu-0; echo "=== $R"; zcat $D/canton_network_test.clog.gz | grep -a -E 'Starting test suite|Test (succeeded|failed): |Starting .clue|Failed clue|Finished clue|max-sequencing-time|Waiting for .* to initialize' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | head -30; done
for R in 11371 11372; do zcat log/$R/logs-roll-forward-lsu-0/canton_network_test.clog.gz | grep -a -m1 -o 'global-max-sequencing-time-exclusive=[^ ,)]*'; done
```
```
=== 11371
2026-11-11T18:04:37.833Z Starting test suite 'RollForwardLsuDRIntegrationTest'...",
2026-11-11T18:04:46.940Z Running clue: Starting external Canton process roll-forward-lsu-before-dr with List(canton.sequencers.sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-e
2026-11-11T18:14:27.505Z Failed clue: Start nodes before DR",
2026-11-11T18:14:33.660Z Test failed: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR', message: Condition never became true within 5 minutes, location: SeeStackDepthException",
=== 11372
2026-11-11T18:15:56.930Z Starting test suite 'RollForwardLsuDRIntegrationTest'...",
2026-11-11T18:16:06.311Z Running clue: Starting external Canton process roll-forward-lsu-before-dr with List(canton.sequencers.sv1StandaloneSequencer.parameters.lsu-repair.global-max-sequencing-time-e
2026-11-11T18:23:57.219Z Failed clue: Start nodes before DR",
2026-11-11T18:24:00.542Z Test failed: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR', message: Condition never became true within 5 minutes, location: SeeStackDepthException",
global-max-sequencing-time-exclusive=2026-11-11T18:09:37.796976Z
global-max-sequencing-time-exclusive=2026-11-11T18:20:56.897578Z
```

Which app timed out:
```
for R in 11371 11372; do zcat log/$R/logs-roll-forward-lsu-0/canton_network_test.clog.gz | grep -a -E '"level":"ERROR"' | sed -E "$F" | cut -c1-200 | head -3; done
```
```
2026-11-11T18:14:27.504Z Timeout while waiting for initialization of sv3",[o.l.s.c.SvAppBackendReference:app=sv3] ERROR
2026-11-11T18:14:27.505Z Failed clue: Start nodes before DR",[o.l.s.i.t.RollForwardLsuDRIntegrationTest:RollForwardLsuDRIntegrationTest] ERROR
...
2026-11-11T18:22:20.793Z Gave up waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::1220f21b88c3..., mediatorId = MED::sv4::1220bef26b0c..., s
2026-11-11T18:23:57.218Z Timeout while waiting for initialization of sv2Scan",[o.l.s.c.ScanAppBackendReference:app=sv2Scan] ERROR
```

Last init phase per app (`init-phases.txt` = all `app initialization: ... (started|finished)` and
`Initialization complete` lines, formatted with `$F`):
```
for n in 'SV=sv1\]' 'SV=sv2\]' 'SV=sv3\]' 'SV=sv4\]' 'scan=sv1Scan' 'scan=sv2Scan' 'scan=sv3Scan' 'scan=sv4Scan' 'aliceValidator'; do grep -E "$n" log/$R/init-phases.txt | tail -1; done
```
```
=== 11371
2026-11-11T18:06:52.215Z sv1 app initialization: Initialization complete, running on version 0.10.2-ci-snapshot.20261111.1.0.v08774bc4-dirty",[o.l.s.s.SvApp:SV=sv1] INFO
2026-11-11T18:09:23.860Z sv2 app initialization: Initialization complete, ...
2026-11-11T18:05:32.174Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:SV=sv3] DEBUG
2026-11-11T18:09:05.326Z sv4 app initialization: Initialization complete, ...
(all four scans and aliceValidator: Initialization complete, last at 18:09:11.108)
=== 11372
2026-11-11T18:17:46.699Z sv1 app initialization: Initialization complete, running on version 0.10.2-ci-snapshot.20261111.1.0.v915616a7-dirty",[o.l.s.s.SvApp:SV=sv1] INFO
2026-11-11T18:16:43.211Z sv2 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:SV=sv2] DEBUG
2026-11-11T18:16:43.211Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:SV=sv3] DEBUG
2026-11-11T18:16:43.211Z sv4 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:SV=sv4] DEBUG
2026-11-11T18:17:47.549Z sv1Scan app initialization: Initialization complete, ...
2026-11-11T18:18:00.610Z sv2Scan app initialization: Get DSO party from user metadata started",[o.l.s.s.ScanApp:scan=sv2Scan] DEBUG
2026-11-11T18:19:49.021Z sv3Scan app initialization: Initialization complete, ...
2026-11-11T18:20:00.692Z sv4Scan app initialization: Initialization complete, ...
2026-11-11T18:18:52.943Z aliceValidator app initialization: Initialization complete, ...
```
`startAllSync` waits in list order (scans first, then SVs), so 11372 reports sv2Scan, which waits for the DSO party
annotation that sv2 writes late in its join; sv2, sv3 and sv4 never finished either.

## 3. AK confirming grep: refusals after the bound (both runs)

```
for R in 11371 11372; do D=log/$R/logs-roll-forward-lsu-0; echo "=== $R"; for f in canton-standalone-roll-forward-lsu-before-dr.clog.gz canton_network_test.clog.gz canton.clog.gz; do echo "-- $f"; zcat $D/$f | grep -a -c 'SEQUENCING_TIME_NOT_ADMISSIBLE'; zcat $D/$f | grep -a -m1 'SEQUENCING_TIME_NOT_ADMISSIBLE' | sed -E "$F" | cut -c1-300; done; done
```
```
=== 11371
-- canton-standalone-roll-forward-lsu-before-dr.clog.gz
1584
2026-11-11T18:09:37.941Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,ce4adbbb): The sequencing time [2026-11-11T18:09:37.826461Z] is not admissible (sequencing time is at or after the upper bound (2026-11-11T18:09:37.796976Z)) for Send(...
-- canton_network_test.clog.gz
0
-- canton.clog.gz
0
=== 11372
-- canton-standalone-roll-forward-lsu-before-dr.clog.gz
86
2026-11-11T18:21:08.732Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,8719474c): The sequencing time [2026-11-11T18:21:08.280992Z] is not admissible (sequencing time is at or after the upper bound (2026-11-11T18:20:56.897578Z)) for Send(...
-- canton_network_test.clog.gz
0
-- canton.clog.gz
0
```

## 4. 11371: sv3 sends its last join step after the bound (11312/11328 shape)

Ordering topology growth (first epoch per activation, sv1 sequencer):
```
zcat log/11371/logs-roll-forward-lsu-0/canton-standalone-roll-forward-lsu-before-dr.clog.gz | grep -a -E 'New epoch [0-9]+ has started' | grep -a sv1StandaloneSequencer | sed -E 's/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+).*activationTime = ([^,]*),\\n  size = ([0-9]+).*/\1 epoch \2 activation \3 size \4/' | awk '!seen[$7]++'
```
```
2026-11-11T18:05:50.625Z epoch 0 activation 0001-01-01T00:00:00.250002Z size 1
2026-11-11T18:08:51.885Z epoch 45 activation 2026-11-11T18:08:51.555432Z size 2
2026-11-11T18:09:10.389Z epoch 48 activation 2026-11-11T18:09:10.044368Z size 3
2026-11-11T18:09:29.006Z epoch 51 activation 2026-11-11T18:09:28.709971Z size 4
```

sv3 join, last steps (INFO, retries and noise filtered):
```
zcat $D/canton_network_test.clog.gz | grep -a 'SV=sv3' | grep -a 'JoiningNodeInitializer\|SetSynchronizerNodeConfig\|OnboardedAfterDelay\|Initialization failed' | grep -a -E '"level":"(INFO|WARN|ERROR)"' | sed -E "$F" | awk '$1>="2026-11-11T18:09:07.18"' | ...
```
```
2026-11-11T18:09:08.421Z Got 'SV participant PAR::sv3::12204c3a1b41... has Submission rights and is not onboarding for party DSO-13fa9bd5-13fa9bd5::1220b87abd73...': TopologyResult(...
2026-11-11T18:09:42.077Z Attempting to get 'SV nodes have been granted unlimited traffic'",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv3] INFO
2026-11-11T18:09:42.083Z Got 'SV nodes have been granted unlimited traffic': ()",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv3] INFO
2026-11-11T18:14:51.431Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed",[o.l.s.s.SvApp:SV=sv3] INFO
```
The step in between (sv3 mediator initialization) ends at 18:09:42.069 (`InitializeMediatorResponse()`, then
`Success: mediator observes itself as onboarded` 18:09:42.076). The next submission:
```
zcat $D/canton_network_test.clog.gz | grep -a 'b0128c6ba1883de107f6dcb60c6a2ed7' | sed -E "$F" | sed 's/\\n/ /g; s/  */ /g' | cut -c1-900
zcat $D/canton.clog.gz | grep -a 'e91fe8c1-27e1-4326-af10-0e6b33ae641d' | grep -a -E 'Phase 1 completed|REQUEST_TIME_OUT' | sed -E "$F" | cut -c1-260
```
```
2026-11-11T18:09:42.180Z Request (tid:b0128c6ba1883de107f6dcb60c6a2ed7) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: sending request SubmitAndWaitForTransactionRequest( Commands( \"\", sv3-13fa9bd5, e91fe8c1-27e1-4326-af10-0e6b33ae641d, Command( Exercise( ExerciseCommand( Identifier(#splice-dso-governance, Splice.DsoRules, DsoRules), 00e4...e178, DsoRules_SetSynchronizerNodeConfig, ...
2026-11-11T18:11:42.178Z Request (tid:b0128c6ba1883de107f6dcb60c6a2ed7) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999793077s. ...
2026-11-11T18:09:42.241Z Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-eng-3-13fa9bd5::12204c3a1b41..., command-id e91fe8c1-27e1-4326-af10-0e6b33ae641d",[c.d.c.p.p.TransactionProcessor:participant=sv3Participant/ps
2026-11-11T18:11:42.181Z REQUEST_TIME_OUT(3,5ef77e05): Timed out while awaiting for a completion corresponding to a command submission with command-id=e91fe8c1-27e1-4326-af10-0e6b33ae641d and submission-id=5ef77e05-6cc3-4c69-8b16-41da9e8794b0.",...
```
The send happens 4.4 s after the bound; all senders (PAR, MED, SEQ of all four SVs) are refused from 18:09:37.941
to 18:14:26.831:
```
zcat $S | grep -a 'SEQUENCING_TIME_NOT_ADMISSIBLE' | grep -a -o 'SubmissionRequest([A-Z]*::sv[0-9]' | sort | uniq -c
```
```
    232 SubmissionRequest(MED::sv1
    232 SubmissionRequest(MED::sv2
    224 SubmissionRequest(MED::sv3
    232 SubmissionRequest(MED::sv4
    240 SubmissionRequest(PAR::sv1
    248 SubmissionRequest(PAR::sv2
    272 SubmissionRequest(PAR::sv3
    240 SubmissionRequest(PAR::sv4
    232 SubmissionRequest(SEQ::sv1
    232 SubmissionRequest(SEQ::sv2
    216 SubmissionRequest(SEQ::sv3
    232 SubmissionRequest(SEQ::sv4
```

## 5. 11372: epoch 40 of the 2-sequencer topology stalls across the bound

sv2 joins late (sponsor sv1 ready 18:17:46; DSO party id 18:17:50; vetting 18:17:56 to 18:19:04; onboarding
confirmed 18:20:02):
```
zcat $D/canton_network_test.clog.gz | grep -a "SV=sv2" | grep -a 'JoiningNodeInitializer\|JoiningNodeDsoPartyHosting\|NodeInitializer:\|Packages vetting\|upload dar' | grep -a -E '"level":"(INFO|WARN|ERROR)"' | sed -E "$F" | grep -v -E 'Now retrying|has failed with an exception|Detected an error|failed with a retryable' | cut -c1-200 | awk '{k=substr($0,26,40); if (k!=last) print; last=k}'
```
```
2026-11-11T18:16:43.260Z Attempting to get 'DSO party ID from sponsoring SV'",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv2] INFO
2026-11-11T18:17:50.259Z Got 'DSO party ID from sponsoring SV': DSO-82e4eaa-82e4eaa::1220f21b88c3...",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv2] INFO
2026-11-11T18:17:56.321Z Vetting packages based on state from sponsor",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv2] INFO
2026-11-11T18:19:04.048Z Packages vetting completed",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv2] INFO
2026-11-11T18:19:04.858Z Requesting to be onboarded via the sponsor SV",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv2] INFO
2026-11-11T18:20:02.338Z Got 'SvOnboardingConfirmed contract for digital-asset-eng-2-82e4eaa::122001c3fde7...': Contract(\n  contractId = 00eba15f...
2026-11-11T18:20:27.870Z DSO party is now hosted in the candidate SV participant PAR::sv2::122001c3fde7...",[o.l.s.s.o.j.JoiningNodeDsoPartyHosting:SV=sv2] INFO
2026-11-11T18:20:28.048Z granted sv2-82e4eaa readAs rights for dsoParty",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv2] INFO
2026-11-11T18:22:30.239Z The operation 'add sv to Dso' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999853346s. ...
2026-11-11T18:24:30.448Z sv2 app initialization: JoiningNodeInitializer joining Dso with key failed",[o.l.s.s.SvApp:SV=sv2] INFO
```

sv2 `add sv to Dso` (command e5b94690, message 6b4a6fea) from participant to sequencer:
```
zcat $D/canton.clog.gz | grep -a 'e5b94690-8147-4b71-bc34-d7c95f23ac13' | sed -E "$F" | cut -c1-300
zcat $D/canton-standalone-roll-forward-lsu-before-dr.clog.gz | grep -a '6b4a6fea-5ef2-45b4-a805-0b46edd06215' | sed -E "$F" | cut -c1-330
```
```
2026-11-11T18:20:30.790Z Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-eng-2-82e4eaa::122001c3fde7..., command-id e5b94690-8147-4b71-bc34-d7c95f23ac13",[c.d.c.p.p.TransactionProcessor:participant=sv2Participant/psid=global-domain::1220f21b88c3::37-0] INF
2026-11-11T18:22:30.270Z REQUEST_TIME_OUT(3,58876d88): Timed out while awaiting for a completion corresponding to a command submission with command-id=e5b94690-8147-4b71-bc34-d7c95f23ac13 ...
2026-11-11T18:20:30.850Z 'PAR::sv2::122001c3fde7...' sends request with id '6b4a6fea-5ef2-45b4-a805-0b46edd06215' of size 6985 bytes with 3 envelopes to MGR=1, PAR=4.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=sv1StandaloneSequencer] INFO
2026-11-11T18:20:30.870Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..d9f1) with reference epoch 40 for batch received from local mempool containing 3 requests ...
2026-11-11T18:21:08.737Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,ce5a6b22): The sequencing time [2026-11-11T18:21:08.280998Z] is not admissible (sequencing time is at or after the upper bound (2026-11-11T18:20:56.897578Z)) for Send(2026-11-11T18:21:08.280998Z,SignedContent(SubmissionRequest(PAR::sv2::122001c3fde7...,6b4a6fea-5ef2-45b4-...
```
Sent 26 s before the bound, sequencing time 11.4 s after it.

Why epoch 40 did not order (epoch log of both pre-DR BFT sequencers):
```
zcat $D/canton-standalone-roll-forward-lsu-before-dr.clog.gz | grep -a -E 'New epoch [0-9]+ has started|Completed epoch [0-9]+, but no new|Waiting for new membership' | sed -E "$F" | sed -E 's/ordering topology = OrderingTopology\(\\n  activationTime = ([^,]*),\\n  size = ([0-9]+).*/activation=\1 size=\2/' | cut -c1-230 | awk '$1>="2026-11-11T18:19:40"'
zcat $D/canton-standalone-roll-forward-lsu-before-dr.clog.gz | grep -a -m1 'New epoch 40 has started' | grep -a -o 'size = [0-9]*,\\n  weakQuorum = [0-9]*,\\n  strongQuorum = [0-9]*'
```
```
2026-11-11T18:20:19.199Z New epoch 39 has started with leaders = List(SEQ::sv1::..f6cc)and blacklisted nodes = List(); activation=2026-11-11T18:20:17.938467Z size=1
2026-11-11T18:20:28.082Z Completed epoch 39, but no new epoch topology is available yet",[c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=sv1StandaloneSequencer/psid=global-domain::1220f21b88c3::37-0] INFO
2026-11-11T18:20:29.146Z New epoch 40 has started with leaders = List(SEQ::sv1::..f6cc, SEQ::sv3::..7f97)and blacklisted nodes = List(); activation=2026-11-11T18:20:27.657687Z size=2
2026-11-11T18:21:08.085Z New epoch 40 has started with leaders = List(SEQ::sv1::..f6cc, SEQ::sv3::..7f97)and blacklisted nodes = List(); activation=2026-11-11T18:20:27.657687Z size=2
2026-11-11T18:21:08.620Z Completed epoch 40, but no new epoch topology is available yet",[c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=sv1StandaloneSequencer/psid=global-domain::1220f21b88c3::37-0] INFO
size = 2,\n  weakQuorum = 1,\n  strongQuorum = 2
```
The second `New epoch 40` line is sv3's. sv3's BFT sequencer during the stall (INFO/WARN, deduplicated):
```
zcat $D/canton-standalone-roll-forward-lsu-before-dr.clog.gz | awk -F'"' '$4>="2026-11-11T18:20:29" && $4<"2026-11-11T18:21:09"' | grep -a -i -E 'state transfer|catch.?up|view change|P2P connectivity|not ready|timeout|Started view|Completed view' | grep -a -E '"level":"(INFO|WARN)"' | sed -E "$F" | cut -c1-260 | awk '{k=substr($0,26,60); if (k!=last) print; last=k}'
```
```
2026-11-11T18:20:34.173Z Segment 0 starting at block number 630 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3::..7f97.",...
2026-11-11T18:20:34.187Z Segment 1 starting at block number 631 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1::..f6cc.",...
2026-11-11T18:20:44.731Z Database: jdbc:postgresql://localhost:5432/sequencer_sv3_lsu_drbeforedr_bft?loginTimeout=5&tcpKeepAlive=true (PostgreSQL 18.6)",[o.flywaydb.core.FlywayExecutor] INFO
2026-11-11T18:21:00.720Z Starting Onboarding state transfer from epoch 39",[c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=sv3StandaloneSequencer/psid=global-domain::1220f21b88c3::37-0] INFO
2026-11-11T18:21:08.081Z Completed state transfer, new epoch is 40, completing init",[c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=sv3StandaloneSequencer/psid=global-domain::1220f21b88c3::37-0] INFO
2026-11-11T18:21:08.181Z View change completed; metadata: (epochNumber=40, blockNumber=630), view: 1",[c.d.c.s.s.b.b.c.m.c.i.IssSegmentModule:sequencer=sv3StandaloneSequencer/psid=global-domain::1220f21b88c3::37-0] INFO
```
So the 2-node ordering topology (strong quorum 2) is active from 18:20:27.66, but sv3's BFT node only starts its
onboarding state transfer at 18:21:00.7 and joins epoch 40 at 18:21:08; nothing is ordered in between, and the first
blocks after it get sequencing times after the bound. sv3's SV app started that sequencer onboarding at 18:20:17
(`Onboarding sequencer SEQ::sv3::122050582f3a... through sponsoring SV`, `Adding sequencer` 18:20:17.910). Refusals
in this run (all after 18:21:08.732, last 18:21:13.464):
```
    16 SubmissionRequest(MED::sv1
    24 SubmissionRequest(PAR::sv1
    24 SubmissionRequest(PAR::sv2
    28 SubmissionRequest(PAR::sv3
    24 SubmissionRequest(PAR::sv4
    16 SubmissionRequest(SEQ::sv1
```

## 6. #8432 and other suspects: not involved

```
for R in 11371 11372; do D=log/$R/logs-roll-forward-lsu-0; echo "== $R"; for f in canton_network_test.clog.gz canton.clog.gz canton-standalone-roll-forward-lsu-before-dr.clog.gz; do printf '%s NO_TEMPLATES=%s FeaturedAppConfigUpdate=%s\n' $f "$(zcat $D/$f | grep -a -c NO_TEMPLATES_FOR_PACKAGE_NAME_AND_QUALIFIED_NAME)" "$(zcat $D/$f | grep -a -c -i 'FeaturedAppConfigUpdate')"; done; done
for R in 11371 11372; do printf '%s warn/error lines from FeaturedAppConfigUpdateInstructionTrigger: ' $R; zcat log/$R/logs-roll-forward-lsu-0/canton_network_test.clog.gz | grep -a 'FeaturedAppConfigUpdateInstructionTrigger' | grep -a -c -E '"level":"(WARN|ERROR)"'; done
```
```
== 11371
canton_network_test.clog.gz NO_TEMPLATES=0 FeaturedAppConfigUpdate=60
canton.clog.gz NO_TEMPLATES=0 FeaturedAppConfigUpdate=16
canton-standalone-roll-forward-lsu-before-dr.clog.gz NO_TEMPLATES=0 FeaturedAppConfigUpdate=0
== 11372
canton_network_test.clog.gz NO_TEMPLATES=0 FeaturedAppConfigUpdate=28
canton.clog.gz NO_TEMPLATES=0 FeaturedAppConfigUpdate=16
canton-standalone-roll-forward-lsu-before-dr.clog.gz NO_TEMPLATES=0 FeaturedAppConfigUpdate=0
11371 warn/error lines from FeaturedAppConfigUpdateInstructionTrigger: 0
11372 warn/error lines from FeaturedAppConfigUpdateInstructionTrigger: 0
```
The FeaturedAppConfigUpdate hits are trigger start-up (`Starting trigger ...ExpireFeaturedAppConfigUpdateInstructionTrigger`, DEBUG),
retry-provider reader names and ACS stream event formats. The only ERRORs in the test log are the timeouts of section 2.
Also, 3b5b82822d (#8432) itself and the later 1ee8c88066 and ee8772cc0b passed roll-forward-lsu (0) (section 8).

## 7. Duplicate search

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "roll-forward-lsu" created:>=2026-11-04' -f per_page=50 --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:16]) \(.title[0:120])"'
gh api repos/DACH-NY/cn-test-failures/issues/11303 --jq '"assignees=\([.assignees[].login]|join(",")) comments=\(.comments)"'
```
```
#11372 open/- 2026-11-11T18:25 GHA Run 43073759004 : job roll-forward-lsu (0) in scala_test Failed :fire:
#11371 open/- 2026-11-11T18:15 GHA Run 43072152132 : job roll-forward-lsu (0) in scala_test Failed :fire:
#11303 open/- 2026-11-09T15:28 RollForwardLsuDRIntegrationTest: pre-DR SV onboarding misses the 300 s sequencing cutoff since DA BFT
#11336 closed/duplicate 2026-11-10T10:51 GHA Run 42799831067 : job roll-forward-lsu (0) in scala_test Failed :fire:
#11328 closed/duplicate 2026-11-10T09:49 GHA Run 42790498517 : job roll-forward-lsu (0) in scala_test Failed :fire:
#11312 closed/duplicate 2026-11-09T17:11 GHA Run 42658360509 : job roll-forward-lsu (0) in scala_test Failed :fire:
#11224 closed/duplicate 2026-11-05T12:26 GHA Run 42020929705 : job roll-forward-lsu (0) in scala_test Failed :fire:
assignees= comments=2
```
- 11303: parent, same clue, same bound, same refusals (AK). Open, unassigned; its comments (2026-11-09, 2026-11-10)
  describe the budget and the 4 earlier hits.
- 11312, 11328, 11336: AK dups (INDEX rows), same failure.
- 11224: 2026-11-05, before #8234; not counted here.
H2 (validator init against a non-active psid) does not apply: no validator restart, the failure is before the LSU.

## 8. Not hard broken: roll-forward-lsu (0) on every post-merge main run of 2026-11-10/11

```
for p in 1 2 3; do gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&event=push&created=>=2026-11-10&per_page=50&page=$p" > log/11371/scan/runs-$p.json; done
# per run: jobs?filter=all&per_page=40&page=N into log/11371/scan/jobs-<run>-<N>.json, then
for run in $(cat $S/runs-*.json | jq -r '.workflow_runs[] | .id' | sort -u); do sha=$(cat $S/runs-*.json | jq -r --argjson r $run '.workflow_runs[] | select(.id==$r) | "\(.created_at) \(.head_sha[0:10])"'); cat $S/jobs-$run-*.json | jq -r --arg h "$sha $run" '.jobs[] | select(.name | test("(^| / )roll-forward-lsu \\(0\\)$")) | "\($h) \(.id) a=\(.run_attempt) \(.conclusion)"'; done | sort
```
```
2026-11-10T06:07:27Z 39ef05f244 42766394442 129664098508 a=1 success
2026-11-10T08:06:04Z e5741cdd9f 42780003623 129707823365 a=1 success
2026-11-10T08:24:31Z 6aed1a2ab2 42782393978 129715671720 a=1 success
2026-11-10T08:50:59Z 087f79bf73 42785850771 129727101242 a=1 success
2026-11-10T09:24:50Z 6c0fee0505 42790498517 129742803244 a=1 failure
2026-11-10T09:25:22Z e59889cad0 42790576154 129743011204 a=1 success
2026-11-10T09:25:38Z a9038ea432 42790612651 129743767722 a=1 success
2026-11-10T09:32:00Z f367de93c5 42791495886 129746274603 a=1 success
2026-11-10T09:55:21Z 6dbbda48ca 42794742288 129757298441 a=1 success
2026-11-10T10:31:13Z f78a3ebdeb 42799831067 129774572737 a=1 failure
2026-11-10T11:06:41Z c119dc37fa 42804747879 129791311405 a=1 success
2026-11-10T11:45:33Z 88491ce56e 42810067070 129809310370 a=1 success
2026-11-10T13:17:08Z 1844b95301 42823122392 129853834176 a=1 success
2026-11-10T15:48:37Z 5a5335450d 42848234115 129941992173 a=1 success
2026-11-10T22:27:09Z 5ccef7ff82 42914678423 130205512186 a=1 success
2026-11-10T23:29:49Z 2ef0423979 42923829975 130205519279 a=1 success
2026-11-11T02:49:37Z 9dd892fe2e 42948633392 130287713822 a=1 success
2026-11-11T02:49:37Z 9dd892fe2e 42948633392 130343181017 a=2 success
2026-11-11T06:49:59Z b3502738ae 42975175376 130372802492 a=1 success
2026-11-11T10:03:06Z 3b2f0ae469 43000309945 130455003516 a=1 success
2026-11-11T10:32:50Z d2cf5f33f5 43004518396 130468941991 a=1 success
2026-11-11T11:32:11Z 2d54198a47 43012738610 130496493253 a=1 success
2026-11-11T11:32:11Z 2d54198a47 43012738610 130710913219 a=2 success
2026-11-11T11:40:23Z c961df87ba 43013871503 130500305533 a=1 success
2026-11-11T13:34:29Z 2fc780a883 43030349815 130555879985 a=1 success
2026-11-11T13:35:44Z 5bdf3cc88f 43030542628 130557422683 a=1 success
2026-11-11T13:39:19Z 644b3ba97a 43031077112 130558370559 a=1 success
2026-11-11T13:42:02Z 5a0be64241 43031491527 130569702379 a=1 success
2026-11-11T16:02:25Z 3b5b82822d 43054307546 130640013387 a=1 success
2026-11-11T16:08:42Z 1ee8c88066 43055359204 130644883964 a=1 success
2026-11-11T17:51:21Z 08774bc437 43072152132 130703038018 a=1 failure
2026-11-11T18:01:02Z ee8772cc0b 43073677994 130708314993 a=1 success
2026-11-11T18:01:30Z 915616a77d 43073759004 130709572782 a=1 failure
2026-11-11T18:30:00Z d356bcf573 43078335366 130724476341 a=1 success
```
4 failures of 34 jobs on 32 runs; no two consecutive runs failed, every failing sha has passing neighbours
(ee8772cc0b, the parent of 915616a77d, passed; d356bcf573 after it passed). Hard broken by a commit is ruled out;
rate about 1 in 8.

## Verdict

Duplicate of 11303 (family AK): flake from a test time budget, no product change. Both runs end the same way: a
joining SV still has a join step to sequence when the pre-DR synchronizer reaches its fixed sequencing bound (suite
start + 300 s, test line 89) and every later send is refused. 11371 is the 11312/11328 shape (fourth sequencer
joins 9 s before the bound, sv3 sends its last join step 4.4 s after it). 11372 is a new delay of the same kind: the
three joins run in parallel and late, sv3's sequencer enters the BFT ordering topology at 18:20:27.66 (strong quorum
2 of 2) but its node only joins epoch 40 at 18:21:08, so ordering stalls for 39 s across the bound. Not caused by
#8432 or the Canton bump #8441 (passing runs on both, no related errors). Fix location unchanged from 11303:
`RollForwardLsuDRIntegrationTest.scala` line 89, more pre-DR headroom than 300 s, or start the joining SVs one at a
time before DR. Not verified: why the 11372 joins were slow (vetting alone took 68 s, onboarding confirmation 58 s);
no cause for that is shown in the logs we searched.
