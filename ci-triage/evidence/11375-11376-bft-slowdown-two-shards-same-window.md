# 11375 11376 - WalletExpirationsIntegrationTest and SvMergeSvRewardStateIntegrationTest: run-wide 4 min slowdown of the BFT synchronizer on two shards at once (run 43073677994)

new. One cause for both issues. main ee8772c ("Bump Canton to `3.7.3-snapshot.20261111.20904.0.vbe797d9d` (#8441)"),
jobs wall-clock-time (5) and wall-clock-time (9), attempt 1 each, in separate runner pods. From 18:45:22 to 18:49:18
UTC both shards' Canton instances slow down together: BFT epochs that normally take 3.5-6 s take 15-36 s, and even the
local database write of a pre-prepare on sv1 goes from about 5 ms to 100-370 ms. Every ledger round trip in that
window needs three ordering hops of 3-6 s each. 11375: `auto-expire payment requests` fails in its first step,
`onboardUser` on aliceValidator, with the 38 s HTTP client timeout (the request completes after 63.8 s; its two ledger
submissions take 20.1 s and 29.5 s). 11376: `Multiple SvRewardStates for the same SV get merged` fails its 20 s
`eventually()` because the merge transaction comes back 130 ms after the deadline (19.5 s round trip). Both test
budgets are fine under normal latency. The Canton bump is not implicated: the slowdown starts and ends within 2 s on
two independent Canton processes in different pods, and the next post-merge runs on the same Canton passed their
wall-clock shards. What slowed both pods (shared k8s node, storage, cluster) is not visible in the artifacts.

- Run: https://github.com/canton-network/splice/actions/runs/43073677994, main ee8772cc0b ("Bump Canton to
  `3.7.3-snapshot.20261111.20904.0.vbe797d9d` (#8441)"), jobs 130708763686 `ci / scala_test_wall_clock_time /
  wall-clock-time (5)` (11375) and 130708763759 `ci / scala_test_wall_clock_time / wall-clock-time (9)` (11376), attempt 1.
- Runtime canton: 3.7.3-snapshot.20261111.20904.0.vbe797d9d.
- Component: infra (runner host or storage), surfacing through fixed test budgets.

## 0. Issues and job resolution

```
for r in 11375 11376; do gh api repos/DACH-NY/cn-test-failures/issues/$r --jq '"\(.state) \(.title)\n\(.body)"'; done
```
```
open GHA Run 43073677994 : job wall-clock-time (5) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43073677994).
Job: wall-clock-time (5) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [ee8772c](https://github.com/canton-network/splice/commit/ee8772cc0b5caa646ea5a3351937f28670d19383) Bump Canton to `3.7.3-snapshot.20261111.20904.0.vbe797d9d` (#8441)
Actor: martinflorian-da

open GHA Run 43073677994 : job wall-clock-time (9) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43073677994).
Job: wall-clock-time (9) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [ee8772c](https://github.com/canton-network/splice/commit/ee8772cc0b5caa646ea5a3351937f28670d19383) Bump Canton to `3.7.3-snapshot.20261111.20904.0.vbe797d9d` (#8441)
Actor: martinflorian-da

```

```
RUN=43073677994; for N in 'wall-clock-time (5)' 'wall-clock-time (9)'; do export NAME="$N"; for p in 1 2 3 4 5; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'; done; for j in 130708763686 130708763759; do gh api repos/canton-network/splice/actions/jobs/$j --jq '"\(.id) runner=\(.runner_name) started=\(.started_at) completed=\(.completed_at)"'; done; git show ee8772cc0b:nix/canton-sources.json | grep -m1 version; git show 644b3ba97a:nix/canton-sources.json | grep -m1 version
```
```
130708763686 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (5)
130708763759 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (9)
130708763686 runner=self-hosted-k8s-large-runner-r82nt started=2026-11-11T18:03:53Z completed=2026-11-11T18:50:58Z
130708763759 runner=self-hosted-k8s-large-runner-8vqj4 started=2026-11-11T18:03:52Z completed=2026-11-11T18:51:43Z
  "version": "3.7.3-snapshot.20261111.20904.0.vbe797d9d",
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

## 1. Failing assertions

```
for j in 130708763686 130708763759; do echo "== $j"; grep -a -E 'Tests: succeeded|FAILED \*\*\*' log/inbox-1111b/$j.log | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-200; grep -a -m1 -A4 'Multiple SvRewardStates for the same SV get merged \*\*\* FAILED' log/inbox-1111b/$j.log | sed -E 's/^[^Z]*Z //' | grep -a -E 'TestFailedException|SvMergeSvRewardStateIntegrationTest.scala:75' | head -2; done
```
```
== 130708763686
[info] *** 1 TEST FAILED ***
[info] - auto-expire payment requests *** FAILED ***
[info] - should auto-expire payment requests *** FAILED ***
[info] Tests: succeeded 26, failed 1, canceled 0, ignored 0, pending 0
== 130708763759
[info] *** 1 TEST FAILED ***
[info] - Multiple SvRewardStates for the same SV get merged *** FAILED ***
[info] Tests: succeeded 29, failed 1, canceled 0, ignored 0, pending 0
[info]   List(org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState.Contract(ContractId(00c1b99f24f0c17fcf993b4b432f27c1ce50f80ce1b7d9e7c67d1bce01ca286bddca1212205110329696940b5ca10b7feeb71ae9929632bca2db2ce689832b0c520c815766), org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState(DSO-ca35fc50-ca35fc50::12202aaaa1ebdf47fbf5afc7229ebf49385555ba97628e205a780cfdae00ffc52118, Digital-Asset-2, org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.RewardState(0, 2, org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round(1), 2)), [DSO-ca35fc50-ca35fc50::12202aaaa1ebdf47fbf5afc7229ebf49385555ba97628e205a780cfdae00ffc52118], []), org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState.Contract(ContractId(00b4efacefcf8bcc65775d8b690e852ffa06c3750a07676425e16eb92a6b0fdcb4ca121220cd634610acf0aaa250f72c435096b7316207a195d23f86baf2b6579549cdb806), org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState(DSO-ca35fc50-ca35fc50::12202aaaa1ebdf47fbf5afc7229ebf49385555ba97628e205a780cfdae00ffc52118, Digital-Asset-2, org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.RewardState(0, 2, org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round(1), 2)), [DSO-ca35fc50-ca35fc50::12202aaaa1ebdf47fbf5afc7229ebf49385555ba97628e205a780cfdae00ffc52118], [])) had size 2 instead of expected size 1 SvRewardStates (SvMergeSvRewardStateIntegrationTest.scala:75)
[info]   org.scalatest.exceptions.TestFailedException:
```

## 2. 11375: suite timeline and the failing clue

Artifacts: `logs-wall-clock-time-5` (id 13884922828) in `log/11375/logs-wall-clock-time-5/`, `logs-wall-clock-time-9` (id 13883977927) in `log/11376/logs-wall-clock-time-9/`.

```
zcat log/11375/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Running clue: Onboard|Failed clue|timeout after 38 seconds|HttpCommandException" | grep -a -E 'T18:(4[5-9])' | grep -a -E 'WalletExpirations|aliceValidator' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/WalletExpirationsIntegrationTest\/config=d34247d8\///' | cut -c1-230 | head -12
```
```
2026-11-11T18:45:21.979Z Starting test suite 'WalletExpirationsIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-11T18:48:12.935Z Running clue: Onboard alice_wallet_user-d34247d8_tc0 on aliceValidator",[o.l.s.i.t.WalletExpirationsIntegrationTest:WalletExpirationsIntegrationTest] DEBUG
2026-11-11T18:48:12.937Z Starting 'WalletExpirationsIntegrationTest/A wallet should auto-expire payment requests'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-11T18:48:50.982Z Request to http://127.0.0.1:5503/api/validator/v0/admin/users (POST) resulted in a timeout after 38 seconds.",[o.l.s.a.h.HttpErrorHandler:validator=aliceValidator] WARN
2026-11-11T18:48:51.020Z org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 503 Service Unavailable POST at '/api/validator/v0/admin/users' on 127.0.0.1:5503. Command failed, message: The server i
2026-11-11T18:48:51.083Z Failed clue: Onboard alice_wallet_user-d34247d8_tc0 on aliceValidator",[o.l.s.i.t.WalletExpirationsIntegrationTest:WalletExpirationsIntegrationTest] ERROR
2026-11-11T18:48:51.159Z Test failed: 'WalletExpirationsIntegrationTest/A wallet should auto-expire payment requests', message: Command execution failed., location: SeeStackDepthException",[c.d.c.LogReporter:reporter=scala-test] E
2026-11-11T18:48:51.264Z Starting 'WalletExpirationsIntegrationTest/A wallet should support expiring transfer offers'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-11T18:48:51.264Z Running clue: Onboard alice_wallet_user-d34247d8_tc1 on aliceValidator",[o.l.s.i.t.WalletExpirationsIntegrationTest:WalletExpirationsIntegrationTest] DEBUG
2026-11-11T18:49:18.497Z Running clue: Onboard bob_wallet_user-d34247d8_tc1 on bobValidator",[o.l.s.i.t.WalletExpirationsIntegrationTest:WalletExpirationsIntegrationTest] DEBUG
2026-11-11T18:49:32.275Z Test succeeded: 'WalletExpirationsIntegrationTest/A wallet should support expiring transfer offers'",[c.d.c.LogReporter:reporter=scala-test] INFO
```

## 3. 11375: where the 38 s of onboardUser went

Request trace 8f36af5880aa2dc9a72cf0d87514c9bf on aliceValidator: two ledger submissions (installWalletForUser, createValidatorRight) take 20.1 s and 29.5 s; the HTTP request completes after 63.8 s, 25 s after the 38 s client timeout.

```
zcat log/11375/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a '8f36af5880aa2dc9a72cf0d87514c9bf' | grep -a -E 'received request|CommandService/SubmitAndWait.*(sending request|succeeded)|timeout after 38|Responding with status' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/WalletExpirationsIntegrationTest\/config=d34247d8\///; s/(SubmitAndWaitRequest\(\\n  Commands\(\\n    \\"\\",\\n    alice_validator_user-d34247d8,\\n    org.lfdecentralizedtrust.splice.validator.[a-zA-Z]+).*/\1 .../' | cut -c1-230
```
```
2026-11-11T18:48:12.967Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:54852): received request.",[o.l.s.a.a.HttpRequestLogger:validator=aliceValidator] DEBUG
2026-11-11T18:48:23.153Z Request (tid:29985686603b0e6d54e3b8674683e133) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(\n  Commands(\n    \"\",\n    alice_validator_user
2026-11-11T18:48:24.489Z Request (tid:84c61188c35c216e1d79e5c5fcec7485) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(\n  Commands(\n    \"\",\n    alice_validator_user
2026-11-11T18:48:24.942Z Request (tid:5dc2d1a3d1905f1fe17dbf1a9f1b3b30) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(\n  Commands(\n    \"\",\n    alice_validator_user
2026-11-11T18:48:26.343Z Request (tid:c1b6d73d737eca47826a9ebb166a8a60) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(\n  Commands(\n    \"\",\n    alice_validator_user
2026-11-11T18:48:46.406Z Request (tid:c1b6d73d737eca47826a9ebb166a8a60) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: succeeded(OK)",[o.l.s.a.a.c.ApiClientRequestLogger:validator=aliceValidator] DEBUG
2026-11-11T18:48:47.338Z Request (tid:75417c57d2ed3f5a08e0dc3349451ab2) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitRequest(\n  Commands(\n    \"\",\n    alice_validator_user
2026-11-11T18:48:50.982Z Request to http://127.0.0.1:5503/api/validator/v0/admin/users (POST) resulted in a timeout after 38 seconds.",[o.l.s.a.h.HttpErrorHandler:validator=aliceValidator] WARN
2026-11-11T18:49:16.798Z Request (tid:75417c57d2ed3f5a08e0dc3349451ab2) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: succeeded(OK)",[o.l.s.a.a.c.ApiClientRequestLogger:validator=aliceValidator] DEBUG
2026-11-11T18:49:16.809Z HTTP POST /api/validator/v0/admin/users from (127.0.0.1:54852): Responding with status code: 200 OK, took 63841 ms",[o.l.s.a.a.HttpRequestLogger:validator=aliceValidator] DEBUG
```

Canton side of the installWalletForUser submission (participant trace c1b6d73d737eca47826a9ebb166a8a60): every hop through the BFT ordering layer takes 3-6 s (submission 18:48:27.1 ordered 18:48:30.3; confirmation sent 18:48:32.1 reaches the mediators 18:48:37.3; verdicts sent 18:48:37.7-18:48:41.9 reach the participant 18:48:44.0).

```
zcat log/11375/logs-wall-clock-time-5/canton.clog.gz | grep -a 'c1b6d73d737eca47826a9ebb166a8a60' | grep -a -E 'Phase 1 completed|sends request with id|validated to: Deliver|Phase 3: Validating|Phase 4: Sending|Request send-async-versioned/c1926ae5.*succeeded|Phase 5: Received|Phase 6: Finalized|Request data is waiting|timeout-result succeed|Publish event with synchronizer index SynchronizerIndex\(sequencerIndex=Some\(2026-11-11T18:48:28' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | awk '{k=substr($0,26,28); c[k]++; if (c[k]<=1) print}' | cut -c1-200
```
```
2026-11-11T18:48:27.043Z Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters Seq(alice-validatord34247d8-1::1220e37d7c87..., alice__wallet__user-d34247d8__tc0::1220e37d7c87..
2026-11-11T18:48:27.074Z 'PAR::aliceValidator::1220e37d7c87...' sends request with id 'c448ee3c-60b9-4c73-9ea3-b4f5acb540e8' of size 3098 bytes with 3 envelopes to MGR=1, PAR=1.",[c.d.c.s.s.s.GrpcSequ
2026-11-11T18:48:30.329Z At block 8830, the submission request c448ee3c-60b9-4c73-9ea3-b4f5acb540e8 at 2026-11-11T18:48:28.305647Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:seq
2026-11-11T18:48:31.556Z Phase 3: Validating Transaction request=2026-11-11T18:48:28.305647Z with 1 envelope(s)",[c.d.c.p.p.TransactionProcessor:participant=aliceParticipant/psid=global-domain::1220f6
2026-11-11T18:48:31.954Z Phase 4: Sending for request=2026-11-11T18:48:28.305647Z with msgId=c1926ae5-774e-41d0-90e8-fe5fa3655b63 approved=1, rejected=0, abstained=0",[c.d.c.p.p.TransactionProcessor:p
2026-11-11T18:48:32.119Z Request send-async-versioned/c1926ae5-774e-41d0-90e8-fe5fa3655b63 has succeeded for server-SEQ::sv1::..87ec-0.",[c.d.c.s.c.p.GrpcConnection:participant=aliceParticipant/psid=g
2026-11-11T18:48:36.493Z At block 8850, the submission request c1926ae5-774e-41d0-90e8-fe5fa3655b63 at 2026-11-11T18:48:34.755155Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:seq
2026-11-11T18:48:37.256Z Phase 5: Received 1 response(s) for request=2026-11-11T18:48:28.305647Z.",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:mediator=globalMediatorSv4/psid=global-domain::122
2026-11-11T18:48:37.617Z Phase 6: Finalized request=RequestId(2026-11-11T18:48:28.305647Z) with verdict Approve at 2026-11-11T18:49:28.305647Z",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:media
2026-11-11T18:48:37.817Z 'MED::sv4::12204a54f71c...' sends request with id 'b7033775-0ba5-46d0-ab65-9ff305c97f42' of size 722 bytes with 1 envelopes to PAR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-11T18:48:37.992Z 'MED::sv2::1220e7c9e111...' sends request with id '0e5c5d0f-0654-407d-988e-6287e5fbcf3d' of size 722 bytes with 1 envelopes to PAR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-11T18:48:38.968Z 'MED::sv3::12206c3f0167...' sends request with id 'ebd80755-d302-47dc-8c7b-c59508ad3dea' of size 722 bytes with 1 envelopes to PAR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-11T18:48:41.917Z 'MED::sv1::12205529573c...' sends request with id '466ebde7-ba9c-45ae-adc0-47931fd70acf' of size 722 bytes with 1 envelopes to PAR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-11T18:48:42.072Z At block 8860, the submission request b7033775-0ba5-46d0-ab65-9ff305c97f42 at 2026-11-11T18:48:37.606290Z validated to: DeliverReceipt",[c.d.c.s.b.u.SequencedSubmissionsValida
2026-11-11T18:48:42.341Z At block 8862, the submission request 0e5c5d0f-0654-407d-988e-6287e5fbcf3d at 2026-11-11T18:48:37.608949Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:seq
2026-11-11T18:48:44.050Z Request 2026-11-11T18:48:28.305647Z: Request data is waiting to be validated",[c.d.c.p.p.Phase37Synchronizer:participant=aliceParticipant/psid=global-domain::1220f6b89030::37-
2026-11-11T18:48:44.096Z timeout-result succeed successfully but slow after 12256 milliseconds",[c.d.c.p.p.c.NaiveRequestTracker:participant=aliceParticipant/psid=global-domain::1220f6b89030::37-0] IN
2026-11-11T18:48:44.752Z Publish event with synchronizer index SynchronizerIndex(sequencerIndex=Some(2026-11-11T18:48:28.305647Z), repairIndex=None, recordTime=2026-11-11T18:48:28.305647Z)",[c.d.c.p.e
```

All onboardUser calls in the shard: only the two inside the slow window exceed 10 s (the second, for the next test case, took 27.2 s and passed under the 38 s limit).

```
zcat log/11375/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -o -E '"@timestamp":"[^"]+","message":"HTTP POST /api/validator/v0/admin/users from \([^)]*\): Responding with status code: [0-9]+ [A-Za-z ]+, took [0-9]+ ms' | sed -E 's/"@timestamp":"([^"]+)","message":"HTTP POST.*status code: ([0-9]+).*took ([0-9]+) ms/\1 \2 \3/' | awk '{n++; if ($3>10000) print; s+=$3} END {print "count", n, "mean_ms", int(s/n)}'
```
```
2026-11-11T18:49:16.809Z 200 63841
2026-11-11T18:49:18.487Z 200 27158
count 21 mean_ms 6934
```

## 4. 11376: the merge transaction commits 130 ms after the 20 s eventually

The clue starts 18:47:41.825 (eventually() default 20 s, so until about 18:48:01.8); the trigger submits the merge at 18:47:42.454 and gets the transaction back at 18:48:01.955; the clue fails at 18:48:02.054.

```
zcat log/11376/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-11T18:4[5-9]' | grep -a -E 'Starting test suite .SvMerge|Running clue: Trigger merges|Failed clue: Trigger merges|Test failed: .SvMerge|MergeSvRewardStateContractsTrigger.*(Resuming|Merged)|c728021dd998b7e60d5643afc3b06672.*(sending request|succeeded)' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/SvMergeSvRewardStateIntegrationTest\/config=[0-9a-f]+\///; s/(SubmitAndWaitForTransactionRequest).*(\[o\.l)/\1 ... \2/; s/(message: List\(org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState.Contract).*/\1 .../' | cut -c1-220
```
```
2026-11-11T18:45:07.397Z Starting test suite 'SvMergeSvRewardStateIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-11T18:47:41.825Z Running clue: Trigger merges SvRewardState contracts",[o.l.s.i.t.SvMergeSvRewardStateIntegrationTest:SvMergeSvRewardStateIntegrationTest] DEBUG
2026-11-11T18:47:42.454Z Request (tid:c728021dd998b7e60d5643afc3b06672) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: sending request SubmitAndWaitForTransactionRequest ... [o.l.s.a
2026-11-11T18:48:01.955Z Request (tid:c728021dd998b7e60d5643afc3b06672) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: succeeded(OK)",[o.l.s.a.a.c.ApiClientRequestLogger:SV=sv1] DEBU
2026-11-11T18:48:02.054Z Suppressed ERROR: Failed clue: Trigger merges SvRewardState contracts",[o.l.s.i.t.SvMergeSvRewardStateIntegrationTest:SvMergeSvRewardStateIntegrationTest] INFO
2026-11-11T18:49:20.759Z Test failed: 'SvMergeSvRewardStateIntegrationTest/Multiple SvRewardStates for the same SV get merged', message: List(org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState.
```

```
git show ee8772cc0b:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvMergeSvRewardStateIntegrationTest.scala | sed -n '69,78p'
```
```
      loggerFactory.assertLoggedWarningsAndErrorsSeq(
        {
          resumeAllDsoDelegateTriggers[MergeSvRewardStateContractsTrigger]
          clue("Trigger merges SvRewardState contracts") {
            eventually() {
              val newRewardStates = getRewardStates()
              newRewardStates should have size 1 withClue "SvRewardStates"
            }
          }
        },
```

Canton side of the merge (participant trace c728021dd998b7e60d5643afc3b06672): the same shape, 4-5 s per ordering hop (submission 18:47:43.5 ordered 18:47:47.2; confirmation 18:47:49.1 ordered 18:47:54.1; verdict 18:47:55.4 reaches the participant 18:47:59.7).

```
zcat log/11376/logs-wall-clock-time-9/canton.clog.gz | grep -a 'c728021dd998b7e60d5643afc3b06672' | grep -a -E 'Phase 1 completed|validated to: Deliver|Phase 3: Validating|Phase 4: Sending|Phase 5: Received|Phase 6: Finalized|Request data is waiting|timeout-result|Publish event with synchronizer index|SubmitAndWaitForTransaction.*sending response' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | awk '{k=substr($0,26,28); c[k]++; if (c[k]<=1) print}' | cut -c1-200
```
```
2026-11-11T18:47:43.502Z Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-2-ca35fc50::12205d41636a..., command-id e4b57abc-43f2-44dc-980c-3e1c7bc68cc5",[c.d.
2026-11-11T18:47:47.232Z At block 22920, the submission request fb1cb4a8-7195-407d-8c2f-27ef4a135102 at 2026-11-11T18:47:45.776272Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:se
2026-11-11T18:47:48.539Z Phase 3: Validating Transaction request=2026-11-11T18:47:45.776272Z with 1 envelope(s)",[c.d.c.p.p.TransactionProcessor:participant=sv1Participant/psid=global-domain::12202aaa
2026-11-11T18:47:49.068Z Phase 4: Sending for request=2026-11-11T18:47:45.776272Z with msgId=8f2f535e-40c5-4c8d-84a4-ba0cd3a32303 approved=1, rejected=0, abstained=0",[c.d.c.p.p.TransactionProcessor:p
2026-11-11T18:47:54.110Z At block 22940, the submission request 8f2f535e-40c5-4c8d-84a4-ba0cd3a32303 at 2026-11-11T18:47:51.660841Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:se
2026-11-11T18:47:55.256Z Phase 5: Received 1 response(s) for request=2026-11-11T18:47:45.776272Z.",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:mediator=globalMediatorSv3/psid=global-domain::122
2026-11-11T18:47:55.367Z Phase 6: Finalized request=RequestId(2026-11-11T18:47:45.776272Z) with verdict Approve at 2026-11-11T18:48:45.776272Z",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:media
2026-11-11T18:47:57.721Z At block 22950, the submission request b31b24f0-0ffe-4ff0-9c0a-de2c48c887e8 at 2026-11-11T18:47:54.665238Z validated to: DeliverReceipt",[c.d.c.s.b.u.SequencedSubmissionsValid
2026-11-11T18:47:58.691Z At block 22955, the submission request db94c3aa-8374-4d0a-bd7a-c2cfdad0b4b9 at 2026-11-11T18:47:57.260877Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:se
2026-11-11T18:47:59.687Z Request 2026-11-11T18:47:45.776272Z: Request data is waiting to be validated",[c.d.c.p.p.Phase37Synchronizer:participant=sv1Participant/psid=global-domain::12202aaaa1eb::37-0]
2026-11-11T18:47:59.736Z timeout-result succeed successfully but slow after 10790 milliseconds",[c.d.c.p.p.c.NaiveRequestTracker:participant=sv1Participant/psid=global-domain::12202aaaa1eb::37-0] INFO
2026-11-11T18:48:00.302Z Publish event with synchronizer index SynchronizerIndex(sequencerIndex=Some(2026-11-11T18:47:45.776272Z), repairIndex=None, recordTime=2026-11-11T18:47:45.776272Z)",[c.d.c.p.e
2026-11-11T18:48:01.943Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:56874: sending response SubmitAndWaitForTransactionResponse(\n  Transaction(\n    
```

## 5. Both shards: BFT epochs slow down up to 8x in the same wall-clock window, 18:45:22 to 18:49:18

Epoch durations on globalSequencerSv1 (epoch length 80 blocks). Normal epochs take 3.5-6 s; both shards have a run of 15-36 s epochs starting within one second of each other (18:45:21.5 shard 5, 18:45:22.6 shard 9) and ending within 2 s (18:49:16.1 / 18:49:18.4). Apart from that window, only isolated epochs above 10 s (startup and around 18:41). The previous wall-clock-time (5) on the older Canton (run 43031077112) has slow epochs only at startup.

```
cd log; for f in 11375/logs-wall-clock-time-5/canton.clog.gz 11376/logs-wall-clock-time-9/canton.clog.gz 11364/logs-wall-clock-time-5/canton_before_shutdown.clog.gz; do echo "== $f"; zcat $f | grep -a 'sequencer=globalSequencerSv1/' | grep -a -o -E '^\{"@timestamp":"[^"]+","message":"New epoch [0-9]+ has started' | sed -E 's/\{"@timestamp":"2026-11-11T([^"]+)Z","message":"New epoch ([0-9]+).*/\1 \2/' | awk '{split($1,a,":"); s=a[1]*3600+a[2]*60+a[3]; if (p && $2==pe+1) {d=s-p; n++; if (d>10) {m++; printf "  slow epoch %s started %s dur=%.1fs\n", pe, pt, d}} p=s; pe=$2; pt=$1} END {print "  epochs=" n, "over_10s=" m+0}'; done
```
```
== 11375/logs-wall-clock-time-5/canton.clog.gz
  slow epoch 20 started 18:21:53.230 dur=15.3s
  slow epoch 21 started 18:22:08.551 dur=16.0s
  slow epoch 22 started 18:22:24.510 dur=10.8s
  slow epoch 269 started 18:40:51.971 dur=14.5s
  slow epoch 270 started 18:41:06.465 dur=16.4s
  slow epoch 272 started 18:41:30.626 dur=11.0s
  slow epoch 280 started 18:42:18.260 dur=19.1s
  slow epoch 315 started 18:45:21.531 dur=15.4s
  slow epoch 316 started 18:45:36.909 dur=19.5s
  slow epoch 317 started 18:45:56.416 dur=20.1s
  slow epoch 318 started 18:46:16.512 dur=27.5s
  slow epoch 319 started 18:46:43.968 dur=26.7s
  slow epoch 320 started 18:47:10.634 dur=29.1s
  slow epoch 321 started 18:47:39.754 dur=30.1s
  slow epoch 322 started 18:48:09.887 dur=30.7s
  slow epoch 323 started 18:48:40.544 dur=35.6s
  epochs=334 over_10s=16
== 11376/logs-wall-clock-time-9/canton.clog.gz
  slow epoch 18 started 18:21:41.928 dur=12.9s
  slow epoch 19 started 18:21:54.857 dur=18.6s
  slow epoch 30 started 18:23:09.195 dur=10.7s
  slow epoch 156 started 18:33:50.824 dur=10.1s
  slow epoch 267 started 18:42:32.544 dur=11.4s
  slow epoch 304 started 18:45:22.561 dur=19.0s
  slow epoch 305 started 18:45:41.561 dur=21.6s
  slow epoch 306 started 18:46:03.141 dur=18.8s
  slow epoch 307 started 18:46:21.953 dur=22.3s
  slow epoch 308 started 18:46:44.242 dur=23.7s
  slow epoch 309 started 18:47:07.978 dur=23.7s
  slow epoch 310 started 18:47:31.701 dur=24.6s
  slow epoch 311 started 18:47:56.348 dur=24.5s
  slow epoch 312 started 18:48:20.893 dur=35.4s
  slow epoch 313 started 18:48:56.325 dur=22.1s
  epochs=331 over_10s=15
== 11364/logs-wall-clock-time-5/canton_before_shutdown.clog.gz
  slow epoch 19 started 14:13:07.416 dur=13.1s
  slow epoch 20 started 14:13:20.540 dur=15.8s
  slow epoch 21 started 14:13:36.306 dur=10.3s
  epochs=395 over_10s=3
```

The slowdown shows inside a single node: on sv1, storing a received pre-prepare in its own database goes from about 5 ms to 100-370 ms per block, and the time from storing to ordering from about 30 ms to 0.25-1.1 s, for exactly the slow epochs. Shard 5 (epochs 312-326):

```
zcat log/11375/logs-wall-clock-time-5/canton.clog.gz | grep -a -E '"@timestamp":"2026-11-11T18:(4[4-9])' | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'PrePrepare: received verified message from|DB stored pre-prepare w/|Ordered block [0-9]+ in segment' | sed -E 's/\{"@timestamp":"2026-11-11T([^"]+)Z","message":"(.*)/\1 \2/' | awk '{t=$1; split(t,a,":"); s=a[1]*3600+a[2]*60+a[3]; if ($0 ~ /PrePrepare: received/) {match($0,/blockNumber=[0-9]+/); b=substr($0,RSTART+12,RLENGTH-12); if(!(b in pp)) pp[b]=s} else if ($0 ~ /DB stored pre-prepare/) {match($0,/blockNumber=[0-9]+/); b=substr($0,RSTART+12,RLENGTH-12); if(!(b in st)) st[b]=s} else {match($0,/Ordered block [0-9]+/); b=substr($0,RSTART+14,RLENGTH-14); match($0,/in epoch [0-9]+/); e=substr($0,RSTART+9,RLENGTH-9); if (b in pp) {n[e]++; d1[e]+=st[b]-pp[b]; d2[e]+=s-st[b]; if (!(e in f)) f[e]=t}}} END {for (e in n) printf "epoch %s first_ordered=%s blocks=%d avg_preprepare_to_dbstored=%.3fs avg_dbstored_to_ordered=%.3fs\n", e, f[e], n[e], d1[e]/n[e], d2[e]/n[e]}' | sort -k2 -n | awk '$2>=312 && $2<=326'
```
```
epoch 312 first_ordered=18:45:07.182 blocks=60 avg_preprepare_to_dbstored=0.017s avg_dbstored_to_ordered=0.057s
epoch 313 first_ordered=18:45:12.193 blocks=60 avg_preprepare_to_dbstored=0.007s avg_dbstored_to_ordered=0.042s
epoch 314 first_ordered=18:45:17.152 blocks=60 avg_preprepare_to_dbstored=0.009s avg_dbstored_to_ordered=0.034s
epoch 315 first_ordered=18:45:21.829 blocks=60 avg_preprepare_to_dbstored=0.104s avg_dbstored_to_ordered=0.256s
epoch 316 first_ordered=18:45:37.601 blocks=60 avg_preprepare_to_dbstored=0.164s avg_dbstored_to_ordered=0.388s
epoch 317 first_ordered=18:45:57.544 blocks=60 avg_preprepare_to_dbstored=0.194s avg_dbstored_to_ordered=0.407s
epoch 318 first_ordered=18:46:17.958 blocks=60 avg_preprepare_to_dbstored=0.200s avg_dbstored_to_ordered=0.612s
epoch 319 first_ordered=18:46:44.510 blocks=60 avg_preprepare_to_dbstored=0.197s avg_dbstored_to_ordered=0.584s
epoch 320 first_ordered=18:47:11.373 blocks=60 avg_preprepare_to_dbstored=0.230s avg_dbstored_to_ordered=0.674s
epoch 321 first_ordered=18:47:41.000 blocks=60 avg_preprepare_to_dbstored=0.240s avg_dbstored_to_ordered=0.671s
epoch 322 first_ordered=18:48:10.900 blocks=60 avg_preprepare_to_dbstored=0.238s avg_dbstored_to_ordered=0.737s
epoch 323 first_ordered=18:48:41.777 blocks=47 avg_preprepare_to_dbstored=0.367s avg_dbstored_to_ordered=1.111s
epoch 324 first_ordered=18:49:16.552 blocks=53 avg_preprepare_to_dbstored=0.012s avg_dbstored_to_ordered=0.031s
epoch 325 first_ordered=18:49:22.440 blocks=54 avg_preprepare_to_dbstored=0.006s avg_dbstored_to_ordered=0.021s
epoch 326 first_ordered=18:49:28.572 blocks=53 avg_preprepare_to_dbstored=0.003s avg_dbstored_to_ordered=0.014s
```

Shard 9 (epochs 301-316), same measurement:

```
zcat log/11376/logs-wall-clock-time-9/canton.clog.gz | grep -a -E '"@timestamp":"2026-11-11T18:(4[4-9])' | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'PrePrepare: received verified message from|DB stored pre-prepare w/|Ordered block [0-9]+ in segment' | sed -E 's/\{"@timestamp":"2026-11-11T([^"]+)Z","message":"(.*)/\1 \2/' | awk '{t=$1; split(t,a,":"); s=a[1]*3600+a[2]*60+a[3]; if ($0 ~ /PrePrepare: received/) {match($0,/blockNumber=[0-9]+/); b=substr($0,RSTART+12,RLENGTH-12); if(!(b in pp)) pp[b]=s} else if ($0 ~ /DB stored pre-prepare/) {match($0,/blockNumber=[0-9]+/); b=substr($0,RSTART+12,RLENGTH-12); if(!(b in st)) st[b]=s} else {match($0,/Ordered block [0-9]+/); b=substr($0,RSTART+14,RLENGTH-14); match($0,/in epoch [0-9]+/); e=substr($0,RSTART+9,RLENGTH-9); if (b in pp) {n[e]++; d1[e]+=st[b]-pp[b]; d2[e]+=s-st[b]; if (!(e in f)) f[e]=t}}} END {for (e in n) printf "epoch %s first_ordered=%s blocks=%d avg_preprepare_to_dbstored=%.3fs avg_dbstored_to_ordered=%.3fs\n", e, f[e], n[e], d1[e]/n[e], d2[e]/n[e]}' | sort -k2 -n | awk '$2>=301 && $2<=316'
```
```
epoch 301 first_ordered=18:45:07.319 blocks=60 avg_preprepare_to_dbstored=0.004s avg_dbstored_to_ordered=0.021s
epoch 302 first_ordered=18:45:12.830 blocks=60 avg_preprepare_to_dbstored=0.004s avg_dbstored_to_ordered=0.021s
epoch 303 first_ordered=18:45:17.401 blocks=60 avg_preprepare_to_dbstored=0.003s avg_dbstored_to_ordered=0.017s
epoch 304 first_ordered=18:45:22.843 blocks=60 avg_preprepare_to_dbstored=0.151s avg_dbstored_to_ordered=0.413s
epoch 305 first_ordered=18:45:42.398 blocks=60 avg_preprepare_to_dbstored=0.202s avg_dbstored_to_ordered=0.482s
epoch 306 first_ordered=18:46:03.716 blocks=60 avg_preprepare_to_dbstored=0.155s avg_dbstored_to_ordered=0.425s
epoch 307 first_ordered=18:46:22.912 blocks=60 avg_preprepare_to_dbstored=0.165s avg_dbstored_to_ordered=0.491s
epoch 308 first_ordered=18:46:45.227 blocks=60 avg_preprepare_to_dbstored=0.163s avg_dbstored_to_ordered=0.495s
epoch 309 first_ordered=18:47:08.667 blocks=60 avg_preprepare_to_dbstored=0.213s avg_dbstored_to_ordered=0.515s
epoch 310 first_ordered=18:47:32.835 blocks=60 avg_preprepare_to_dbstored=0.189s avg_dbstored_to_ordered=0.559s
epoch 311 first_ordered=18:47:57.625 blocks=60 avg_preprepare_to_dbstored=0.196s avg_dbstored_to_ordered=0.525s
epoch 312 first_ordered=18:48:21.930 blocks=60 avg_preprepare_to_dbstored=0.273s avg_dbstored_to_ordered=0.821s
epoch 313 first_ordered=18:48:58.168 blocks=60 avg_preprepare_to_dbstored=0.126s avg_dbstored_to_ordered=0.490s
epoch 314 first_ordered=18:49:18.636 blocks=60 avg_preprepare_to_dbstored=0.007s avg_dbstored_to_ordered=0.033s
epoch 315 first_ordered=18:49:23.674 blocks=60 avg_preprepare_to_dbstored=0.006s avg_dbstored_to_ordered=0.021s
epoch 316 first_ordered=18:49:28.699 blocks=60 avg_preprepare_to_dbstored=0.003s avg_dbstored_to_ordered=0.018s
```

No WARN/ERROR in the canton log of shard 5 between 18:45 and 18:49:18 other than the participant AuthInterceptor `UserNotFound` lines of suite setup (excluded by the grep); the first other ones come after the window ends.

```
zcat log/11375/logs-wall-clock-time-5/canton.clog.gz | grep -a -E '"@timestamp":"2026-11-11T18:4[5-9]' | grep -a -E '"level":"(WARN|ERROR)"' | grep -a -v 'AuthInterceptor' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-11T18:49:18.079Z Failed to send result to sequencer for request 2026-11-11T18:49:17.398051Z RequestRefused(\n  SendAsyncErrorGrpc(\n    Request failed for server-DefaultSequencer-0.\n  GrpcReq
2026-11-11T18:49:20.394Z Failed to send result to sequencer for request 2026-11-11T18:49:19.819815Z RequestRefused(\n  SendAsyncErrorGrpc(\n    Request failed for server-DefaultSequencer-0.\n  GrpcReq
2026-11-11T18:49:21.469Z Failed to send result to sequencer for request 2026-11-11T18:49:20.852902Z RequestRefused(\n  SendAsyncErrorGrpc(\n    Request failed for server-DefaultSequencer-0.\n  GrpcReq
2026-11-11T18:49:22.759Z periodic acknowledgement failed",[c.d.c.s.c.PeriodicAcknowledgements:sequencer=globalSequencerSv4] ERROR
2026-11-11T18:49:28.841Z Failed to send result to sequencer for request 2026-11-11T18:49:28.304766Z RequestRefused(\n  SendAsyncErrorGrpc(\n    Request failed for server-DefaultSequencer-0.\n  GrpcReq
```

## 6. Canton bump: same Canton passes on the next runs

The two shards run independent Canton instances in separate runner pods (self-hosted-k8s-large-runner-r82nt and -8vqj4, section 0); two independent processes on the new Canton cannot slow down in lockstep to the second unless something they share does. The next post-merge runs contain ee8772c; their wall-clock-time shards passed (only roll-forward-lsu, simtime and a cancelled docker-compose job are not green).

```
for s in 915616a77d d356bcf573; do git merge-base --is-ancestor ee8772cc0b $s && echo "$s contains ee8772c"; done; for RUN in 43073677994 43073759004 43078335366; do echo "== $RUN $(gh api repos/canton-network/splice/actions/runs/$RUN --jq '"\(.head_sha[0:10]) \(.created_at) \(.conclusion)"')"; for p in 1 2 3 4 5; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=latest&per_page=40&page=$p"; done | jq -r '.jobs[] | select(.conclusion != "success" and .conclusion != "skipped") | "  \(.conclusion) \(.name | sub("^.* / ";"")) \(.started_at[11:19])-\(.completed_at[11:19])"'; done
```
```
915616a77d contains ee8772c
d356bcf573 contains ee8772c
== 43073677994 ee8772cc0b 2026-11-11T18:01:02Z failure
  cancelled docker-compose (0) 18:19:29-19:14:29
  failure wall-clock-time (5) 18:03:53-18:50:58
  failure wall-clock-time (9) 18:03:52-18:51:43
== 43073759004 915616a77d 2026-11-11T18:01:30Z failure
  failure roll-forward-lsu (0) 18:05:40-18:25:25
  failure simtime (0) 18:12:23-18:46:39
== 43078335366 d356bcf573 2026-11-11T18:30:00Z success
```

## 7. Duplicate search

```
for q in '"auto-expire payment requests"' 'WalletExpirationsIntegrationTest' 'SvMergeSvRewardState' '"SvRewardStates for the same SV"'; do echo "== $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q" --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:90])"'; done
```
```
== "auto-expire payment requests"
== WalletExpirationsIntegrationTest
#8982 closed/not_planned 2026-06-29 Creating environment failed at step Creating fixture: Cause: java.io.UncheckedIOException:
#8908 closed/not_planned 2026-06-22 DistributedDomainIntegrationTest: "Timeout while waiting for initialization of aliceValida
== SvMergeSvRewardState
== "SvRewardStates for the same SV"
#7881 closed/completed 2026-04-05 SvRewardState contracts not getting merged
#6355 closed/completed 2025-11-04 SvMergeSvRewardStateIntegrationTest - Multiple SvRewardStates for the same SV get merged
#6281 closed/completed 2025-10-30 new acs exports: SYNC_SERVICE_ALARM - Request RequestId with failed activeness check is ap
```

## Verdict

- New, not a known family. Closest is M (slow BFT confirmation round trips vs a 10 s client deadline), but M was a
  steady per-hop latency against a too-short deadline; here a 4 min window of 4-8x slower ordering hits two independent
  shards at the same time and every budget in it (38 s HTTP, 20 s eventually) is exceeded only inside the window.
- 11376 is the same incident as 11375: close it as a duplicate of 11375 (same run, same window, same mechanism).
- Flake, infra: no splice or test change proposed. Raising the two budgets would only move the threshold; a
  4 minute slowdown of this size breaks any wall-clock budget below about 60 s.
- Canton bump (#8441): not implicated (section 6). Not ruled out that the new Canton is more sensitive, but nothing in
  the logs points to it and the next runs on it are green.
- Not verified: what slowed both pods. The runner pod names are in section 0; GCP (project da-cn-splice, cluster
  cn-splicenet) would show whether they shared a node and what else ran there 18:45-18:50 UTC
  (`ci-triage/scripts/gcp_logs.py --project da-cn-splice --start 2026-11-11T18:40:00Z --end 2026-11-11T18:55:00Z
  --filter 'resource.type="k8s_container"' --filter 'resource.labels.pod_name=("self-hosted-k8s-large-runner-r82nt-workflow"
  OR "self-hosted-k8s-large-runner-8vqj4-workflow")'`; the node is in the entry labels, not in the default format).
  Worth a look only if a second run shows simultaneous slow epochs on several shards.
- Lesson: none (infra one-off).

## 8. Both pods on one node; the slowdown hit every process in the pod (added in the main thread, 2026-11-11)

Trivial GET status/health handlers in the test JVM (a separate process from Canton) per minute, both shards:
```
cd log; for f in 11375/logs-wall-clock-time-5/canton_network_test.clog.gz 11376/logs-wall-clock-time-9/canton_network_test.clog.gz; do echo "== $f"; zcat $f | grep -a 'Responding with status code: 200 OK, took' | grep -aE 'GET /(api/[a-z]+/)?(status|readyz|livez|health|version|v0/status)' | sed -E 's/.*"@timestamp":"2026-11-11T([0-9:]{5})[^"]*".*took ([0-9]+) ms.*/\1 \2/' | awk '{m=$1; n[m]++; s[m]+=$2; if($2>mx[m])mx[m]=$2} END{for(k in n) printf "%s n=%d avg=%.1fms max=%dms\n",k,n[k],s[k]/n[k],mx[k]}' | sort | awk '$1>="18:40" && $1<="18:53"'; done
```
```
== 11375/logs-wall-clock-time-5/canton_network_test.clog.gz
18:40 n=44 avg=0.0ms max=1ms
18:41 n=22 avg=0.0ms max=1ms
18:42 n=25 avg=0.8ms max=19ms
18:43 n=53 avg=0.0ms max=1ms
18:44 n=60 avg=0.0ms max=1ms
18:45 n=26 avg=0.2ms max=2ms
18:46 n=36 avg=7.8ms max=113ms
18:47 n=26 avg=22.3ms max=148ms
18:48 n=5 avg=2.2ms max=7ms
18:49 n=26 avg=0.1ms max=1ms
== 11376/logs-wall-clock-time-9/canton_network_test.clog.gz
18:40 n=15 avg=0.0ms max=0ms
18:41 n=5 avg=0.0ms max=0ms
18:42 n=16 avg=0.0ms max=0ms
18:43 n=37 avg=0.0ms max=1ms
18:44 n=26 avg=0.0ms max=0ms
18:45 n=45 avg=2.8ms max=34ms
18:46 n=28 avg=4.0ms max=31ms
18:47 n=7 avg=9.0ms max=35ms
18:48 n=7 avg=4.6ms max=32ms
18:49 n=47 avg=0.0ms max=1ms
18:50 n=49 avg=0.2ms max=8ms
```

Node of each runner pod, k8s Scheduled events (user ran this on the host, project da-cn-splice, 2026-11-11):
```
gcloud logging read 'timestamp>="2026-11-11T18:00:00Z" AND timestamp<="2026-11-11T18:55:00Z" AND (resource.type="k8s_pod") AND (resource.labels.namespace_name="gha-runners-splice") AND (resource.labels.pod_name=("self-hosted-k8s-large-runner-r82nt-workflow" OR "self-hosted-k8s-large-runner-8vqj4-workflow")) AND (jsonPayload.reason="Scheduled")' --project da-cn-splice --order asc --limit 200 --format 'value(timestamp,severity,resource.labels.service_name,resource.labels.function_name,httpRequest.status,textPayload,jsonPayload.message)'
```
```
2026-11-11 18:03:28.000
Successfully assigned gha-runners-splice/self-hosted-k8s-large-runner-8vqj4 to gke-cn-splicenet-cn-apps-node-pool-3--eb220c51-dglz
2026-11-11 18:03:29.000
Successfully assigned gha-runners-splice/self-hosted-k8s-large-runner-r82nt to gke-cn-splicenet-cn-apps-node-pool-3--eb220c51-dglz
```
Both pods ran on the same node. Which resource saturated (CPU, memory, disk) is not verified: node metrics for
18:44-18:50 UTC are not checked. Whether CPU limits equal requests for the large-runner pods is not checked.

## 9. Node metrics from Cloud Monitoring (user exports, 2026-11-11)

The user reported from Prometheus (not exported): per pod CPU 5-7 cores for both runner pods; CPU throttling peak 0.9
for r82nt around 18:42; memory working set about 30 GB per pod; a third large workflow pod on the node with equal
utilization; node_* (node-exporter) metrics absent; GKE node_cpu_allocatable_utilization peaks around 50%,
node_memory_allocatable_utilization 70% non-evictable plus 20% evictable.

Exports in `export/11375/` (Metrics Explorer CSV). VM CPU utilization of the node, per minute (script in the
scratchpad, parses the CSV header rows and selects the column of instance_name = the node):
```
python3 -I gcm.py 'VM_Instance_-_CPU_utilization_[MEAN].csv' 'Disk_write_operations_(filtered)_[SUM].csv' 'Disk_read_operations_(filtered)_[SUM].csv'
```
```
instance_id 3965588400284177265
== VM_Instance_-_CPU_utilization_[MEAN].csv cols ['gke-cn-splicenet-cn-apps-node-pool-3--eb220c51-dglz']
18:38      0.41
18:39      0.46
18:40      0.48
18:41      0.58
18:42      0.57
18:43      0.62
18:44      0.59
18:45      0.58
18:46      0.79
18:47      0.91
18:48      0.93
18:49      0.74
18:50         -
18:51      0.41
18:52      0.13
18:53      0.02
== Disk_write_operations_(filtered)_[SUM].csv cols ['-']
18:38   1015.82
18:39   1015.97
18:40   1057.03
18:41   1146.85
18:42   1110.00
18:43   1008.45
18:44   1197.88
18:45   1435.97
18:46    701.42
18:47    358.50
18:48    311.23
18:49    353.12
18:50    353.13
18:51   1286.62
18:52   1716.12
18:53    441.92
== Disk_read_operations_(filtered)_[SUM].csv cols ['-']
18:38      9.13
18:39     34.60
18:40     25.10
18:41     18.48
18:42     21.02
18:43     23.50
18:44     12.47
18:45      6.87
18:46      5.63
18:47      4.57
18:48      0.60
18:49      8.45
18:50      8.47
18:51    101.17
18:52     28.73
18:53      1.13
```
The node (instance_id 3965588400284177265) has no series in `Disk_average_io_queue_depth_[MEAN].csv` or
`Peak_disk_write_ops_[MEAN].csv`. The filter of the two `(filtered)` disk charts is not recorded in the export; if it
is this node, its write ops fell from about 1000-1400/min to 310-360/min during the window, which is less load, not
a disk cap. Reading: the VM CPU went from 58% (18:45) to 79%, 91%, 93% (18:46-18:48) and 74% (18:49), back to 41% at
18:51, matching the slow window 18:45:22-18:49:18. GKE allocatable utilization (about 50%) and VM utilization
disagree; not resolved. The third pod and the node machine type are not identified.

## 10. How runner pods are packed onto nodes (splice main, cluster/pulumi/gha/src/runners.ts)

```
git show FETCH_HEAD:cluster/pulumi/gha/src/runners.ts | sed -n '329,334p;391,402p'
```
```
// A note about resources: We create two pods per workflow: the runner pod and the workflow pod.
// They have implicit affinity between them as they communicate via a shared local PVC.
// The runner starts first, so even though it is quite lightweight, it already pins the node
// on which both will run. We therefore set the resource requests of the runner pod to be the
// request we actually need for the workflow. The limits are set on the workflow pod, to actually
// have the higher bound on actual usage.
                    resources: {
                      // See note above on resource requests and limits.
                      requests: {
                        // We set the requests to a tiny non-zero number, just to prevent k8s from
                        // using the limits as the requests values.
                        cpu: '1m',
                        memory: '1m',
                        'ephemeral-storage': '1',
                      },
                      limits: resources
                        ? singleResourcesSpecFromConfig(resources.limits)
                        : undefined,
```
The scheduler only sees the runner pod requests (`resources.requests` of the runner spec); the workflow pod requests
1m and may use up to `resources.limits`. If the large spec has a CPU limit above its CPU request, three large
workflows on one node can together use more CPU than the node has. The large spec values live in the cn-splicenet
cluster config (`gha.runnerSpecs`, not in the splice repo; the mock config has only tiny); not read here. Node machine
type not read either.

## 11. Node size (user, 2026-11-11) and what it implies

User: the runner node pool uses `c3d-standard-8` nodes (8 vCPU, 32 GB), with fallback to `c4-standard-32` and
`n4-standard-32` (32 vCPU, 128 GB) depending on availability. The machine type of `...-eb220c51-dglz` is not in the
Cloud Monitoring export (its label rows are TimeSeries ID, instance_name, zone, project_id, instance_id).
Inference, not read: three workflow pods with about 30 GB working set each (section 9) do not fit on 32 GB, so the
node was one of the 32 vCPU / 128 GB fallbacks; 90 GB of pod memory matches 70% non-evictable plus 20% evictable.
Since large jobs also run on `c3d-standard-8`, their requests are at most what fits there, so a 32 vCPU fallback
node can take three or more large jobs (section 10: the scheduler sees only the runner pod requests).
Not explained: the three pods used about 15-21 cores (5-7 each) while the VM showed 93% of 32 vCPU (about 30 cores);
not checked whether other pods ran there or whether the kernel spent CPU on memory reclaim.
