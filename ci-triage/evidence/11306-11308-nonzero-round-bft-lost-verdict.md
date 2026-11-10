# 11306, 11308 - NonZeroRoundBootstrapBftTimeBasedIntegrationTest setup: lost verdict in the BFT onboarding window freezes participant indexers (runs 42637895044, 42639148146)

DUPLICATES of 11255 (BFT ordering-topology onboarding window), lost-verdict shape (as 11275, 11284, 11287). Both on
post-merge main, canton 3.7.2, 2026-11-09, both in simtime (3), both in the same test as 11284
(NonZeroRoundBootstrapBftTimeBasedIntegrationTest, "SV triggers complete the reward pipeline for the initial round",
failed in environment setup after 5 min of waitForInitialization; 8 succeeded, 1 failed, then 3 suites aborted).

- 11306 (7d3d5f17da): sv4Validator never initializes. Its installWalletForUser submission became confirmation request
  00:14:43.373Z (sim time) right after the ordering topology grew 2 -> 4 (sv4 blacklisted in epochs 75-77). Mediator
  group sv1, sv4, sv3, sv2 with threshold 2: sv4's result was refused by its blacklisted sequencer, sv3 sent, sv1
  delayed its send by 3000 ms on the sim clock, sv2 subscribed after the request. The sim clock stopped at
  00:14:45.306, so sv1 never sent; sv4Participant never got the result and its indexer stopped publishing at
  15:12:24 (request counter 16; counter 17 is the lost request), so every later wallet call of sv4Validator waited.
- 11308 (92705421fd): the sv3 SV app never initializes. Two DSO-party requests submitted by sv2Participant,
  00:14:43.413002Z and 00:14:43.413003Z, got 1 of 2 results each (sv4 refused while blacklisted in epochs 81-83, one
  of sv1/sv2 sent, the other delayed 3000 ms on the sim clock, sv3 subscribed after them). The indexers of all four
  SV participants stop at 15:23:19. The sv3 SV app's own `DsoRules_SetSynchronizerNodeConfig` (request
  00:14:43.449004Z) got its result at 15:23:23.937 but was never published, so its SubmitAndWaitForTransaction timed
  out after 120 s and the app never finished init.

- Runs: https://github.com/canton-network/splice/actions/runs/42637895044 (main 7d3d5f17da, "Handle duplicate
  sequencer ids in scan (#8365)"), job 129231329873 `ci / scala_test_sim_time / simtime (3)`, attempt 1;
  https://github.com/canton-network/splice/actions/runs/42639148146 (main 92705421fd, "Remove CometBFT support from
  pulumi (#8315)"), job 129235695082 `ci / scala_test_sim_time / simtime (3)`, attempt 1.
- Runtime canton: 3.7.2 at both shas.
- Component: Canton BFT orderer onboarding window (blacklisting) plus simtime test setup (nothing advances the sim
  clock during setup; delayed verdict sender scheduled on the sim clock).

## 1. Jobs, canton pin, failing assertion

Issue `Job:` lines `simtime (3) in scala_test`; exactly one failed job per run:
```
for r in 42637895044 42639148146; do gh api --paginate "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == "simtime (3)" or (.name | endswith(" / simtime (3)"))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'; done
for s in 7d3d5f17da6e16d760b259528b0cee424a306c9e 92705421fdc1324cbc17fbbd86785091ec6c3469; do git show $s:nix/canton-sources.json | grep -m1 version; done
```
```
129231329873 attempt=1 ci / scala_test_sim_time / simtime (3)
129235695082 attempt=1 ci / scala_test_sim_time / simtime (3)
  "version": "3.7.2",
  "version": "3.7.2",
```
Job logs `gh api repos/canton-network/splice/actions/jobs/<job>/logs > log/<ref>/job.log`:
```
for r in 11306 11308; do echo "== $r"; sed -E 's/\x1b\[[0-9;]*m//g' log/$r/job.log | grep -a -E 'Tests: succeeded|\*\*\* FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | sort -u; sed -E 's/\x1b\[[0-9;]*m//g' log/$r/job.log | grep -a -m1 -A5 'reward pipeline for the initial round \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | sed -n '2,5p' | cut -c1-200; done
```
```
== 11306
[info] - SV triggers complete the reward pipeline for the initial round *** FAILED ***
[info] Tests: succeeded 8, failed 1, canceled 0, ignored 1, pending 0
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
== 11308
[info] - SV triggers complete the reward pipeline for the initial round *** FAILED ***
[info] Tests: succeeded 8, failed 1, canceled 0, ignored 1, pending 0
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
```
The stack continues with `ValidatorAppReference.waitForInitialization` (11306) and `SvAppReference.waitForInitialization`
(11308). The three suites after it aborted in setup on the same Canton instance (not analysed):
```
for r in 11306 11308; do echo "== $r"; sed -E 's/\x1b\[[0-9;]*m//g' log/$r/job.log | grep -a -E 'Suites: completed|tests\.[A-Za-z]+ \*\*\* ABORTED' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-160; done
```
```
== 11306
[info] Suites: completed 3, aborted 3
[info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedBootstrappingRoundIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRewardCouponMissingPartyIntegrationTest *** ABORTED ***
== 11308
[info] Suites: completed 3, aborted 3
[info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedBootstrappingRoundIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRewardCouponMissingPartyIntegrationTest *** ABORTED ***
```

## 2. Suite timelines

Artifacts `logs-simtime-3` of each run (`gh run download <run> -n logs-simtime-3 -D log/<ref>/logs-simtime-3`).
```
for f in log/11306/logs-simtime-3 log/11308/logs-simtime-3; do echo "== $f"; zcat $f/canton_network_test.clog.gz | jq -r 'select(.message|test("^Starting test suite|^Test (succeeded|failed)|Timeout while waiting for initialization")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:70]) | \(.message[0:200])"' | awk '$1>"15:10"' | head -8; done
```
(excerpt)
```
== log/11306/logs-simtime-3
15:10:13.731 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest'...
15:17:42.132 ERROR o.l.s.c.ValidatorAppBackendReference:NonZeroRoundBootstrapBftTimeBased | Timeout while waiting for initialization of sv4Validator
15:17:42.345 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest/SV triggers complete the reward pipeline for the initial round', message: Creating environment failed at step Running setup (EnvironmentS
== log/11308/logs-simtime-3
15:20:30.419 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest'...
15:26:07.550 ERROR o.l.s.c.SvAppBackendReference:NonZeroRoundBootstrapBftTimeBasedIntegra | Timeout while waiting for initialization of sv3
15:26:18.018 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest/SV triggers complete the reward pipeline for the initial round', message: Creating environment failed at step Running setup (EnvironmentS
```

## 3. Epoch tables (globalSequencerSv1), both runs

Leaders and blacklisted nodes per epoch, consecutive epochs with the same leader/blacklist set collapsed.
```
for f in log/11306/logs-simtime-3 log/11308/logs-simtime-3; do echo "== $f"; zcat $f/canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-09T15:10") | select(.logger_name|test("globalSequencerSv1")) | select(.message|test("^New epoch")) | "\(.["@timestamp"][11:23]) \(.message|capture("New epoch (?<e>[0-9]+)").e) leaders=\([.message|capture("leaders = List\\((?<l>[^)]*)\\)").l|scan("SEQ::(sv[0-9])")[0]]|join(",")) bl=\([.message|capture("blacklisted nodes = List\\((?<l>[^)]*)\\)").l|scan("SEQ::(sv[0-9])")[0]]|join(","))"' | awk '{k=$3" "$4; if(k!=prev){print; prev=k}}' | head -8; done
```
```
== log/11306/logs-simtime-3
15:10:01.000 62 leaders=sv1 bl=
15:12:09.428 72 leaders=sv1,sv4 bl=
15:12:18.358 73 leaders=sv4,sv1 bl=
15:12:25.130 74 leaders=sv3,sv4,sv1,sv2 bl=
15:12:34.527 75 leaders=sv1,sv2,sv3 bl=sv4
15:12:45.140 76 leaders=sv2,sv3,sv1 bl=sv4
15:12:58.586 77 leaders=sv3,sv1,sv2 bl=sv4
15:13:12.761 78 leaders=sv3,sv4,sv1,sv2 bl=
== log/11308/logs-simtime-3
15:10:27.292 0 leaders=sv1 bl=
15:22:42.344 78 leaders=sv1,sv4 bl=
15:22:52.744 79 leaders=sv4,sv1 bl=
15:23:00.698 80 leaders=sv1,sv2,sv3,sv4 bl=
15:23:17.200 81 leaders=sv1,sv2,sv3 bl=sv4
15:23:30.324 82 leaders=sv2,sv3,sv1 bl=sv4
15:23:45.488 83 leaders=sv3,sv1,sv2 bl=sv4
15:24:00.590 84 leaders=sv1,sv2,sv3,sv4 bl=
```
Both: 1 -> 2 (sv4 alone), then 2 -> 4 (sv2 and sv3 together), then sv4 blacklisted for three epochs. Note: here sv4
is blacklisted although it joined alone one step earlier and was not a newcomer of the 2 -> 4 step (in 11287 and
11288, sv4 was among the newcomers of the multi-node step).

## 4. 11306: sv4Validator stuck on an in-flight installWalletForUser

```
zcat log/11306/logs-simtime-3/canton_network_test.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-09T15:10:13" and .["@timestamp"] < "2026-11-09T15:17:43") | select(.logger_name|test("sv4Validator")) | select(.level!="DEBUG") | "\(.["@timestamp"][11:23]) \(.level) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:230])"' | awk '{m=substr($0,index($0,"|")); gsub(/[0-9]+/,"N",m); c[m]++; if(c[m]<=1) print} END{for(k in c) if(c[k]>3) print c[k], k}' | tail -30
```
(excerpt)
```
15:15:21.361 WARN | Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure
15:16:25.682 INFO | Request (tid:95dda0e351502c4abdec1ec942356e43) com.daml.ledger.api.v2.UpdateService/GetUpdates to 127.0.0.1:15401: failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999616120s. Name resolution delay 0.000000000
15:16:57.175 INFO | Request (tid:ad7261c148edbbf0206bc8cef374b9f6) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15401: failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,ad7261c1): The submission is already in-flight Trailers: Met
15:17:42.132 ERROR | Timeout while waiting for initialization of sv4Validator
15:17:42.165 INFO | sv4Validator app initialization: Onboard validator wallet users failed
21 | The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted): category=Some(ContentionOnSharedResources) ErrorInfoDetail(SUBMISSION_ALREADY_IN_FLIGHT,HashMap(participant -> svNParticipant, physical
54 | Now retrying operation 'installWalletForUser'. 
```

## 5. 11306: request 00:14:43.373Z is that submission, and it gets 1 of 2 results

Confirmation request per mediator: finalized, sent, failed-to-send (`log/<ref>/g3-results.txt` is the extract):
```
F=log/11306/logs-simtime-3/canton-simtime.clog.gz
zcat $F | grep -a -E "00:14:43\.373Z" | jq -r 'select(.logger_name|test("mediator=globalMediatorSv[1-4]")) | select(.message|test("Phase 2: Registered|Phase 6|Sent result|Failed to send")) | "\(.["@timestamp"][11:23]) \(.level) mediator=\(.logger_name|capture("mediator=(?<n>[A-Za-z0-9]+)").n) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:200])"'
```
```
15:12:34.958 INFO mediator=globalMediatorSv1 | Phase 2: Registered request=1970-01-01T00:14:43.373Z from submittingParticipant=PAR::sv4::1220fe897717... with 1 view(s).
15:12:34.958 INFO mediator=globalMediatorSv4 | Phase 2: Registered request=1970-01-01T00:14:43.373Z from submittingParticipant=PAR::sv4::1220fe897717... with 1 view(s).
15:12:35.399 INFO mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.373Z) with verdict Approve at 1970-01-01T00:15:43.373Z
15:12:35.417 INFO mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.373Z) with verdict Approve at 1970-01-01T00:15:43.373Z
15:12:35.453 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:14:43.373Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_
15:12:36.221 INFO mediator=globalMediatorSv3 | Phase 2: Registered request=1970-01-01T00:14:43.373Z from submittingParticipant=PAR::sv4::1220fe897717... with 1 view(s).
15:12:36.306 INFO mediator=globalMediatorSv3 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.373Z) with verdict Approve at 1970-01-01T00:15:43.373Z
15:12:36.995 DEBUG mediator=globalMediatorSv3 | Sent result for request 1970-01-01T00:14:43.373Z
```
The sv4 refusal in full (blacklisting):
```
zcat $F | grep -a 'Failed to send result to sequencer for request 1970-01-01T00:14:43.373Z' | jq -r '"\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:60]) | \(.message|gsub("\\s+";" ")|.[0:420])"'
```
```
15:12:35.453 ERROR c.d.c.s.m.DefaultVerdictSender:mediator=globalMediatorSv4/ps | Failed to send result to sequencer for request 1970-01-01T00:14:43.373Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,e700fe52): Mempool received client request but this node is currently blacklisted, rejecting Request: send-async-versioned/5d15e3fb-4598-4d1c-be44-67846f1a487e DecodedCantonError( code = 'SEQUENCER_OVERLOADE
```
The request is sv4Validator's installWalletForUser (same trace id, sv4Participant):
```
t=$(zcat $F | jq -r 'select(.message|test("Phase 2: Registered request=1970-01-01T00:14:43.373Z")) | .["trace-id"]' | head -1); echo trace=$t
zcat $F | jq -r --arg t "$t" 'select(.["trace-id"]==$t) | select(.logger_name|test("participant=sv4Participant")) | select(.message|test("command|Command|submission|Phase 1|in-flight|userId|actAs")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|sub(".*\\.";"")|.[0:50]) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:260])"' | head -4
```
```
trace=e700fe52d198f23ca5f7be9b38cb09e3
15:12:25.486 DEBUG ApiRequestLogger:participant=sv4Participant | Request com.daml.ledger.api.v2.CommandService/SubmitAndWait by grpc:/127.0.0.1:51038: auth claims: Claims(List(ClaimPublic, ClaimActAsParty(digital-asset-eng-4-f127cade::..61ba), ClaimAdmin),None,Some(sv4_validator_user-f127cade),None,None,true)
15:12:25.486 DEBUG ApiRequestLogger:participant=sv4Participant | Request com.daml.ledger.api.v2.CommandService/SubmitAndWait by grpc:/127.0.0.1:51038: received a message SubmitAndWaitRequest( Commands( "", sv4_validator_user-f127cade, org.lfdecentralizedtrust.splice.validator.installWalletForUser_9ff2723eb4a691cb4a0e358affd
15:12:25.488 INFO CommandSubmissionServiceImpl:participant=sv4Partic | Phase 1 started: Submitting 1 command(s) for interpretation on behalf of digital-asset-eng-4-f127cade::1220fe897717....
15:12:25.488 DEBUG CommandSubmissionServiceImpl:participant=sv4Partic | Submitted commands are: 'create Splice.Wallet.Install:WalletAppInstall'
```
The mediator group had 4 active members with threshold 2 by then:
```
zcat $F | awk '/"@timestamp":"2026-11-09T15:1[0-7]/' | grep -a -o 'MediatorSynchronizerState[^"]\{0,400\}' | grep -o 'active = Seq([^)]*)\|threshold = [0-9]*' | sed -E 's/1220[0-9a-f]{8}/1220/g' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
```
```
486 threshold = 1
192 active = Seq(MED::sv1::1220..., MED::sv4::1220...)
252 active = Seq(MED::sv1::1220..., MED::sv4::1220..., MED::sv3::1220...)
237 active = Seq(MED::sv1::1220..., MED::sv4::1220..., MED::sv3::1220..., MED::sv2::1220...)
237 threshold = 2
```
sv1 held its result back on the sim clock, and sv2's mediator saw its first event after the request:
```
zcat $F | jq -r --arg t "$t" 'select(.["trace-id"]==$t and (.logger_name|contains("globalMediatorSv1")) and (.message|test("Delaying|Phase 6|Sent result"))) | "\(.["@timestamp"][11:23]) \(.message[0:120])"'
zcat $F | jq -r 'select(.["@timestamp"] > "2026-11-09T15:10") | select(.logger_name|contains("mediator=globalMediatorSv2")) | select(.message|test("Received event at")) | "\(.["@timestamp"][11:23]) \(.message[0:120])"' | head -1
zcat $F | awk '/"@timestamp":"2026-11-09T15:1[2-7]/' | grep -a 'Received event at' | jq -r 'select(.logger_name|contains("mediator=globalMediatorSv1")) | "\(.["@timestamp"][11:23]) \(.message|capture("Received event at (?<t>[^ ]+)").t)"' | awk '{if($2>m){m=$2; l=$0}} END{print "latest event seen by globalMediatorSv1 before 15:18:", l}'
```
```
15:12:35.417 Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.373Z) with verdict Approve at 1970-01-01T00:15:43.373Z
15:12:35.419 Delaying sending of verdict by 3000 ms
15:12:38.836 [sv2::12208a64861c...] Received event at 1970-01-01T00:14:43.373004Z with hash SHA-256:116edb780680...
latest event seen by globalMediatorSv1 before 15:18: 15:17:13.939 1970-01-01T00:14:45.306Z
```
So of the four: sv4 refused (blacklisted, epochs 75-77), sv3 sent, sv2 never saw it, sv1 waits until 00:14:46.373 on
a sim clock that stopped at 00:14:45.306. No `Sent result` from sv1 for this request anywhere in the log (the
first command of this section lists every send).

## 6. 11306: sv4Participant stops publishing at the lost request

Last indexer activity per participant (the canton log runs to 15:33:57):
```
zcat log/11306/logs-simtime-3/canton-simtime.clog.gz | grep -a -E 'ParallelIndexerSubscription|InMemoryStateUpdater' | jq -r '"\(.logger_name|capture("participant=(?<n>[A-Za-z0-9]+)").n // "-") \(.["@timestamp"][11:19])"' 2>/dev/null | awk '$2>"15:10" {last[$1]=$2; c[$1]++} END{for(k in c) print k, last[k], c[k]}' | sort
```
```
aliceParticipant 15:28:51 690
bobParticipant 15:28:51 688
splitwellParticipant 15:28:51 735
sv1Participant 15:28:51 984
sv2Participant 15:28:51 870
sv3Participant 15:28:51 945
sv4Participant 15:12:25 761
```
Results and publications on sv4Participant: every request after 00:14:43.284Z (counter 16) gets its result, but
nothing is stored after counter 16; counter 17 (00:14:43.373Z) never gets a result:
```
zcat log/11306/logs-simtime-3/canton-simtime.clog.gz | awk '/"@timestamp":"2026-11-09T15:1[2-7]/' | grep -a 'participant=sv4Participant' | jq -r 'select(.message|test("Got result for|Schedule publication|Phase 7|timed out")) | "\(.["@timestamp"][11:23]) \(.logger_name|split(":")[0]|split(".")[-1]) | \(.message|gsub("\\s+";" ")|.[0:130])"' | awk '$1>"15:12:22"' | head -12
```
```
15:12:22.740 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.270Z): OpenEnvelope( SignedProtocolMessage( ConfirmationResult
15:12:22.748 RecordOrderPublisher | Schedule publication for request counter 15
15:12:22.750 ParallelIndexerSubscription | Phase 7: Storing at offset=234 SequencedTransactionAccepted( recordTime = 1970-01-01T00:14:43.270Z, updateId = 122091be3b9b7ab86b0
15:12:24.408 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.284Z): OpenEnvelope( SignedProtocolMessage( ConfirmationResult
15:12:24.416 RecordOrderPublisher | Schedule publication for request counter 16
15:12:24.418 ParallelIndexerSubscription | Phase 7: Storing at offset=245 SequencedTransactionAccepted( recordTime = 1970-01-01T00:14:43.284Z, updateId = 122001b8d7a4bc074e6
15:12:37.668 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.385Z): OpenEnvelope( SignedProtocolMessage( ConfirmationResult
15:12:37.679 RecordOrderPublisher | Schedule publication for request counter 18
15:12:39.542 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.385001Z): OpenEnvelope( SignedProtocolMessage( ConfirmationRes
15:12:39.550 RecordOrderPublisher | Schedule publication for request counter 19
15:12:40.429 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.406Z): OpenEnvelope( SignedProtocolMessage( ConfirmationResult
15:12:40.446 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.406001Z): OpenEnvelope( SignedProtocolMessage( ConfirmationRes
```
Only sv4 hosts the informees of 00:14:43.373Z (confirmers digital-asset-eng-4 and sv4 in its Phase 2 line), so only
sv4Participant froze; the GetUpdates timeout and the SUBMISSION_ALREADY_IN_FLIGHT retries of section 4 follow from it.

## 7. 11308: the sv3 SV app's own transaction is never published

The sv3 SV app's last calls (DsoRules_SetSynchronizerNodeConfig times out, retries fail):
```
zcat log/11308/logs-simtime-3/canton_network_test.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-09T15:22:30" and .["@timestamp"] < "2026-11-09T15:26:08") | select(.logger_name|test("/SV=sv3($|/)")) | select(.level!="DEBUG") | select(.message|test("Processing Tick|no-op|PeriodicTask|Retrieving tasks|polling loop|Sending upload dar")|not) | "\(.["@timestamp"][11:23]) \(.level) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:200])"' | awk '{m=substr($0,index($0,"|")); k=m; gsub(/[0-9]+/,"N",k); if(!(k in f)){f[k]=$1; o[++n]=k; s[k]=$0} l[k]=$1; c[k]++} END{for(i=1;i<=n;i++){k=o[i]; print f[k]"-"l[k], c[k], substr(s[k], index(s[k],"|"))}}' | cut -c1-260 | tail -40
```
(excerpt)
```
15:24:41.135-15:24:41.135 1 | Request (tid:8c8c2b5cb9943c17d363d708cc050f4f) com.daml.ledger.api.v2.UpdateService/GetUpdates to 127.0.0.1:15301: failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999414308s. Nam
15:25:22.199-15:25:22.199 1 | Request (tid:9ad02093fd2b4a84bf000fa410260f96) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 1
15:25:22.200-15:25:22.200 1 | The operation 'setting domain config for digital-asset-eng-3-b75ebeee::12207bc0d578...' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after
15:25:22.446-15:26:06.464 15 | The operation 'setting domain config for digital-asset-eng-3-b75ebeee::12207bc0d578...' failed with a retryable error (full stack trace omitted): category=Some(InvalidGivenCurrentSystemStateOther) Err
```
That submission (trace 9ad02093...) is confirmation request 00:14:43.449004Z, submitted by sv3Participant at
15:23:22.206:
```
F=log/11308/logs-simtime-3/canton-simtime.clog.gz
zcat $F | jq -r 'select(.["trace-id"]=="9ad02093fd2b4a84bf000fa410260f96") | select(.message|test("Phase 1 started|Submitted commands|Phase 2: Registered")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>(sequencer|mediator|participant)=[A-Za-z0-9]+)").n // "-") | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:180])"' | head -3
```
```
15:23:22.206 INFO participant=sv3Participant | Phase 1 started: Submitting 1 command(s) for interpretation on behalf of digital-asset-eng-3-b75ebeee::12207bc0d578....
15:23:22.206 DEBUG participant=sv3Participant | Submitted commands are: 'exercise @Splice.DsoRules:DsoRules DsoRules_SetSynchronizerNodeConfig'
15:23:22.997 INFO mediator=globalMediatorSv1 | Phase 2: Registered request=1970-01-01T00:14:43.449004Z from submittingParticipant=PAR::sv3::12207bc0d578... with 1 view(s).
```
It got two results (sv2 and sv1 sent, sv4 refused), both batches were pre-prepared in epoch 81 (blocks 1673, 1675),
and sv3Participant received the result at 15:23:23.937 (next command). It was never published: the indexers of all
four SV participants stop at 15:23:19 (the canton log runs to 15:42:38):
```
zcat $F | grep -a -E 'ParallelIndexerSubscription|InMemoryStateUpdater' | jq -r '"\(.logger_name|capture("participant=(?<n>[A-Za-z0-9]+)").n // "-") \(.["@timestamp"][11:19])"' 2>/dev/null | awk '$2>"15:10" {last[$1]=$2; c[$1]++} END{for(k in c) print k, last[k], c[k]}' | sort
zcat $F | awk '/"@timestamp":"2026-11-09T15:2[2-6]/' | grep -a 'participant=sv3Participant' | jq -r 'select(.message|test("Got result for|Schedule publication|Phase 7: Storing")) | "\(.["@timestamp"][11:23]) \(.logger_name|split(":")[0]|split(".")[-1]) | \(.message|gsub("\\s+";" ")|.[0:130])"' | awk '$1>"15:23:15"' | head -10
```
```
aliceParticipant 15:37:30 3066
bobParticipant 15:37:30 2554
splitwellParticipant 15:37:30 2939
sv1Participant 15:23:19 3298
sv2Participant 15:23:19 722
sv3Participant 15:23:19 622
sv4Participant 15:23:19 777
15:23:17.692 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.330Z): OpenEnvelope( SignedProtocolMessage( ConfirmationResult
15:23:17.778 RecordOrderPublisher | Schedule publication for request counter 8
15:23:17.780 ParallelIndexerSubscription | Phase 7: Storing at offset=201 SequencedTransactionAccepted( recordTime = 1970-01-01T00:14:43.330Z, updateId = 1220e77421279eb4d6b
15:23:19.414 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.413001Z): OpenEnvelope( SignedProtocolMessage( ConfirmationRes
15:23:19.436 RecordOrderPublisher | Schedule publication for request counter 9
15:23:19.440 ParallelIndexerSubscription | Phase 7: Storing at offset=208 SequencedTransactionAccepted( recordTime = 1970-01-01T00:14:43.413001Z, updateId = 1220244071f82313
15:23:23.501 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.446Z): OpenEnvelope( SignedProtocolMessage( ConfirmationResult
15:23:23.526 RecordOrderPublisher | Schedule publication for request counter 12
15:23:23.937 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:14:43.449004Z): OpenEnvelope( SignedProtocolMessage( ConfirmationRes
15:23:23.949 RecordOrderPublisher | Schedule publication for request counter 13
```
Counters 10 and 11 never get a result.

## 8. 11308: the two lost requests, 00:14:43.413002Z and 00:14:43.413003Z

```
for q in '00:14:43\.413002Z' '00:14:43\.413003Z'; do echo "== $q"; zcat $F | grep -a -E "$q" | jq -r 'select(.logger_name|test("mediator=globalMediatorSv[1-4]")) | select(.message|test("Phase 2: Registered|Phase 6|Sent result|Failed to send")) | "\(.["@timestamp"][11:23]) \(.level) mediator=\(.logger_name|capture("mediator=(?<n>[A-Za-z0-9]+)").n) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:200])"'; done
```
```
== 00:14:43\.413002Z
15:23:17.706 INFO mediator=globalMediatorSv4 | Phase 2: Registered request=1970-01-01T00:14:43.413002Z from submittingParticipant=PAR::sv2::1220d91f98dc... with 1 view(s).
15:23:17.745 INFO mediator=globalMediatorSv1 | Phase 2: Registered request=1970-01-01T00:14:43.413002Z from submittingParticipant=PAR::sv2::1220d91f98dc... with 1 view(s).
15:23:17.752 INFO mediator=globalMediatorSv2 | Phase 2: Registered request=1970-01-01T00:14:43.413002Z from submittingParticipant=PAR::sv2::1220d91f98dc... with 1 view(s).
15:23:18.947 INFO mediator=globalMediatorSv2 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413002Z) with verdict Approve at 1970-01-01T00:15:43.413002Z
15:23:18.949 INFO mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413002Z) with verdict Approve at 1970-01-01T00:15:43.413002Z
15:23:18.965 INFO mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413002Z) with verdict Approve at 1970-01-01T00:15:43.413002Z
15:23:19.027 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:14:43.413002Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENC
15:23:19.196 DEBUG mediator=globalMediatorSv2 | Sent result for request 1970-01-01T00:14:43.413002Z
== 00:14:43\.413003Z
15:23:17.726 INFO mediator=globalMediatorSv4 | Phase 2: Registered request=1970-01-01T00:14:43.413003Z from submittingParticipant=PAR::sv2::1220d91f98dc... with 1 view(s).
15:23:17.750 INFO mediator=globalMediatorSv2 | Phase 2: Registered request=1970-01-01T00:14:43.413003Z from submittingParticipant=PAR::sv2::1220d91f98dc... with 1 view(s).
15:23:17.770 INFO mediator=globalMediatorSv1 | Phase 2: Registered request=1970-01-01T00:14:43.413003Z from submittingParticipant=PAR::sv2::1220d91f98dc... with 1 view(s).
15:23:18.921 INFO mediator=globalMediatorSv2 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413003Z) with verdict Approve at 1970-01-01T00:15:43.413003Z
15:23:18.932 INFO mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413003Z) with verdict Approve at 1970-01-01T00:15:43.413003Z
15:23:18.946 INFO mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413003Z) with verdict Approve at 1970-01-01T00:15:43.413003Z
15:23:19.018 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:14:43.413003Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENC
15:23:19.413 DEBUG mediator=globalMediatorSv1 | Sent result for request 1970-01-01T00:14:43.413003Z
```
(the Phase 2 Initial state lines, omitted here, show `Quorum(confirmers = Map(DSO-b75ebeee-b75ebeee::...`: DSO-party
requests, so every SV participant is an informee, which is why all four froze.)
The missing second result was delayed on the sim clock in both; the sv3 mediator started after them; the sim clock
stopped before the delay ran out:
```
for x in "00:14:43.413002Z globalMediatorSv1" "00:14:43.413003Z globalMediatorSv2"; do set -- $x; t=$(zcat $F | jq -r --arg q "Phase 6: Finalized request=RequestId(1970-01-01T$1)" --arg m "$2" 'select(.message|startswith($q)) | select(.logger_name|contains($m)) | .["trace-id"]' | head -1); echo "== $1 $2 trace=$t"; zcat $F | jq -r --arg t "$t" --arg m "$2" 'select(.["trace-id"]==$t and (.logger_name|contains($m)) and (.message|test("Delaying|Phase 6|Sent result"))) | "\(.["@timestamp"][11:23]) \(.message[0:120])"'; done
zcat $F | awk '/"@timestamp":"2026-11-09T15:2[0-6]/' | grep -a 'mediator=globalMediatorSv3' | grep -a 'Received event at' | jq -r '"\(.["@timestamp"][11:23]) \(.message[0:110])"' | head -1
zcat $F | awk '/"@timestamp":"2026-11-09T15:2[2-6]/' | grep -a 'Received event at' | jq -r 'select(.logger_name|contains("mediator=globalMediatorSv1")) | "\(.["@timestamp"][11:23]) \(.message|capture("Received event at (?<t>[^ ]+)").t)"' | awk '$1<"15:26:10" {if($2>m){m=$2; l=$0}} END{print l}'
```
```
== 00:14:43.413002Z globalMediatorSv1 trace=d3636280a8a665ce4a77bca1a32a6935
15:23:18.965 Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413002Z) with verdict Approve at 1970-01-01T00:15:43.413002Z
15:23:18.968 Delaying sending of verdict by 3000 ms
== 00:14:43.413003Z globalMediatorSv2 trace=c0be9197445db209ed534667fa647d39
15:23:18.921 Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.413003Z) with verdict Approve at 1970-01-01T00:15:43.413003Z
15:23:18.923 Delaying sending of verdict by 3000 ms
15:23:21.942 [sv3::1220b819aa4f...] Received event at 1970-01-01T00:14:43.413007Z with hash SHA-256:b148f4a243d9...
15:25:29.997 1970-01-01T00:14:44.297Z
```
Latest record time 00:14:44.297 before the test gave up; the delayed sends were due at 00:14:46.41. Same shape as
11306 and as 11275, 11284, 11287.

## 9. Duplicate check

11255 comment and the 2026-11-09 nightly round (11284, NonZeroRoundBootstrapBftTimeBasedIntegrationTest on the mainnet
nightly, folded into 11255) describe this shape: blacklisted sv4's result refused, a newly added mediator not yet
subscribed, the remaining mediator's verdict delayed on the sim clock, participants frozen in sim time.
```
grep -l 'NonZeroRoundBootstrapBft' ci-triage/evidence/*.md
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"\(.state) comments=\(.comments) assignees=\([.assignees[].login]|join(",")) \(.title)"'
gh pr view 8356 -R canton-network/splice --json state,mergedAt,title --jq '"\(.state) \(.mergedAt) \(.title)"'
```
```
ci-triage/evidence/11140-bft-scan-refresh-warn-after-suppression.md
ci-triage/evidence/11274-11275-11276-mainnet-nightly-tag-and-lost-verdict.md
ci-triage/evidence/11281-11282-11283-11284-mainnet-nightly-1109.md
open comments=1 assignees=rautenrieth-da Timeouts due to changing bft ordering topology
OPEN null Disable BFT sequencer blacklisting in integration tests
```
(11140 mentions the suite only in a suite list.) Post-merge main runs on 2026-11-09 with a failed simtime job (the
runs listed in this round's README and the nightly round):
```
for RUN in 42591855865 42608940134 42618450792 42634636103 42637895044 42639073931 42639148146; do gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?per_page=100" --jq "[.jobs[] | select(.name|test(\"/ simtime \\\\([0-9]\\\\)\$\")) | select(.conclusion==\"failure\") | (.name|sub(\".*/ \";\"\"))] | \"$RUN \" + join(\", \")"; done
```
```
42591855865 simtime (3)
42608940134 simtime (4)
42618450792 simtime (3)
42634636103 simtime (5)
42637895044 simtime (3)
42639073931 simtime (4)
42639148146 simtime (3)
```
11287 (42591855865) and 11288 (42608940134) are 11255 duplicates; 42618450792 was not triaged here; 11299
(42634636103) and 11305 (42639073931) are other groups of this round.

## Verdict

- 11306: duplicate of 11255, lost-verdict shape (sv4Validator's own installWalletForUser request; only sv4Participant
  froze).
- 11308: duplicate of 11255, lost-verdict shape (two DSO-party requests of sv2; all four SV participants froze, the
  sv3 SV app's own transaction was decided but never published).
- In both the blacklisted sv4 refused one result; splice#8356 (disable blacklisting in tests, open) would have let
  sv4's result through, which alone gives the second result in both runs. Not verified by a rerun.
- New observation for the family: sv4 was blacklisted after a 2 -> 4 step in which it was not a newcomer (it had
  joined alone at 1 -> 2).
- Flake, Canton BFT onboarding window plus sim-clock delayed verdicts; no splice change beyond the 11255 options.
- Not verified: why the three following suites aborted (same Canton instance, sv4Participant or all SV participants
  frozen; not analysed); why sv4 is the blacklisted node.
