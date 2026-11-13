# 11419 - RollForwardLsuDR "Start nodes before DR": sv2 init timeout, pre-DR cutoff 18:19:07.974 (run 43479138494)

DUPLICATE of 11303 (family AK): the pre-DR sequencers stop sequencing at suite start + 300 s
(`lsu-repair.global-max-sequencing-time-exclusive`, test line 89). The MediatorSynchronizerState proposal adding
MED::sv2 was proposed by all four SVs at 18:17:07.77-18:17:08.45, in the same second the BFT ordering topology went
from 1 to 4 nodes (epoch 31, 18:17:07.785). Every first broadcast was refused by the sv1 sequencer with
`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. sv1 and sv2 re-sent after their
outbox timeout and were sequenced at 18:17:37.95 (2 of the 3 DSO signatures); sv3 and sv4 treated the refusal as
"possibly sequenced" and did not re-send. The third signature only arrived with sv3's re-proposal at 18:19:05.954
(effective 18:19:06.0). sv2 waited 116 s for its mediator's traffic grant (18:17:11.587-18:19:07.970). Its last init
step, the OnboardedAfterDelay `DsoRules_SetSynchronizerNodeConfig`, was sent at 18:19:08.924, 0.95 s after the bound
18:19:07.974670. It was refused with SEQUENCING_TIME_NOT_ADMISSIBLE and never completed. sv1, sv3 and sv4 had
initialized by 18:17:24. This is the 11303 shape (lost MED::sv2 broadcasts at the 1 to 4 step), 2 s closer to the
bound than 11396 (0.33 s late there).

- Run: https://github.com/canton-network/splice/actions/runs/43479138494, main 1ffd6415e3 ("Include dev events in
  monday.com cadence (#7359)"), job 132071212701 `roll-forward-lsu (0)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (first AK hit on 3.7.4-snapshot).
- Component: test (pre-DR budget at RollForwardLsuDRIntegrationTest.scala:89); the margin is consumed by the
  Canton BFT 1 to N onboarding delays of family B / 11255 (rejected send).

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11419/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/11419/job.log | grep -a -A4 'roll forward LSU DR \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | head -5
```
```
[info] - roll forward LSU DR *** FAILED ***
[info] Run completed in 8 minutes, 55 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
[info] - roll forward LSU DR *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
```
Canton pin:
```
git show 1ffd6415e3:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 2. Timeline: sv2 is the SV that never initialized

Artifact `logs-roll-forward-lsu-0` (id 14065088543) in `log/11419/logs-roll-forward-lsu-0/`.
```
T=log/11419/logs-roll-forward-lsu-0/canton_network_test.clog.gz
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/RollForwardLsuDRIntegrationTest\/config=3e0c45a9\///'
zcat $T | grep -a -E "Starting test suite|Running clue: Start nodes|app initialization: Initialize app (finished|failed)|Timeout while waiting for initialization|Failed clue: Start nodes" | sed -E "$F" | cut -c1-150
```
```
2026-11-13T18:14:08.010Z Starting test suite 'RollForwardLsuDRIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T18:14:16.169Z Running clue: Start nodes before DR",[o.l.s.i.t.RollForwardLsuDRIntegrationTest:RollForwardLsuDRIntegrationTest] DEBUG
2026-11-13T18:15:42.830Z sv1Scan app initialization: Initialize app finished after PT1M21.721357546S",[o.l.s.s.ScanApp:scan=sv1Scan] INFO
2026-11-13T18:15:44.668Z sv1 app initialization: Initialize app finished after PT1M22.094393627S",[o.l.s.s.SvApp:SV=sv1] INFO
2026-11-13T18:16:32.621Z aliceValidator app initialization: Initialize app finished after PT2M11.901390755S",[o.l.s.v.ValidatorApp:validator=aliceVali
2026-11-13T18:16:57.335Z sv4Scan app initialization: Initialize app finished after PT2M35.151228373S",[o.l.s.s.ScanApp:scan=sv4Scan] INFO
2026-11-13T18:16:58.618Z sv3Scan app initialization: Initialize app finished after PT2M36.802077537S",[o.l.s.s.ScanApp:scan=sv3Scan] INFO
2026-11-13T18:16:59.529Z sv2Scan app initialization: Initialize app finished after PT2M38.053302257S",[o.l.s.s.ScanApp:scan=sv2Scan] INFO
2026-11-13T18:17:24.093Z sv3 app initialization: Initialize app finished after PT3M0.805091084S",[o.l.s.s.SvApp:SV=sv3] INFO
2026-11-13T18:17:24.282Z sv4 app initialization: Initialize app finished after PT3M0.608842114S",[o.l.s.s.SvApp:SV=sv4] INFO
2026-11-13T18:22:04.167Z Timeout while waiting for initialization of sv2",[o.l.s.c.SvAppBackendReference:app=sv2] ERROR
2026-11-13T18:22:04.168Z Failed clue: Start nodes before DR",[o.l.s.i.t.RollForwardLsuDRIntegrationTest:RollForwardLsuDRIntegrationTest] ERROR
2026-11-13T18:22:42.335Z sv2 app initialization: Initialize app failed",[o.l.s.s.SvApp:SV=sv2] INFO
2026-11-13T18:22:42.335Z sv2 app initialization: Ignoring initialization failure, we are actually shutting down. Message was: sv2 app initialization: 
```

## 3. Pre-DR bound = suite start + 300 s

The test computes the bound once, when the suite class is constructed (18:14:07.97, 40 ms before
`Starting test suite`):
```
git show 1ffd6415e3:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala | sed -n '88,89p;209,210p'
```
```
  // This needs to be long enough in the future that we can start Canton and initialize for SVs.
  val maxSequencingTime = CantonTimestamp.now().plusSeconds(300)
      extraSequencerConfig =
        Seq(s"parameters.lsu-repair.global-max-sequencing-time-exclusive=${maxSequencingTime}"),
```
The pre-DR sequencers refuse everything from the bound on:
```
S=log/11419/logs-roll-forward-lsu-0/canton-standalone-roll-forward-lsu-before-dr.clog.gz
zcat $S | grep -a 'SEQUENCING_TIME_NOT_ADMISSIBLE' | head -1 | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-220
zcat $S | grep -a -c 'SEQUENCING_TIME_NOT_ADMISSIBLE'
```
```
2026-11-13T18:19:08.171Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,f94cb1af): The sequencing time [2026-11-13T18:19:08.087869Z] is not admissible (sequencing time is at or after the upper bound (2026-11-13T18:19:07.974670Z)) for Se
980
```

## 4. sv2's last init step is sent 0.95 s after the bound and never completes

After the traffic wait (section 5), `JoiningNodeInitializer.onboard` calls
`reconcileSynchronizerNodeConfigIfRequired(..., OnboardedAfterDelay)`, a `DsoRules_SetSynchronizerNodeConfig`
exercise:
```
zcat $T | grep -a 'SV=sv2' | grep -a -E 'Got .SV nodes have been granted unlimited traffic|Setting domain node config|setting domain config for|JoiningNodeInitializer joining Dso with key failed' | grep -av 'active readers' | sed -E "$F" | awk '$1>"2026-11-13T18:19"' | cut -c1-200
```
```
2026-11-13T18:19:08.838Z Got 'SV nodes have been granted unlimited traffic': ()",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv2] INFO
2026-11-13T18:19:08.865Z Setting domain node config to Record(\n  fields = Seq(\n    Record(fields = Seq(GenMap(), List(), List())),\n    None(),\n    Some(Record(fields = \"MED::sv2::..d026\")),\n   
2026-11-13T18:21:08.868Z The operation 'setting domain config for digital-asset-eng-2-3e0c45a9::12201e69c1d9...' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOption
2026-11-13T18:21:08.868Z The operation 'setting domain config for digital-asset-eng-2-3e0c45a9::12201e69c1d9...' has failed with an exception. New kind of error: transient error (request infinite retr
2026-11-13T18:21:09.068Z Now retrying operation 'setting domain config for digital-asset-eng-2-3e0c45a9::12201e69c1d9...'. ",[o.l.s.s.o.SynchronizerNodeReconciler:SV=sv2] INFO
2026-11-13T18:21:09.073Z Setting domain node config to Record(\n  fields = Seq(\n    Record(fields = Seq(GenMap(), List(), List())),\n    None(),\n    Some(Record(fields = \"MED::sv2::..d026\")),\n   
2026-11-13T18:22:42.330Z Active readers: setting domain config for digital-asset-eng-2-3e0c45a9::12201e69c1d9...,processTaskWithRetry",[o.l.s.e.RetryProvider:SV=sv2] DEBUG
2026-11-13T18:22:42.331Z Giving up on retrying the operation 'setting domain config for digital-asset-eng-2-3e0c45a9::12201e69c1d9...' due to shutdown. Last attempt was Some(transient error (request i
2026-11-13T18:22:42.332Z sv2 app initialization: JoiningNodeInitializer joining Dso with key failed",[o.l.s.s.SvApp:SV=sv2] INFO
2026-11-13T18:22:42.335Z sv2 app initialization: Initialize node failed",[o.l.s.s.SvApp:SV=sv2] INFO
2026-11-13T18:22:42.335Z sv2 app initialization: Initialize app failed",[o.l.s.s.SvApp:SV=sv2] INFO
```
The command (command-id a7c55454-...) on the sv2 participant, then on the sequencers (trace 49704d03...):
```
C=log/11419/logs-roll-forward-lsu-0/canton.clog.gz
zcat $C | grep -a 'a7c55454-c1a5-40af-a350-0c6d7998093a' | grep -a -E 'Phase 1 completed|REQUEST_TIME_OUT' | sed -E "$F" | cut -c1-200
zcat $S | grep -a 49704d032a5f2c1d827c072bf70b00f4 | grep -a -E 'sends request with id|SEQUENCING_TIME_NOT_ADMISSIBLE' | sed -E "$F" | cut -c1-230 | head -3
```
```
2026-11-13T18:19:08.924Z Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-eng-2-3e0c45a9::12201e69c1d9..., command-id a7c55454-c1a5-40af-a350-0c6d7998093a",[
2026-11-13T18:21:08.868Z REQUEST_TIME_OUT(3,d859c61e): Timed out while awaiting for a completion corresponding to a command submission with command-id=a7c55454-c1a5-40af-a350-0c6d7998093a and submissi
2026-11-13T18:21:08.868Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:54868: failed with DEADLINE_EXCEEDED/REQUEST_TIME_OUT(3,d859c61e): Timed out while
2026-11-13T18:19:08.928Z 'PAR::sv2::12201e69c1d9...' sends request with id '520c4b21-2e0a-459c-998f-31d8b9d0c959' of size 5049 bytes with 3 envelopes to MGR=1, PAR=4.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=sv1StandaloneSeque
2026-11-13T18:19:09.065Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,49704d03): The sequencing time [2026-11-13T18:19:08.975851Z] is not admissible (sequencing time is at or after the upper bound (2026-11-13T18:19:07.974670Z)) for Send(2026-
2026-11-13T18:19:09.066Z SEQUENCING_TIME_NOT_ADMISSIBLE(9,49704d03): The sequencing time [2026-11-13T18:19:08.975851Z] is not admissible (sequencing time is at or after the upper bound (2026-11-13T18:19:07.974670Z)) for Send(2026-
```

## 5. What consumed the margin: sv2 waits 116 s for MED::sv2 unlimited traffic

sv3 and sv4 get their mediator traffic grant within 9 s; sv2 waits until 18:19:07.970:
```
zcat $T | grep -a -E 'Onboarding mediator"|Success: Mediator has been granted unlimited traffic' | sed -E "$F" | cut -c1-140
```
```
2026-11-13T18:17:11.429Z Onboarding mediator",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
2026-11-13T18:17:11.587Z Onboarding mediator",[o.l.s.s.LocalSynchronizerNode:SV=sv2] INFO
2026-11-13T18:17:12.645Z Onboarding mediator",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-13T18:17:20.410Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
2026-11-13T18:17:20.513Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv4] INFO
2026-11-13T18:19:07.970Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv2] INFO
```
The grant needs a traffic state for MED::sv2, so it waits for the MediatorSynchronizerState that adds MED::sv2.
All four SVs propose it within 0.7 s; it is accepted only after sv3's re-proposal 2 min later:
```
zcat $T | grep -a -E 'Success: Add mediator MED::sv2|Submitted proposal MediatorSynchronizerState' | grep -a 'MED::sv2' | sed -E "$F" | sed -E 's/Submitted proposal MediatorSynchronizerState.*for Add mediator/Submitted MSS proposal for Add mediator/' | cut -c1-160
```
```
2026-11-13T18:17:07.773Z Submitted MSS proposal for Add mediator MED::sv2::1220b3486f1d..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv4] INFO
2026-11-13T18:17:07.776Z Submitted MSS proposal for Add mediator MED::sv2::1220b3486f1d..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv2] INFO
2026-11-13T18:17:07.790Z Submitted MSS proposal for Add mediator MED::sv2::1220b3486f1d..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv1] INFO
2026-11-13T18:17:08.452Z Submitted MSS proposal for Add mediator MED::sv2::1220b3486f1d..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-13T18:19:05.954Z Submitted MSS proposal for Add mediator MED::sv2::1220b3486f1d..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-13T18:19:06.485Z Success: Add mediator MED::sv2::1220b3486f1d...",[o.l.s.e.ParticipantAdminConnection:SV=sv1] INFO
2026-11-13T18:19:06.492Z Success: Add mediator MED::sv2::1220b3486f1d...",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-13T18:19:07.354Z Success: Add mediator MED::sv2::1220b3486f1d...",[o.l.s.e.ParticipantAdminConnection:SV=sv4] INFO
2026-11-13T18:19:09.255Z Success: Add mediator MED::sv2::1220b3486f1d...",[o.l.s.e.ParticipantAdminConnection:SV=sv2] INFO
```

## 6. The first broadcasts land in the BFT 1 to 4 step and are refused

Ordering topology size per epoch on the sv1 sequencer (only changes shown):
```
zcat $S | grep -a 'sequencer=sv1StandaloneSequencer' | grep -a -E 'New epoch [0-9]+ has started' | sed -E "$F" | grep -oE '^[^ ]+ New epoch [0-9]+ has started with leaders = List\([^)]*\)' | awk '{n=gsub(/SEQ::/,"&"); print $0" leaders="n}' | awk '{k=$NF; if (k!=last) print; last=k}'
```
```
2026-11-13T18:15:08.996Z New epoch 0 has started with leaders = List(SEQ::sv1::..fdd1) leaders=1
2026-11-13T18:17:07.785Z New epoch 31 has started with leaders = List(SEQ::sv4::..d997, SEQ::sv1::..fdd1, SEQ::sv2::..fa96, SEQ::sv3::..ff60) leaders=4
2026-11-13T18:17:21.896Z New epoch 32 has started with leaders = List(SEQ::sv4::..d997, SEQ::sv1::..fdd1, SEQ::sv2::..fa96) leaders=3
2026-11-13T18:17:43.744Z New epoch 35 has started with leaders = List(SEQ::sv4::..d997, SEQ::sv1::..fdd1, SEQ::sv2::..fa96, SEQ::sv3::..ff60) leaders=4
```
The participants' sends 40 ms after the switch (sv1, sv2, sv4) and 0.68 s after it (sv3):
```
zcat $C | grep -a 'GrpcConnection:participant=sv[1-4]Participant' | grep -a 'P2P connectivity is not ready' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | awk '$1>"2026-11-13T18:17:07" && $1<"2026-11-13T18:17:09"' | grep -oE '^[^ ]+|SEQUENCER_UNAVAILABLE\([^)]*\): [^\\]{0,95}|participant=sv[1-4]Participant' | paste - - - | sort -u
```
```
2026-11-13T18:17:07.825Z	SEQUENCER_UNAVAILABLE(1,67fdd068): P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting	participant=sv4Participant
2026-11-13T18:17:07.825Z	SEQUENCER_UNAVAILABLE(1,a4523f85): P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting	participant=sv1Participant
2026-11-13T18:17:07.825Z	SEQUENCER_UNAVAILABLE(1,a97a9612): P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting	participant=sv2Participant
2026-11-13T18:17:08.464Z	SEQUENCER_UNAVAILABLE(1,be48a902): P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting	participant=sv3Participant
2026-11-13T18:17:08.707Z	SEQUENCER_UNAVAILABLE(1,84e5a954): P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting	participant=sv1Participant
```
sv3 and sv4 broadcast the MED::sv2 proposal (TxHash a39ea2e62966, the MediatorSynchronizerState with
`active = Seq(MED::sv1, MED::sv4, MED::sv3, MED::sv2)`) and drop the refusal as possibly sequenced:
```
for p in sv3 sv4; do zcat $C | grep -a "participant=${p}Participant" | grep -a -E 'Broadcasting topology transaction: List\(TxHash\(SHA-256:a39ea2e62966|returned an error however may still be possibly sequenced' | grep -av 'tick-' | sed -E "$F" | awk '$1>"2026-11-13T18:17:07" && $1<"2026-11-13T18:17:09"' | cut -c1-140; done
```
```
2026-11-13T18:17:08.451Z Broadcasting topology transaction: List(TxHash(SHA-256:a39ea2e62966...))",[c.d.c.c.s.SequencerBasedRegisterTopology
2026-11-13T18:17:08.471Z Send [bf99a240-9ef5-42e7-b555-8894bdb77259] returned an error however may still be possibly sequenced so we are ign
2026-11-13T18:17:07.772Z Broadcasting topology transaction: List(TxHash(SHA-256:a39ea2e62966...))",[c.d.c.c.s.SequencerBasedRegisterTopology
2026-11-13T18:17:07.840Z Send [5f7aa8b6-88cd-4c05-8494-ba48fe81d375] returned an error however may still be possibly sequenced so we are ign
```
(first two lines sv3Participant, last two sv4Participant, in loop order)
Signatures of the sequenced versions, seen by the sv1 participant (namespaces: 12201e69c1d9 sv2, 122051707b70 sv1,
12203fcda985 sv3; DSO threshold 3):
```
zcat $C | grep -a 'Persisted topology transactions' | grep -a 'MED::sv2' | grep -a 'participant=sv1Participant' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | awk '$1>"2026-11-13T18:17:07" && $1<"2026-11-13T18:19:08"' | while read -r l; do echo "$l" | grep -oE 'SequencedTime\([^)]*\)'; echo "$l" | sed 's/\\n/\n/g' | grep -E 'signatures' | sed 's/^ *//'; done
```
```
SequencedTime(2026-11-13T18:17:37.949828Z)
signatures = 12201e69c1d9...,
SequencedTime(2026-11-13T18:17:37.949829Z)
signatures = Seq(12201e69c1d9..., 122051707b70...),
SequencedTime(2026-11-13T18:19:06.001746Z)
signatures = Seq(12201e69c1d9..., 12203fcda985..., 122051707b70...)
```
(sv1's and sv2's outboxes retried after `The submitted topology transactions were not sequenced.
Error=[Timeout(2026-11-13T18:17:27.972394Z)]`; sv3's signature came only with its re-proposal at 18:19:05.954.)

## 7. Family and recurrence

```
grep -n -A3 '^## AK' ci-triage/known-families.md | head -4
gh issue view 11303 -R DACH-NY/cn-test-failures --json state,title --jq '.state+" "+.title'
```
```
917:## AK. RollForwardLsuDRIntegrationTest: pre-DR SV onboarding does not finish before the 300 s sequencing cutoff
918-- Signature: `roll-forward-lsu` shard, clue "Start nodes before DR" fails with `Timeout while waiting for
919-  initialization of sv2` (SvAppReference.waitForInitialization, 5 min).
920-- Confirm: the pre-DR Canton log has `SEQUENCING_TIME_NOT_ADMISSIBLE ... is at or after the upper bound (<suite start +
OPEN RollForwardLsuDRIntegrationTest: pre-DR SV onboarding misses the 300 s sequencing cutoff since DA BFT
```
Test unchanged on main since #8234, no fix PR:
```
git log --format='%h %ad %s' --date=short -1 origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala
gh api -X GET search/issues -f q='repo:canton-network/splice is:pr RollForwardLsuDR' --jq '.items[]|"\(.number) \(.state) \(.title)"'
```
```
b77153c658 2026-11-06 Use DA BFT in all tests (#8234)
```
(second command: empty.) Post-merge main runs since 11396 (2026-11-12 17:00 to 2026-11-13 19:23): 20 runs,
`roll-forward-lsu (0)` failed in 1 (this one):
```
gh run list --repo canton-network/splice --branch main --event push --created '>=2026-11-12T17:00' --limit 60 --json databaseId,headSha,conclusion,createdAt,workflowName --jq '.[] | select(.workflowName|test("post-merge")) | "\(.databaseId) \(.headSha[0:10]) \(.createdAt) \(.conclusion)"' > log/11419/runs.txt
while read id sha t c; do j=$(gh api "repos/canton-network/splice/actions/runs/$id/jobs?per_page=100" --jq '[.jobs[]|select(.name|test("roll-forward-lsu \\(0\\)"))|.conclusion]|join(",")'); echo "$id $sha $t ${c:-running} rfl0=$j"; done < log/11419/runs.txt | grep -c 'rfl0=failure'
```
```
1
```

## Verdict

Duplicate of 11303 (AK), confirmed by the AK grep: `SEQUENCING_TIME_NOT_ADMISSIBLE ... upper bound
(2026-11-13T18:19:07.974670Z)` = suite start + 300 s, and sv2's OnboardedAfterDelay `DsoRules_SetSynchronizerNodeConfig`
sent at 18:19:08.924, after the bound. Shape as 11303: the MED::sv2 MediatorSynchronizerState broadcasts land in the
BFT 1 to 4 step (18:17:07.785) and are refused with `P2P connectivity is not ready`; sv3 and sv4 do not re-send, so the
third signature waits for sv3's re-proposal 2 min later. Flake; fix in the test (more pre-DR headroom than 300 s at
line 89, or start the joining SVs one at a time before DR), as already proposed on 11303. First AK hit on Canton
3.7.4-snapshot.20261112; 1 of 20 post-merge main runs since 11396.
Not verified: why sv3's re-proposal came at 18:19:05.954 (the retry interval of its proposal wait) and whether a
longer budget alone always suffices; no local reproduction.
