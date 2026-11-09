# 11287, 11288 - simtime on main: lost verdict (sv4 blacklisted) and rejected MED::sv4 proposal in the BFT onboarding window (runs 42591855865, 42608940134)

DUPLICATES of 11255 (BFT ordering-topology onboarding window), one of each shape. Both on post-merge main, canton
3.7.2, 2026-11-09:

- 11287, simtime (3), main 9dafafbece: lost-verdict shape (as 11275, 11284). SvTimeBasedRoundMgmtIntegrationTest
  "round management" fails in initDso: sv3Validator never initializes because its installWalletForUser submission
  stays in flight. Its confirmation request 00:00:00.257Z (sim time) needs 2 mediator verdicts from a group of 4
  (sv1, sv3, sv4, sv2): sv3 sent; sv4 was refused by its blacklisted sequencer; sv2 subscribed after the request
  (first event 00:00:00.343002Z); sv1 was held back 3 s by the delayed verdict sender on the sim clock and sent only
  at 10:07:46.991, after the test had failed (10:07:29). The other three test cases of the suite and the rest of the
  shard passed (8 succeeded, 1 failed).
- 11288, simtime (4), main bd9953a523: rejected-send shape (as 11255 itself and 11282). At the switch from 1 to 4
  sequencers (epoch 13, 11:39:58.737) the MediatorSynchronizerState proposal adding MED::sv4, broadcast by all four SV
  participants, was rejected with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`; the
  outboxes stayed in that flush cycle, `check established Add mediator MED::sv4` gave up at 11:41:53.964, and
  sv4Validator gave up waiting for `sv4_validator_user` at 11:44:21 (JVM exit during setup of
  TrafficBasedRewardsSvAppTimeBasedIntegrationTest, no test report).

Epoch tables: in 11287 sv3 joined alone (1 -> 2, epoch 13) and nobody was blacklisted; sv2 and sv4 joined together
(2 -> 4, epoch 15) and sv4 was blacklisted for epochs 16-18. In 11288 sv2, sv3, sv4 joined in one step (1 -> 4,
epoch 13) and sv4 was blacklisted for epochs 14-16. Both fit addendum (2) of the 11274-11276 packet (blacklisting only
after a step adding two or more sequencers; the blacklisted node is always sv4).

Hit rate: 5 of the 13 post-merge main runs since 2026-11-06 failed in this family (11241/11242, 11255, 11258, 11287,
11288), both runs of 2026-11-09 among them; plus the mainnet-Daml nightly on 11-07 and 11-09. The other 4 failed
post-merge runs in that window were not checked.

- Runs: https://github.com/canton-network/splice/actions/runs/42591855865 (main 9dafafbece, "TBAR v1.1 featured app
  config update validation (#7720)"), job 129076454622 `ci / scala_test_sim_time / simtime (3)`, attempt 1;
  https://github.com/canton-network/splice/actions/runs/42608940134 (main bd9953a523, "Remove old comment (#8352)"),
  job 129132359292 `ci / scala_test_sim_time / simtime (4)`, attempt 1.
- Runtime canton: 3.7.2 at both shas.
- Component: Canton BFT orderer onboarding window (trigger) plus simtime test setup (nothing advances the sim clock
  during setup; delayed verdict sender scheduled on the sim clock).

## 1. Jobs and canton pin

```
for r in 42591855865 42608940134; do gh api --paginate "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=100" --jq '.jobs[] | select(.conclusion=="failure") | [.id,.run_attempt,.name]|@tsv'; done
for s in 9dafafbece16a00aeeae5451878a35b9af414976 bd9953a523216c6c095e4fdc957aadb3b93f09bd; do gh api "repos/canton-network/splice/contents/nix/canton-sources.json?ref=$s" --jq .content | base64 -d | grep -m1 version; done
```
```
129076454622	1	ci / scala_test_sim_time / simtime (3)
129132359292	1	ci / scala_test_sim_time / simtime (4)
  "version": "3.7.2",
  "version": "3.7.2",
```
Each issue's `Job:` line matches exactly one failed job of its run.

## 2. Classification

```
for r in 11287 11288; do echo "== $r"; sed -E 's/\x1b\[[0-9;]*m//g' log/$r/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]|Initialize app failed|exited|Starting test suite' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-250; done
```
```
== 11287
##[error]Error: failed to run script step (id 16ab6260-dc24-11f1-a563-fdb9e82ac791): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - round management *** FAILED ***
[info] Run completed in 20 minutes, 32 seconds.
[info] Tests: succeeded 8, failed 1, canceled 0, ignored 1, pending 0
== 11288
##[error]Error: failed to run script step (id bdcd4290-dc31-11f1-a98b-0dc54235f704): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
java.lang.RuntimeException: sv4Validator app initialization: Initialize app failed
```
11287 stack head (`grep -a -A14 'FAILED \*\*\*'`):
```
[info] - round management *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
...
[info]   at org.lfdecentralizedtrust.splice.console.ValidatorAppReference.waitForInitialization(ValidatorAppReference.scala:52)
...
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.initDso(SpliceTests.scala:313)
...
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRoundMgmtIntegrationTest.$anonfun$new$1(SvTimeBasedRoundMgmtIntegrationTest.scala:13)
```
Not 11274: `grep -a -c 'may not be dropped'` on both job logs = 0.

## 3. Suite timelines

Artifacts `logs-simtime-3` in `log/11287/`, `logs-simtime-4` in `log/11288/`.
```
for f in log/11287/logs-simtime-3 log/11288/logs-simtime-4; do echo "== $f"; zcat $f/canton_network_test.clog.gz | jq -r 'select(.message|test("^Starting test suite|^Test (succeeded|failed)|^Starting test run|Initialization failed|Initialize app failed")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:70]) | \(.message[0:200])"' | head -30; done
```
```
== log/11287/logs-simtime-3
09:58:56.821 INFO c.d.c.LogReporter:reporter=scala-test | Starting test run...
09:59:00.155 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...
10:07:27.791 INFO o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d5b709 | sv3Validator app initialization: Initialize app failed
...
10:07:29.165 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'SvTimeBasedRoundMgmtIntegrationTest/round management', message: Condition never became true within 5 minutes, location: SeeStackDepthException
10:08:54.653 INFO c.d.c.LogReporter:reporter=scala-test | Test succeeded: 'SvTimeBasedRoundMgmtIntegrationTest/round management with scheduled config change of doubled tickDuration'
10:10:52.451 INFO c.d.c.LogReporter:reporter=scala-test | Test succeeded: 'SvTimeBasedRoundMgmtIntegrationTest/round management with scheduled config change of reduced tickDuration'
10:12:08.832 INFO c.d.c.LogReporter:reporter=scala-test | Test succeeded: 'SvTimeBasedRoundMgmtIntegrationTest/round management with very tightly scheduled config'
... (WalletAppRewards, SvTimeBasedOnboarding, TokenStandardCliTestData, WalletTxLogWithRewardsCollection: all succeeded)
== log/11288/logs-simtime-4
11:36:35.502 INFO c.d.c.LogReporter:reporter=scala-test | Starting test run...
11:36:36.107 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
11:44:21.156 INFO o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/ | sv4Validator app initialization: Initialize app failed
11:44:21.156 ERROR o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/ | sv4Validator app initialization: Initialization failed
```
(The 10:07:27 sv3Validator line is the shutdown after the test gave up; the test-side line is
`10:07:14.971 ERROR ... Timeout while waiting for initialization of sv3Validator`, section 5.)

## 4. Epoch tables (globalSequencerSv1), both runs

Leaders and blacklisted nodes per epoch, consecutive epochs with the same leader/blacklist set collapsed.
```
for f in log/11287/logs-simtime-3 log/11288/logs-simtime-4; do echo "== $f"; zcat $f/canton-simtime.clog.gz | jq -r 'select(.logger_name|test("globalSequencerSv1")) | select(.message|test("^New epoch")) | "\(.["@timestamp"][11:23]) \(.message|capture("New epoch (?<e>[0-9]+)").e) leaders=\([.message|capture("leaders = List\\((?<l>[^)]*)\\)").l|scan("SEQ::(sv[0-9])")[0]]|join(",")) bl=\([.message|capture("blacklisted nodes = List\\((?<l>[^)]*)\\)").l|scan("SEQ::(sv[0-9])")[0]]|join(","))"' | awk '{k=$3" "$4; if(k!=prev){print; prev=k}}' | head -12; done
```
```
== log/11287/logs-simtime-3
09:59:47.508 0 leaders=sv1 bl=
10:01:35.934 13 leaders=sv3,sv1 bl=
10:01:44.714 14 leaders=sv1,sv3 bl=
10:01:52.043 15 leaders=sv4,sv1,sv2,sv3 bl=
10:02:02.787 16 leaders=sv2,sv3,sv1 bl=sv4
10:02:12.937 17 leaders=sv3,sv1,sv2 bl=sv4
10:02:27.102 18 leaders=sv1,sv2,sv3 bl=sv4
10:02:41.029 19 leaders=sv4,sv1,sv2,sv3 bl=
== log/11288/logs-simtime-4
11:37:51.258 0 leaders=sv1 bl=
11:39:58.737 13 leaders=sv2,sv3,sv4,sv1 bl=
11:40:20.009 14 leaders=sv3,sv1,sv2 bl=sv4
11:40:31.221 15 leaders=sv1,sv2,sv3 bl=sv4
11:40:45.778 16 leaders=sv2,sv3,sv1 bl=sv4
11:41:00.462 17 leaders=sv2,sv3,sv4,sv1 bl=
```
11287: 1 -> 2 (sv3 alone, epoch 13), no blacklisting in epochs 13-14; 2 -> 4 (sv2 and sv4, epoch 15), sv4
blacklisted 16-18. 11288: 1 -> 4 (epoch 13), sv4 blacklisted 14-16.

## 5. 11287: sv3Validator stuck on an in-flight installWalletForUser

```
zcat log/11287/logs-simtime-3/canton_network_test.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-09T10:02:00" and .["@timestamp"] < "2026-11-09T10:07:27") | select(.logger_name|test("sv3Validator")) | select(.level!="DEBUG") | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|sub(".*sv3Validator";"")|.[0:40]) | \(.message|gsub("\\s+";" ")|.[0:220])"' | awk '{m=substr($0,index($0,"|")); gsub(/[0-9]+/,"N",m); c[m]++; if(c[m]<=1) print} END{for(k in c) if(c[k]>5) print c[k], k}' | tail -25
```
(excerpt)
```
10:04:53.265 WARN  | Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure
10:05:23.570 INFO  | Request (tid:37c4c2a4f25ec7716433978722558324) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15301: failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,37c4c2a4): The submission is already in-flight Tra
10:07:14.971 ERROR  | Timeout while waiting for initialization of sv3Validator
21 | The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted): category=Some(ContentionOnSharedResources) ErrorInfoDetail(SUBMISSION_ALREADY_IN_FLIGHT,HashMap(participant -> svNParticipant
60 | Now retrying operation 'installWalletForUser'. 
```

## 6. 11287: the request that never completes, 00:00:00.257Z

Only sv3Participant (the confirmer) has request .257Z in Phase 3; confirmers sv3 and the sv3 operator party, quorum
reached at 10:02:03.19x on sv1 and sv3:
```
F=log/11287/logs-simtime-3/canton-simtime.clog.gz
zcat $F | jq -r 'select(.message|test("00:00:00\\.257Z")) | select(.logger_name|test("mediator=globalMediatorSv[1-4]")) | select(.message|test("Phase 6|Sent result|Failed to send|threshold|Delaying")) | "\(.["@timestamp"][11:23]) \(.level) mediator=\(.logger_name|capture("mediator=(?<n>[A-Za-z0-9]+)").n) | \(.message|gsub("\\s+";" ")|.[0:230])"'
```
```
10:01:51.558 DEBUG mediator=globalMediatorSv3 | Phase 2: Initial state for request=1970-01-01T00:00:00.257Z: ViewPosition("") -> ViewState( quorumsState = Quorum(confirmers = Map(digital-asset-eng-3-d5b70989::1220a6cbbe2c... -> 1, sv3::1220a6cbbe2c... -> 1), threshold = 2), con
10:01:51.558 DEBUG mediator=globalMediatorSv1 | Phase 2: Initial state for request=1970-01-01T00:00:00.257Z: ViewPosition("") -> ViewState( quorumsState = Quorum(confirmers = Map(digital-asset-eng-3-d5b70989::1220a6cbbe2c... -> 1, sv3::1220a6cbbe2c... -> 1), threshold = 2), con
10:02:03.194 DEBUG mediator=globalMediatorSv3 | RequestId(1970-01-01T00:00:00.257Z)(view position ViewPosition("")): Received an approval and reached consortium thresholds for parties: Set(digital-asset-eng-3-d5b70989::1220a6cbbe2c..., sv3::1220a6cbbe2c...)
10:02:03.195 DEBUG mediator=globalMediatorSv1 | RequestId(1970-01-01T00:00:00.257Z)(view position ViewPosition("")): Received an approval and reached consortium thresholds for parties: Set(digital-asset-eng-3-d5b70989::1220a6cbbe2c..., sv3::1220a6cbbe2c...)
10:02:03.199 INFO mediator=globalMediatorSv3 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.257Z) with verdict Approve at 1970-01-01T00:01:00.257Z
10:02:03.199 INFO mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.257Z) with verdict Approve at 1970-01-01T00:01:00.257Z
10:02:03.566 DEBUG mediator=globalMediatorSv3 | Sent result for request 1970-01-01T00:00:00.257Z
10:02:05.911 DEBUG mediator=globalMediatorSv4 | Phase 2: Initial state for request=1970-01-01T00:00:00.257Z: ViewPosition("") -> ViewState( quorumsState = Quorum(confirmers = Map(digital-asset-eng-3-d5b70989::1220a6cbbe2c... -> 1, sv3::1220a6cbbe2c... -> 1), threshold = 2), con
10:02:05.969 DEBUG mediator=globalMediatorSv4 | RequestId(1970-01-01T00:00:00.257Z)(view position ViewPosition("")): Received an approval and reached consortium thresholds for parties: Set(digital-asset-eng-3-d5b70989::1220a6cbbe2c..., sv3::1220a6cbbe2c...)
10:02:05.971 INFO mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.257Z) with verdict Approve at 1970-01-01T00:01:00.257Z
10:02:06.023 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:00:00.257Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,800a817a): Mempoo
10:07:46.991 DEBUG mediator=globalMediatorSv1 | Sent result for request 1970-01-01T00:00:00.257Z
```
The threshold in the Phase 2 lines is the confirmation quorum. The mediator group:
```
zcat $F | grep -a -o 'MediatorSynchronizerState[^"]\{0,400\}' | grep -o 'active = Seq([^)]*)\|threshold = [0-9]*' | sed -E 's/1220[0-9a-f]{8}/1220/g' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
```
```
544 threshold = 1
253 active = Seq(MED::sv1::1220..., MED::sv3::1220..., MED::sv4::1220...)
238 active = Seq(MED::sv1::1220..., MED::sv3::1220..., MED::sv4::1220..., MED::sv2::1220...)
190 active = Seq(MED::sv1::1220..., MED::sv3::1220...)
238 threshold = 2
```
The sv1 delay, same trace id as its Finalized line:
```
t=$(zcat $F | jq -r 'select(.message|test("Phase 6: Finalized request=RequestId\\(1970-01-01T00:00:00.257Z")) | select(.logger_name|contains("globalMediatorSv1")) | .["trace-id"]'); echo trace=$t
zcat $F | jq -r --arg t "$t" 'select(.["trace-id"]==$t and (.logger_name|contains("globalMediatorSv1")) and (.message|test("Delaying|Phase 6|Sent result"))) | "\(.["@timestamp"][11:23]) \(.message[0:120])"'
```
```
trace=800a817a19a72e418417aeca13ac9088
10:02:03.199 Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.257Z) with verdict Approve at 1970-01-01T00:01:00.257Z
10:02:03.200 Delaying sending of verdict by 3000 ms
10:07:46.991 Sent result for request 1970-01-01T00:00:00.257Z
```
sv1 delays, so the group already had 4 active members with threshold 2 (VerdictSender rule, addendum of the
11274-11276 packet: with 3 active and threshold 2 nobody waits). The 4th member, sv2, subscribed after the request:
```
zcat $F | jq -r 'select(.logger_name|contains("mediator=globalMediatorSv2")) | select(.message|test("Received event at")) | "\(.["@timestamp"][11:23]) \(.message[0:120])"' | head -2
```
```
10:02:06.797 [sv2::122060ef5c6c...] Received event at 1970-01-01T00:00:00.343002Z with hash SHA-256:7250de1fabc7...
10:02:06.856 [sv2::122060ef5c6c...] Received event at 1970-01-01T00:00:00.343003Z with hash SHA-256:68fbe4bf0760...
```
So of the four: sv3 sent, sv4 refused (blacklisted, epochs 16-18), sv2 never saw it, sv1 delayed on the sim clock.
The result needs 2; it got 1 until sv1 sent at 10:07:46.991, 17 s after the test failed. Same shape as 11275 and
11284.

## 7. 11288: the MED::sv4 proposal rejected at the switch to 4

Rejected sends at 11:39:59, 0.3 s after epoch 13 (1 -> 4) started:
```
F=log/11288/logs-simtime-4/canton-simtime.clog.gz
zcat $F | jq -r 'select(.["@timestamp"] > "2026-11-09T11:39:58" and .["@timestamp"] < "2026-11-09T11:40:01") | select(.message|test("Rejecting submission request|Request to send submission|returned an error however")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>(sequencer|mediator|participant)=[A-Za-z0-9]+)").n // "-") | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:260])"' | head -40
```
(excerpt)
```
11:39:59.031 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id afe967b9-76ca-43f2-a522-074f0357ca29 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv1::1220e6f9860c... to Set(All). 
11:39:59.041 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv1::1220e6f9860c...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
11:39:59.057 INFO participant=sv1Participant | Send [afe967b9-76ca-43f2-a522-074f0357ca29] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-DefaultSequencer-0. Is the server running? Did you configure the server address as 0.0.
11:39:59.305 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv4::1220c8d77f17...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
```
The rejected sends of sv1 (afe967b9), sv2 (4ed04bbe), sv3 (27c6d640) and sv4 (c77b27e8) are topology outbox flushes of
the same transaction, TxHash c2c2d0bd49e8 (by trace id of the `Sending message ID` line):
```
for id in afe967b9-76ca-43f2-a522-074f0357ca29 c77b27e8-0f34-407d-9bf5-7b97c878abb7 27c6d640-4629-4aca-9347-83c2457f5eb2 4ed04bbe-de7d-4a58-bd1e-0bcb3a36c6cb; do t=$(zcat $F | jq -r --arg id $id 'select(.message|contains("Sending message ID "+$id)) | .["trace-id"]' | head -1); echo "$id trace=$t"; zcat $F | jq -r --arg t "$t" 'select(.["trace-id"]==$t) | select(.logger_name|test("Outbox|Topology|Broadcast")) | "\(.["@timestamp"][11:23]) \(.logger_name|sub("c.d.c.";"")|.[0:60]) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:200])"' | head -3; done
```
(one of four, the others identical apart from participant and time)
```
afe967b9-76ca-43f2-a522-074f0357ca29 trace=925b673a5eb2958fb4184b3fb57b9795
11:39:59.024 t.QueueBasedSynchronizerOutbox:participant=sv1Participant/ps | Invoked flush with queue size 1
11:39:59.024 t.SynchronizerOutboxQueue:participant=sv1Participant/psid=gl | dequeuing: List(Traced(TxHash(SHA-256:c2c2d0bd49e8...), tid:18a97966046d3648b99bd529f84fab7c))
```
That transaction is the MediatorSynchronizerState proposal adding MED::sv4, submitted by the sv4 SV app:
```
zcat log/11288/logs-simtime-4/canton_network_test.clog.gz | jq -r 'select(.message|test("Add mediator MED::sv4")) | select(.level!="DEBUG") | "\(.["@timestamp"][11:23]) \(.level) \(.message|gsub("\\s+";" ")|.[0:200])"' | awk '{m=substr($0,24); gsub(/[0-9]+/,"N",m); if(!(m in s)){s[m]=1; print}}' | head -8
```
```
11:39:58.994 INFO Ensuring that Add mediator MED::sv4::1220bca3c7b3...
11:39:59.005 INFO No proposal found for Add mediator MED::sv4::1220bca3c7b3..., submitting new one.
11:39:59.025 INFO Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::12207658555f..., group = 0, threshold = 2, active = Seq(MED::sv1::1220cad8edd3..., MED::sv3::1220177756cb..., MED::sv2::12
11:39:59.028 INFO The operation 'check established Add mediator MED::sv4::1220bca3c7b3...' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: Mediato
...
11:41:53.964 INFO The operation 'check established Add mediator MED::sv4::1220bca3c7b3...' has failed with an exception. Total maximum number of retries 35 exceeded. Giving up.
```
On sv3Participant the stored MediatorSynchronizerState with sv4 only ever appears as a proposal (11:41:54.734 and
11:43:56.925); the re-proposals found the outbox still in the old flush cycle:
```
zcat $F | jq -r 'select(.message|test("Another flush cycle")) | select(.["@timestamp"] > "2026-11-09T11:41") | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:120]) | \(.message[0:120])"' | head -5
```
```
11:41:54.201 DEBUG c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::12207658555f::37-0 | Another flush cycle is currently ongoing
11:41:54.735 DEBUG c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::12207658555f::37-0 | Another flush cycle is currently ongoing
11:41:54.900 DEBUG c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::12207658555f::37-0 | Another flush cycle is currently ongoing
11:41:57.709 DEBUG c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::12207658555f::37-0 | Another flush cycle is currently ongoing
```
And sv4Validator gave up:
```
zcat log/11288/logs-simtime-4/canton_network_test.clog.gz | jq -r 'select(.level=="ERROR" or .level=="WARN") | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:80]) | \(.message|gsub("\\s+";" ")|.[0:300])"' | head -30
```
```
11:44:21.155 ERROR o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=d9c | Gave up getting 'user sv4_validator_user-d9cc9816'
11:44:21.156 ERROR o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=d9c | sv4Validator app initialization: Initialization failed
```
No mediator `Failed to send result` in 11288 (`zcat $F | grep -a -c 'Failed to send result'` = 0); blacklisting
(epochs 14-16) came after the rejection and played no part here.

## 8. Duplicate check and hit rate

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "simtime" created:>=2026-11-02' --jq '.items[] | "\(.number) \(.state) \(.state_reason) \(.created_at[0:16]) \(.title[0:90])"'
```
(excerpt; full list in the run of 2026-11-09)
```
11288 open null 2026-11-09T11:44 GHA Run 42608940134 : job simtime (4) in scala_test Failed :fire:
11287 open null 2026-11-09T10:20 GHA Run 42591855865 : job simtime (3) in scala_test Failed :fire:
11255 open null 2026-11-06T16:29 Timeouts due to changing bft ordering topology
11284 closed duplicate 2026-11-09T05:43 GHA Run 42565193320 : job simtime (1) in scala_test Failed :fire:
11283 closed duplicate 2026-11-09T05:29 GHA Run 42565193320 : job simtime (0) in scala_test Failed :fire:
11282 closed duplicate 2026-11-09T05:27 GHA Run 42565193320 : job simtime (3) in scala_test Failed :fire:
11276 closed duplicate 2026-11-07T05:53 GHA Run 42341066277 : job simtime (2) in scala_test Failed :fire:
11275 closed duplicate 2026-11-07T05:41 GHA Run 42341066277 : job simtime (4) in scala_test Failed :fire:
11258 closed duplicate 2026-11-06T17:12 GHA Run 42253265180 : job simtime (2) in scala_test Failed :fire:
11242 closed duplicate 2026-11-06T14:39 GHA Run 42230239729 : job simtime (1) in scala_test Failed :fire:
11241 closed duplicate 2026-11-06T14:24 GHA Run 42230239729 : job simtime (2) in scala_test Failed :fire:
```
11255 family members (from the 11255 comment and this round): 11241, 11242, 11258, 11275, 11276, 11282, 11283,
11284; plus 11255 itself (run 42248944117). Post-merge main runs since 2026-11-06:
```
gh api -X GET "repos/canton-network/splice/actions/runs" -f branch=main -f event=push -f created='>=2026-11-06' -f per_page=100 --jq '.workflow_runs[] | select(.name=="CI post-merge to main or release-line branches") | "\(.id) \(.created_at[0:16]) \(.conclusion) \(.head_sha[0:10])"'
```
```
42608940134 2026-11-09T11:25 failure bd9953a523
42591855865 2026-11-09T09:14 failure 9dafafbece
42288740264 2026-11-06T20:29 success 0b2ccec235
42258277175 2026-11-06T17:04 success 4476e19765
42253557708 2026-11-06T16:33 success 1add1813e3
42253265180 2026-11-06T16:32 failure d8537ea52b
42248944117 2026-11-06T16:04 failure 6f7a5f83bc
42230239729 2026-11-06T14:01 failure c218905f75
42226645323 2026-11-06T13:34 failure b77153c658
42214429624 2026-11-06T12:00 failure 97df2c4399
42209420702 2026-11-06T11:19 success d2badce083
42205124002 2026-11-06T10:45 failure 04c342f6fd
42199308951 2026-11-06T10:00 success 9f88d4ce64
```
Family runs among them: 42230239729 (11241, 11242), 42248944117 (11255), 42253265180 (11258), 42591855865 (11287),
42608940134 (11288): 5 of 13. Not checked: 42226645323, 42214429624, 42205124002 (42205124002 is 11238, waiting in
the bft round). Mainnet nightly (separate workflow): 11-07 (11275, 11276) and 11-09 (11282, 11283, 11284).

State of the parent and the fixes:
```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"\(.state) c=\(.comments) a=\([.assignees[].login]|join(",")) \(.title)"'
gh pr view 8356 -R canton-network/splice --json state,mergedAt,title
```
```
open c=1 a= Timeouts due to changing bft ordering topology
{"mergedAt":null,"state":"OPEN","title":"Disable BFT sequencer blacklisting in integration tests"}
```

## Verdict

- 11287: duplicate of 11255, lost-verdict shape (sv4 blacklisted after the 2 -> 4 step, sv2 subscribed after the
  request, sv1 delayed on the sim clock). splice#8356 would have avoided it.
- 11288: duplicate of 11255, rejected-send shape (MED::sv4 proposal rejected at the 1 -> 4 switch). splice#8356 would
  not help; onboarding the sequencers one at a time probably would (11255 comment).
- Flake in the Canton BFT onboarding window, now on most of the simtime failures on main: 5 of 13 post-merge runs
  since 2026-11-06, both runs today; parent unassigned, #8356 open. A recurrence comment on the parent is warranted
  (hit rate on main, no owner).
- Supports addendum (2) of the 11274-11276 packet: the single-sequencer step in 11287 (sv3 alone, 1 -> 2) blacklisted
  nobody; the steps adding two or three sequencers did, always sv4.
- Not verified: why sv4 is always the blacklisted node; the 4 failed post-merge runs not checked above.
