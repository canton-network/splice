# 11395 - SvDsoPartyManagementIntegrationTest (wall-clock-time (4)): 10 s host stall, BFT view change blacklists sv4, the tap's verdict is lost

Verdict: duplicate of 11255 (family B, lost-verdict shape), with a new trigger and a new scope. The trigger is not an
ordering topology change but a 10 s stall of the whole test host (both JVMs silent, family J like 11375). The BFT
segments then go through a view change, sv4's sequencer is blacklisted for epoch 155, the sv4 mediator's verdict for
sv3's tap is refused, and the tap never completes. The test's wallet tap HTTP call times out after 38 s first.
splice#8356 (no BFT blacklisting in tests) is in this sha but is applied only to simtime environments
(`EnvironmentDefinition.withSimTime`); this is a wall-clock suite, so blacklisting was active.

## 0. Issue and job

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11395 --jq '.title, .body'
GHA Run 43238677591 : job wall-clock-time (4) in scala_test Failed :fire:
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43238677591).
Job: wall-clock-time (4) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [f7db866](https://github.com/canton-network/splice/commit/f7db86672f2d334aa613a4f263a9593ac154137a) Wait for error element, retry log check for error-on-unknown-party (#8422)
Actor: stephencompall-DA
$ (recipe 1, NAME='wall-clock-time (4)')
131256726767 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (4)
$ gh api repos/canton-network/splice/actions/jobs/131256726767 --jq '"runner=\(.runner_name) started=\(.started_at) completed=\(.completed_at)"'
runner=self-hosted-k8s-large-runner-2x69j started=2026-11-12T14:43:07Z completed=2026-11-12T15:21:31Z
$ git show f7db86672f:nix/canton-sources.json | grep -m1 version
  "version": "3.7.3",
```
Job log `log/11395/job.log` (recipe 1, job 131256726767); artifact `logs-wall-clock-time-4` (id 13957845567) in
`log/11395/logs-wall-clock-time-4/` (`gh run download 43238677591 --repo canton-network/splice -n logs-wall-clock-time-4`).
`T=canton_network_test.clog.gz`, `C=canton.clog.gz`; `win.clog` = the lines of `$C` with timestamps 15:07:15-15:08:05.
`fmt` = recipe 4 line formatter (timestamp, message, [logger] level; `1220...` ids shortened to `..`).

## 1. Failure

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/11395/job.log | grep -a -A1 'DSO Party can be setup in the participant after SV has been confirmed to be part of the DSO \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | head -2
[info] - The DSO Party can be setup in the participant after SV has been confirmed to be part of the DSO *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
```

## 2. Test timeline

```
$ zcat $T | grep -a SvDsoPartyManagementIntegrationTest | grep -a -E "Starting .SvDsoPartyManagementIntegrationTest/The DSO|Running clue: (start onboarding|sv3 can exercise)|Finished clue: start onboarding|Failed clue|Test failed: " | fmt | cut -c1-230
2026-11-12T15:06:01.928Z Starting 'SvDsoPartyManagementIntegrationTest/The DSO Party can be setup in the participant after SV has been confirmed to be part of the DSO'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-12T15:07:14.997Z Running clue: start onboarding new SV and DSO party setup on new SV's dedicated participant",[o.l.s.i.t.SvDsoPartyManagementIntegrationTest:SvDsoPartyManagementIntegrationTest] DEBUG
2026-11-12T15:07:15.010Z Finished clue: start onboarding new SV and DSO party setup on new SV's dedicated participant",[o.l.s.i.t.SvDsoPartyManagementIntegrationTest:SvDsoPartyManagementIntegrationTest] DEBUG
2026-11-12T15:07:24.757Z Running clue: sv3 can exercise AmuletRules_DevNet_Tap without disclosed contracts or extra observer.",[o.l.s.i.t.SvDsoPartyManagementIntegrationTest:SvDsoPartyManagementIntegrationTest] DEBUG
2026-11-12T15:08:02.801Z Failed clue: sv3 can exercise AmuletRules_DevNet_Tap without disclosed contracts or extra observer.",[o.l.s.i.t.SvDsoPartyManagementIntegrationTest:SvDsoPartyManagementIntegrationTest] ERROR
2026-11-12T15:09:03.871Z Test failed: 'SvDsoPartyManagementIntegrationTest/The DSO Party can be setup in the participant after SV has been confirmed to be part of the DSO', message: Command execution failed., location: SeeStackDep
```
The failing call is `wc("sv3Wallet").tap(100.0)`:
```
$ git show f7db86672f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvDsoPartyManagementIntegrationTest.scala | sed -n 176,181p
      clue(
        "sv3 can exercise AmuletRules_DevNet_Tap without disclosed contracts or extra observer."
      ) {
        val sv3Party = sv3Backend.getDsoInfo().svParty

        wc("sv3Wallet").tap(100.0)
```
(Test file last changed by d4a21721d0, #4151: `git log --oneline -1 f7db86672f -- <file>`.)

## 3. The tap from the wallet side: 30 s ledger deadline, then in-flight, then the 38 s HTTP timeout

```
$ zcat $T | (15:07:24.7-15:08:02.85) | grep -a -E 'Running on sv3Wallet command Tap|still not completed after 10 seconds|failed with a retryable error.*DEADLINE_EXCEEDED|NOT_CONNECTED_TO_ANY_SYNCHRONIZER|resulted in a timeout after 38 seconds|HTTP 503 Service Unavailable POST at ./api/validator/v0/wallet/tap' | grep -a sv3 | fmt | cut -c1-260 | (first of each kind)
2026-11-12T15:07:24.776Z Running on sv3Wallet command Tap(100.0,None) against NetworkAppClientConfig(http://127.0.0.1:5303,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes)))",[o.l.s.c.ConsoleHttpCommandRunner:SvDsoPartyManagementIntegrat
2026-11-12T15:07:40.038Z Task Running on sv3Wallet command Tap(100.0,None) against NetworkAppClientConfig(http://127.0.0.1:5303,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes))) still not completed after 10 seconds. Continue waiting..."
2026-11-12T15:07:54.794Z The operation 'Tap' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 29.999816575s. Name resolution delay 0.000000000 seconds. [closed=[], open=[[remote_addr=127.0.0.1/127
2026-11-12T15:08:00.039Z Task Running on sv3Wallet command Tap(100.0,None) against NetworkAppClientConfig(http://127.0.0.1:5303,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes))) still not completed after 35262 milliseconds. Continue wai
2026-11-12T15:08:02.504Z Request (tid:2d3102d9fe3786cda01ca1954b68ca6c) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with FAILED_PRECONDITION/NOT_CONNECTED_TO_ANY_SYNCHRONIZER(9,2d3102d9): This participant is not 
2026-11-12T15:08:02.505Z Batch failed with FAILED_PRECONDITION: NOT_CONNECTED_TO_ANY_SYNCHRONIZER(9,2d3102d9): This participant is not connected to any synchronizer., failing all operations",[o.l.s.w.t.TreasuryService:SvDsoPartyManagementIntegrationTest/config
2026-11-12T15:08:02.505Z The operation 'Tap' failed with a retryable error (full stack trace omitted):\ncategory=Some(InvalidGivenCurrentSystemStateOther)\nErrorInfoDetail(NOT_CONNECTED_TO_ANY_SYNCHRONIZER,Map(participant -> sv3Participant, definite_answer -> 
2026-11-12T15:08:02.505Z Retrying on same error kind transient error (request infinite retries) for StatusRuntimeException/FAILED_PRECONDITION: NOT_CONNECTED_TO_ANY_SYNCHRONIZER(9,2d3102d9): This participant is not connected to any synchronizer.",[o.l.s.w.a.h.
2026-11-12T15:08:02.796Z Request to http://127.0.0.1:5303/api/validator/v0/wallet/tap (POST) resulted in a timeout after 38 seconds.",[o.l.s.a.h.HttpErrorHandler:SvDsoPartyManagementIntegrationTest/config=85d71bd1/validator=sv3Validator] WARN
```
Between 15:07:55 and 15:08:02 the wallet's retries got `SUBMISSION_ALREADY_IN_FLIGHT` (the first submission was
still in flight, section 4). The `NOT_CONNECTED_TO_ANY_SYNCHRONIZER` at 15:08:02.5 comes from both validators
reconfiguring their participants' sequencer connections 0.6 s earlier (section 7), not from the stall.

## 4. The tap on sv3Participant: sent at 15:07:25.4, sequenced at 15:07:40.724199, delivered at 15:07:55.8

`tapcmd.log` = lines of `$C` (15:07:24-15:09:10) containing the command id `...wallet.tap_2898bc9c...7af1`.
```
$ grep -a -E 'is now in-flight|Phase 1 completed|REQUEST_TIME_OUT|Successfully submitted transaction' tapcmd.log | fmt | cut -c1-200
2026-11-12T15:07:25.013Z Command org.lfdecentralizedtrust.splice.wallet.tap_2898bc9cd448ea6345d984f0c678b603d864ea836c03a4b814714e6244da7af1 is now in-flight.",[c.d.c.p.s.CantonSyncService:participant
2026-11-12T15:07:25.423Z Phase 1 completed: Submitting 4 envelopes for Transaction request, submitters digital-asset-eng-3-85d71bd1::12208884871d..., command-id org.lfdecentralizedtrust.splice.wallet.
2026-11-12T15:07:54.795Z REQUEST_TIME_OUT(3,1b249da4): Timed out while awaiting for a completion corresponding to a command submission with command-id=org.lfdecentralizedtrust.splice.wallet.tap_2898bc
2026-11-12T15:07:54.795Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:52456: failed with DEADLINE_EXCEEDED/REQUEST_TIME_OUT(3,1b249da4): Timed out while
2026-11-12T15:07:55.859Z Successfully submitted transaction org.lfdecentralizedtrust.splice.wallet.tap_2898bc9cd448ea6345d984f0c678b603d864ea836c03a4b814714e6244da7af1.",[c.d.c.p.s.CantonSyncService:p
$ grep -a 'participant=sv3Participant/' win.clog | grep -a -E 'Sending message ID c039d5e0|Request send-async-versioned/c039d5e0.*has succeeded|sequenced-event-send-result has not completed' | fmt | cut -c1-230
2026-11-12T15:07:25.427Z Sending message ID c039d5e0-3f61-4203-833c-3a282153aed6 to sequencer SEQ::sv1::1220252d1617...",[c.d.c.s.c.RichSequencerClientImpl:participant=sv3Participant/psid=global-domain::1220fde82f10::37-0] DEBUG
2026-11-12T15:07:25.440Z Request send-async-versioned/c039d5e0-3f61-4203-833c-3a282153aed6 has succeeded for server-SEQ::sv1::..ecbf-0.",[c.d.c.s.c.p.GrpcConnection:participant=sv3Participant/psid=global-domain::1220fde82f10::37-0
2026-11-12T15:07:40.111Z sequenced-event-send-result has not completed after 14651 milliseconds",[c.d.c.p.p.TransactionProcessor:participant=sv3Participant/psid=global-domain::1220fde82f10::37-0] DEBUG
```
The submission's trace id is 8bcbf374eccf7a751882a962ff8f56df (the `Phase 1 completed` line). Its delivery:
```
$ zcat $C | grep -a 8bcbf374eccf7a751882a962ff8f56df | grep -a participant=sv3Participant | grep -a -m1 'Validating event' | grep -a -oE '^\{"@timestamp":"[^"]+"|timestamp = 2026-11-12T15:07:40.724199Z' | paste - -
{"@timestamp":"2026-11-12T15:07:55.769Z"	timestamp = 2026-11-12T15:07:40.724199Z
```

## 5. 15:07:30-15:07:40: the whole host stalls (both JVMs)

Log lines per second (Canton process and test/app process are separate JVMs):
```
$ awk -F'"@timestamp":"' '{print substr($2,12,8)}' win.clog | awk '$1>="15:07:22" && $1<="15:07:44"' | sort | uniq -c
$ zcat $T | (same, 15:07:22-15:07:44)
canton.clog:
   2223 15:07:22    3251 15:07:23    2309 15:07:24     937 15:07:25     280 15:07:26     215 15:07:27     238 15:07:28      52 15:07:29       2 15:07:30    7080 15:07:40    2321 15:07:41    1810 15:07:42     941 15:07:43     389 15:07:44 
canton_network_test.clog:
    294 15:07:22     376 15:07:23     545 15:07:24     311 15:07:25     290 15:07:26     286 15:07:27     296 15:07:28     157 15:07:29      20 15:07:30       1 15:07:32       2 15:07:33       1 15:07:34       1 15:07:35       1 15:07:36       1 15:07:38       1 15:07:39     245 15:07:40     309 15:07:41     295 15:07:42     298 15:07:43     314 15:07:44 
$ zcat $T | grep -a 'Supervising futures itself takes longer' | fmt; grep -a 'Supervising futures itself takes longer' win.clog | fmt
2026-11-12T15:07:40.039Z Supervising futures itself takes longer than the check frequency: 10368 ms. Approximate supervision size: 0 minus 0",[c.d.c.c.FutureSupervisor$Impl] INFO
2026-11-12T15:07:40.054Z Supervising futures itself takes longer than the check frequency: 10193 ms. Approximate supervision size: 0 minus 0",[c.d.c.c.FutureSupervisor$Impl:participant=sv1Participant]
```
The Canton JVM logged nothing from 15:07:31 to 15:07:39 and the test JVM about one line per second; both future
supervisors report a 10.2-10.4 s gap at 15:07:40. Each is the only such line in its log (`grep -a -c` = 1 in the whole
`$T` and in `win.clog`). Two processes stalling together points at the host (runner pod or node), as in 11375; the
node and what saturated it are not verified.

## 6. BFT ordering: no blocks 15:07:25.4-15:07:40.1, view changes, sv4 blacklisted in epoch 155

```
$ grep -a 'sequencer=globalSequencerSv1/' win.clog | grep -a -E 'Ordered block [0-9]+ in segment' | (timestamp, message) | awk -F'\t' '$1>="2026-11-12T15:07:24" && $1<="2026-11-12T15:07:41"' | head -8
2026-11-12T15:07:24.243Z	Ordered block 10635 in segment 1 in epoch 154; original leader SEQ::sv4
2026-11-12T15:07:24.293Z	Ordered block 10640 in segment 2 in epoch 154; original leader SEQ::sv1
2026-11-12T15:07:24.538Z	Ordered block 10634 in segment 0 in epoch 154; original leader SEQ::sv3
2026-11-12T15:07:24.626Z	Ordered block 10637 in segment 3 in epoch 154; original leader SEQ::sv2
2026-11-12T15:07:25.430Z	Ordered block 10639 in segment 1 in epoch 154; original leader SEQ::sv4
2026-11-12T15:07:40.106Z	Ordered block 10644 in segment 2 in epoch 154; original leader SEQ::sv1
2026-11-12T15:07:40.838Z	Ordered block 10645 in segment 3 in epoch 154; original leader SEQ::sv2
2026-11-12T15:07:40.863Z	Ordered block 10649 in segment 3 in epoch 154; original leader SEQ::sv2
$ grep -a 'sequencer=globalSequencerSv1/' win.clog | grep -a 'retransmit 1 view change messages' | fmt | cut -c1-200 | head -3
2026-11-12T15:07:40.109Z Node SEQ::sv2::..6ec6 is in the same view change as us in view 1, so we retransmit 1 view change messages for segment 3 starting at block number 10593.",[c.d.c.s.s.b.b.c.m.c.i
2026-11-12T15:07:40.109Z Node SEQ::sv2::..6ec6 is in the same view change as us in view 1, so we retransmit 1 view change messages for segment 0 starting at block number 10590.",[c.d.c.s.s.b.b.c.m.c.i
2026-11-12T15:07:40.110Z Node SEQ::sv4::..bed5 is in view 0, so we retransmit 1 view change messages for segment 0 starting at block number 10590.",[c.d.c.s.s.b.b.c.m.c.i.SegmentState:sequencer=global
$ zcat $C | (15:07:14-15:07:46) | grep -a -E 'New epoch 15[45] has started' | grep -a globalSequencerSv1 | grep -a -oE '"@timestamp":"[^"]+"|New epoch [0-9]+ has started|blacklisted nodes = List\([^)]*\)' | paste - - -
"@timestamp":"2026-11-12T15:07:14.386Z"	New epoch 154 has started	blacklisted nodes = List()
"@timestamp":"2026-11-12T15:07:45.862Z"	New epoch 155 has started	blacklisted nodes = List(SEQ::sv4::..bed5)
```
Epochs 151-154 started 7-9 s apart (15:06:50.5, 15:06:59.0, 15:07:06.3, 15:07:14.4); epoch 154 took 31 s. The
ordering topology was unchanged (size 4, activation 15:06:03.051724, the same in every epoch line), so this is not
the 1 -> N step of the earlier 11255 hits.

## 7. The tap's verdict is refused by the blacklisted sv4 sequencer; the request times out

```
$ zcat $C | grep -a -m1 'Failed to send result to sequencer for request 2026-11-12T15:07:40.724199Z' | grep -a -oE '"@timestamp":"[^"]+"|GrpcRequestRefusedByServer: [^\\]{0,140}|"logger_name":"[^"]{0,70}'
"@timestamp":"2026-11-12T15:08:04.928Z"
GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,8bcbf374): Mempool received client request but this node is currently blacklisted, rejecting
"logger_name":"c.d.c.s.m.DefaultVerdictSender:mediator=globalMediatorSv4/psid=global-
$ zcat $C | grep -a 'Response message for request \[RequestId(2026-11-12T15:07:40.724199Z)\] timed out' | grep -a -oE '"@timestamp":"[^"]+"|"message":"[^"]+"|"logger_name":"[^"]{0,95}'
"@timestamp":"2026-11-12T15:08:27.380Z"
"message":"Response message for request [RequestId(2026-11-12T15:07:40.724199Z)] timed out at 2026-11-12T15:08:19.233624Z"
"logger_name":"c.d.c.p.p.TransactionProcessor:participant=sv3Participant/psid=global-domain::1220fde82f10::37-0
```
(`DefaultVerdictSender` ERROR, level from the line; the same mediator refused the verdict of request
15:07:40.724198Z at 15:08:07.765, which also timed out on sv1Participant.) Even without the 38 s HTTP timeout the tap
would have failed: its request never got a verdict.

Separately, at 15:08:01.88 both validators changed their participants' sequencer connections, which disconnects and
reconnects the participant (the `NOT_CONNECTED_TO_ANY_SYNCHRONIZER` in section 3):
```
$ zcat $T | grep -a 'modifying sequencers connections' | grep -a config=85d71bd1 | grep -a -oE '"@timestamp":"[^"]+"|"message":"modifying sequencers connections|"logger_name":"[^"]+"' | paste - - - | cut -c1-230
"@timestamp":"2026-11-12T15:08:01.878Z"	"message":"modifying sequencers connections	"logger_name":"o.l.s.v.a.ReconcileSequencerConnectionsTrigger:SvDsoPartyManagementIntegrationTest/config=85d71bd1/validator=sv3Validator"
"@timestamp":"2026-11-12T15:08:01.880Z"	"message":"modifying sequencers connections	"logger_name":"o.l.s.v.a.ReconcileSequencerConnectionsTrigger:SvDsoPartyManagementIntegrationTest/config=85d71bd1/validator=sv1Validator"
```

## 8. splice#8356 is in this sha but only for simtime

```
$ gh api repos/canton-network/splice/pulls/8356 --jq '"#\(.number) \(.state) merged=\(.merged_at) sha=\(.merge_commit_sha) \(.title)"'
#8356 closed merged=2026-11-10T23:29:46Z sha=2ef042397984ed873ab5e815fd747a87d0c62258 Disable BFT sequencer blacklisting in integration tests
$ git merge-base --is-ancestor 2ef042397984ed873ab5e815fd747a87d0c62258 f7db86672f && echo "f7db866 contains #8356"
f7db866 contains #8356
$ git grep -n 'disableBftBlacklisting' f7db86672f -- apps
f7db86672f:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala:704:  def disableBftBlacklisting(): ConfigTransform =
f7db86672f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala:569:      .addConfigTransform((_, conf) => ConfigTransforms.disableBftBlacklisting()(conf))
$ git show f7db86672f:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala | sed -n 700,704p
  // A BFT sequencer that misses its segment (e.g., right after onboarding or under load) is
  // blacklisted for several epochs, during which it rejects all client submissions.
  // In simtime tests this causes flakes right after sequencer onboarding (rejected sends, lost
  // verdicts). Applied in EnvironmentDefinition.withSimTime only.
  def disableBftBlacklisting(): ConfigTransform =
$ git show f7db86672f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala | sed -n 534p
  private def withSimTime: EnvironmentDefinition =
```
Line 569 is the end of `withSimTime` (lines 534-569). `SvDsoPartyManagementIntegrationTest` runs in a wall-clock
shard, so its SVs configure BFT with blacklisting, which section 6 shows active.

## 10. Duplicate search

```
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "SvDsoPartyManagementIntegrationTest"' --jq '.items[] | "\(.number) \(.state) \(.created_at[0:10]) \(.title[0:100])"'
10165 closed 2026-09-17 sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 sequencer-add topology tr
5960 closed 2025-10-16 EventHistorySanityCheckPlugin mismatched events in SvDsoPartyManagementIntegrationTest
5432 closed 2025-08-24 SvDsoPartyManagementIntegrationTest : FAILED_PRECONDITION/NOT_CONNECTED_TO_ANY_SYNCHRONIZER
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Supervising futures itself takes longer"' --jq '...'
(no output)
```
- 10165: sequencer-add onboarding timeout (B, signature 2), merged into 11255; not this step.
- 5960: event history sanity check; different.
- 5432: NOT_CONNECTED during a sequencer-connection change in the test ("same old sync connection change during
  the test"); here that change happens only after the tap had already failed (section 7), so not the cause.
- 11255 (open, rautenrieth-da): lost-verdict shape = a blacklisted sequencer refuses the mediator's verdict. Same
  mechanism; new here: triggered by a host stall in a wall-clock suite, outside the simtime scope of #8356.

## Verdict

- Duplicate of 11255 (lost-verdict shape). Mechanism: 10 s host stall (15:07:30-15:07:40) -> BFT segments time out
  -> view change -> sv4 sequencer blacklisted for epoch 155 -> mediator sv4's verdict for sv3's tap (request
  15:07:40.724199) refused -> request times out (15:08:19); the test's wallet tap HTTP call times out at 38 s first.
- Fix direction (for 11255's owner): apply `ConfigTransforms.disableBftBlacklisting()` to wall-clock environments as
  well (it is only in `EnvironmentDefinition.withSimTime`), since a host stall under load blacklists a sequencer the
  same way an onboarding does. Host stall cause: infra, same class as 11375 (node not identified).
- Not verified: the node of the runner pod and what saturated it; whether blacklisting alone explains the extra
  15 s between the sequencing (15:07:40.7) and the delivery to the participants (15:07:55.7).
