# 11337 - TimeBasedTestNetPreviewIntegrationTest setup: sv4Validator createValidatorRight loses its verdict in the BFT onboarding window, circuit breaker, JVM exit (run 42810067070)

DUPLICATE of 11255 (BFT ordering-topology onboarding window), lost-verdict shape (as 11287, 11306, 11308). Post-merge
main 88491ce56e, simtime (2), canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252, 2026-11-10. No test report: during
setup of TimeBasedTestNetPreviewIntegrationTest ("TestNet initializes correctly"), sv4Validator's init step
`Onboard validator wallet users` submitted `createValidatorRight` at 12:10:11.7. It became confirmation request
00:14:43.311Z (sim time), sequenced at 12:10:12.175 in epoch 73 (ordering topology sv1 + sv4), 0.1 s before epoch 74 grew the
topology 2 -> 4 (sv2, sv3) and sv4 was blacklisted in epochs 75-77 although it was not a newcomer of that step.
Mediator group at that point: four mediators, threshold 2. sv4's result was refused by its blacklisted sequencer
(`SEQUENCER_OVERLOADED ... currently blacklist...`), sv1 sent its result (sequenced only as a `DeliverReceipt`, so 1 of
2), sv2 delayed its send by 3000 ms and never sent it (sequenced time stays below request + 3 s until the JVM exit),
sv3's mediator subscribed after the request. sv4Participant never got the verdict; SubmitAndWait timed out after
120 s (12:12:11.721), the 19 retries were refused with `SUBMISSION_ALREADY_IN_FLIGHT` (the in-flight entry lives until
max sequencing time 00:15:43Z, which the sim clock never reaches), the validator's `low-priority-connection`
circuit breaker tripped after 20 failures at 12:12:48.737, init failed at 12:12:51.654 and the app exited the JVM
(family H evidence loss).

- Run: https://github.com/canton-network/splice/actions/runs/42810067070, main 88491ce56e ("Remove
  `SplitwellUpgradeFrontendIntegrationTest` (#8403)"), job 129809308723 `ci / scala_test_sim_time / simtime (2)`,
  attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (`git show 88491ce56e:nix/canton-sources.json`; bumped by
  #8381 on 2026-11-09, so the shape persists across the Canton bump from 3.7.2).
- Component: Canton BFT orderer onboarding window (blacklisting) plus simtime test setup (nothing advances the sim
  clock during setup). Fix candidate for this shape: splice#8356 (open, not merged).

## 1. Job, canton pin, failure in the job log

Issue body (verbatim):
```
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42810067070).
Job: simtime (2) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [88491ce](https://github.com/canton-network/splice/commit/88491ce56e2d46dec53c0b3c85895c71af9be0f2) Remove `SplitwellUpgradeFrontendIntegrationTest` (#8403)
Actor: martinflorian-da
```
Exactly one failed job (recipe 1):
```
RUN=42810067070; export NAME='simtime (2)'
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
git show 88491ce56e:nix/canton-sources.json | grep -m1 version
```
```
129809308723 attempt=1 ci / scala_test_sim_time / simtime (2)
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```
No `Tests:` summary; the JVM exits on an app init failure:
```
gh api repos/canton-network/splice/actions/jobs/129809308723/logs > log/11337/job.log
sed 's/\x1b\[[0-9;]*m//g' log/11337/job.log | grep -a -E 'Initialization failed, so exiting|Caused by|Attempt 1 failed|##\[error\]' | cut -c30-230
```
```
sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Initialize node failed
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Onboard validator wallet users failed
Caused by: io.grpc.StatusRuntimeException: ABORTED: Command submission aborted by circuit breaker due to too many successive failures, next attempt in 30s
Caused by: io.grpc.StatusRuntimeException: ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,dd75eb0c): The submission is already in-flight
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id d6e56330-dcfd-11f1-9496-c5686d8c7518): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

## 2. Which test was running

Artifact `logs-simtime-2` (id 13764167266, created 12:13:09Z, attempt 1):
```
export TMPDIR=$PWD/log/ghtmp; gh run download 42810067070 --repo canton-network/splice -n logs-simtime-2 -D log/11337/logs-simtime-2
T=log/11337/logs-simtime-2/canton_network_test.clog.gz
zcat $T | grep -a 'LogReporter' | jq -r '"\(.["@timestamp"][11:23]) \(.message[0:110])"' | grep -E 'Starting test (run|suite)|Completed test suite|TestNet initializes'
```
```
11:57:36.680 Starting test run...
11:57:37.386 Starting test suite 'GovernanceLockTimeBasedIntegrationTest'...
12:07:42.411 Completed test suite 'GovernanceLockTimeBasedIntegrationTest'.
12:07:42.466 Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...
12:07:42.466 Starting 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly'...
```
GovernanceLockTimeBasedIntegrationTest passed all 7 cases; the exit is in the environment setup of
TimeBasedTestNetPreviewIntegrationTest.

## 3. Ordering topology on globalSequencerSv1: 1 -> 2 (sv4), 2 -> 4 (sv2, sv3), sv4 blacklisted in epochs 75-77

```
F=log/11337/logs-simtime-2/canton-simtime.clog.gz
zcat $F | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'New epoch (7[1-8]) has started|below strong quorum' | jq -r '.["@timestamp"][11:23] + " " + (.message|gsub("\n";" ")|gsub("::1220[0-9a-f]{60}";"")|if test("New epoch") then capture("(?<e>New epoch [0-9]+ has started with leaders = List\\([^)]*\\))and blacklisted nodes = (?<b>List\\([^)]*\\)).*activationTime = (?<a>[^,]+),\\s+size = (?<s>[0-9]+)")|"\(.e) blacklisted=\(.b) activation=\(.a) size=\(.s)" else .[0:110] end)' | awk '$1>="12:09:43"'
```
(the sed-like `gsub` drops the 60 middle hex digits of each sequencer id, so `SEQ::sv1c80e` is `SEQ::sv1::1220...c80e`)
```
12:09:43.455 New epoch 71 has started with leaders = List(SEQ::sv1c80e) blacklisted=List() activation=1970-01-01T00:00:00.070002Z size=1
12:09:53.093 Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 2, ordering may no
12:09:53.112 New epoch 72 has started with leaders = List(SEQ::sv1c80e, SEQ::sv4e255) blacklisted=List() activation=1970-01-01T00:14:43.231004Z size=2
12:10:01.454 Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 2, ordering may no
12:10:05.084 New epoch 73 has started with leaders = List(SEQ::sv4e255, SEQ::sv1c80e) blacklisted=List() activation=1970-01-01T00:14:43.231004Z size=2
12:10:12.285 Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may no
12:10:12.286 New epoch 74 has started with leaders = List(SEQ::sv316cd, SEQ::sv4e255, SEQ::sv1c80e, SEQ::sv29d24) blacklisted=List() activation=1970-01-01T00:14:43.311002Z size=4
12:10:16.969 Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may no
12:10:24.915 New epoch 75 has started with leaders = List(SEQ::sv1c80e, SEQ::sv29d24, SEQ::sv316cd) blacklisted=List(SEQ::sv4e255) activation=1970-01-01T00:14:43.311002Z size=4
12:10:36.994 New epoch 76 has started with leaders = List(SEQ::sv29d24, SEQ::sv316cd, SEQ::sv1c80e) blacklisted=List(SEQ::sv4e255) activation=1970-01-01T00:14:43.311002Z size=4
12:10:51.071 New epoch 77 has started with leaders = List(SEQ::sv316cd, SEQ::sv1c80e, SEQ::sv29d24) blacklisted=List(SEQ::sv4e255) activation=1970-01-01T00:14:43.311002Z size=4
12:11:06.436 New epoch 78 has started with leaders = List(SEQ::sv316cd, SEQ::sv4e255, SEQ::sv1c80e, SEQ::sv29d24) blacklisted=List() activation=1970-01-01T00:14:43.311002Z size=4
```
Same staggered onboarding as 11306 (sv4 alone first, then sv2 and sv3 together; sv4 blacklisted after a step it was
not a newcomer of).

## 4. The stuck submission: createValidatorRight, request 00:14:43.311Z, never completed

The validator's SubmitAndWait has trace id 10a65ea1bcb55616966c45fd792dc5fe (the `DEADLINE_EXCEEDED ... 119.999822264s`
line of section 6 carries tid 10a65ea1...):
```
zcat $F | jq -r 'select(.["trace-id"]=="10a65ea1bcb55616966c45fd792dc5fe") | select(.message|test("is now in-flight|with max sequencing time|validated to: Deliver|Phase 4: Sending|REQUEST_TIME_OUT")) | select(.logger_name|test("globalSequencerSv4")|not) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>(participant|sequencer|mediator)=[A-Za-z0-9]+)").n) | \(.message[0:200]|gsub("\n";" ")|gsub("_[0-9a-f]{64}";"_..."))"'
```
```
12:10:11.739 DEBUG participant=sv4Participant | Command org.lfdecentralizedtrust.splice.validator.createValidatorRight_... is now in-flight.
12:10:11.752 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 934c0c04-a8ff-49c2-92da-b9474403518f with max sequencing time 1970-01-01T00:15:43Z from PAR::sv4::12206433dd86... to Set(MediatorGroupRecipient(group = 0), MemberRec
12:10:12.175 DEBUG sequencer=globalSequencerSv1 | At block 1449, the submission request 934c0c04-a8ff-49c2-92da-b9474403518f at 1970-01-01T00:14:43.311Z validated to: Deliver
12:10:12.252 INFO participant=sv4Participant | Phase 4: Sending for request=1970-01-01T00:14:43.311Z with msgId=1036d0df-d308-45c5-a2de-6505fa785de0 approved=1, rejected=0, abstained=0
12:10:12.267 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 1036d0df-d308-45c5-a2de-6505fa785de0 with max sequencing time 1970-01-01T00:15:13.311Z from PAR::sv4::12206433dd86... to Set(MediatorGroupRecipient(group = 0)). 
12:10:20.216 DEBUG sequencer=globalSequencerSv3 | At block 1449, the submission request 934c0c04-a8ff-49c2-92da-b9474403518f at 1970-01-01T00:14:43.311Z validated to: Deliver
12:10:21.137 DEBUG sequencer=globalSequencerSv2 | At block 1449, the submission request 934c0c04-a8ff-49c2-92da-b9474403518f at 1970-01-01T00:14:43.311Z validated to: Deliver
12:10:25.656 DEBUG sequencer=globalSequencerSv1 | At block 1530, the submission request 1036d0df-d308-45c5-a2de-6505fa785de0 at 1970-01-01T00:14:43.392Z validated to: Deliver
12:10:25.659 DEBUG sequencer=globalSequencerSv2 | At block 1530, the submission request 1036d0df-d308-45c5-a2de-6505fa785de0 at 1970-01-01T00:14:43.392Z validated to: Deliver
12:10:25.662 DEBUG sequencer=globalSequencerSv3 | At block 1530, the submission request 1036d0df-d308-45c5-a2de-6505fa785de0 at 1970-01-01T00:14:43.392Z validated to: Deliver
12:10:26.075 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 1ef2836c-9e69-488d-ba1f-917472d5b46f with max sequencing time 1970-01-01T00:15:43.311Z from MED::sv1::1220e20d565f... to Set(MemberRecipient(PAR::sv4::12206433dd86..
12:10:27.049 DEBUG sequencer=globalSequencerSv2 | At block 1536, the submission request 1ef2836c-9e69-488d-ba1f-917472d5b46f at 1970-01-01T00:14:43.398Z validated to: DeliverReceipt
12:10:27.111 DEBUG sequencer=globalSequencerSv1 | At block 1536, the submission request 1ef2836c-9e69-488d-ba1f-917472d5b46f at 1970-01-01T00:14:43.398Z validated to: DeliverReceipt
12:10:27.204 DEBUG sequencer=globalSequencerSv3 | At block 1536, the submission request 1ef2836c-9e69-488d-ba1f-917472d5b46f at 1970-01-01T00:14:43.398Z validated to: DeliverReceipt
12:12:11.721 INFO participant=sv4Participant | REQUEST_TIME_OUT(3,55ff73e0): Timed out while awaiting for a completion corresponding to a command submission with command-id=org.lfdecentralizedtrust.splice.validator.createValidatorRight_8eaf6c60f54
12:12:11.721 INFO participant=sv4Participant | Request com.daml.ledger.api.v2.CommandService/SubmitAndWait by grpc:/127.0.0.1:36230: failed with DEADLINE_EXCEEDED/REQUEST_TIME_OUT(3,55ff73e0): Timed out while awaiting for a completion correspondin
```
The request was sequenced (block 1449, epoch 73) and sv4Participant confirmed it. sv1's mediator result (1ef2836c,
an aggregatable send) was sequenced only as `DeliverReceipt` to its sender: the aggregation was still short of its
threshold, so nothing reached sv4Participant. No other result for this request was ever sequenced.

## 5. Mediators: sv4 refused (blacklisted), sv1 sent, sv2 delayed and never sent, sv3 not subscribed yet

```
zcat $F | jq -r 'select(.message|test("1970-01-01T00:14:43\\.311Z")) | select(.logger_name|test("mediator=globalMediatorSv[1-4]")) | select(.message|test("Phase 6|Sent result|Failed to send|Delaying")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("mediator=(?<n>[A-Za-z0-9]+)").n) | \(.message[0:230]|gsub("\n";" ")|gsub(" +";" "))"'
zcat $F | jq -r 'select(.["trace-id"]=="10a65ea1bcb55616966c45fd792dc5fe" and (.logger_name|contains("mediator=globalMediatorSv2")) and (.message|test("Delaying|Sent result"))) | "\(.["@timestamp"][11:23]) globalMediatorSv2 | \(.message[0:120])"'
```
```
12:10:25.912 INFO globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.311Z) with verdict Approve at 1970-01-01T00:15:43.311Z
12:10:25.979 ERROR globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:14:43.311Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,10a65ea1)
12:10:26.060 INFO globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.311Z) with verdict Approve at 1970-01-01T00:15:43.311Z
12:10:27.274 DEBUG globalMediatorSv1 | Sent result for request 1970-01-01T00:14:43.311Z
12:10:29.421 INFO globalMediatorSv2 | Phase 6: Finalized request=RequestId(1970-01-01T00:14:43.311Z) with verdict Approve at 1970-01-01T00:15:43.311Z
```
```
12:10:29.424 globalMediatorSv2 | Delaying sending of verdict by 3000 ms
```
The refusal reason, from the full line (`grep -a '10a65ea1bcb55616966c45fd792dc5fe' | grep 'Failed to send result'`,
first 4 lines of the message):
```
2026-11-10T12:10:25.979Z ERROR c.d.c.s.m.DefaultVerdictSender:mediator=globalMediatorSv4/psid=global-domain::1220ef45e0f4::37-0 | Failed to send result to sequencer for request 1970-01-01T00:14:43.311Z RequestRefused(
  SendAsyncErrorGrpc(
    Request failed for server-DefaultSequencer-0.
  GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,10a65ea1): Mempool received client request but this node is currently blacklist
```
Mediator group states in the log (same count as the 11287 packet):
```
zcat $F | grep -a -o 'MediatorSynchronizerState[^"]\{0,400\}' | grep -o 'active = Seq([^)]*)\|threshold = [0-9]*' | sed -E 's/1220[0-9a-f]{8}/1220/g' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
```
```
484 threshold = 1
191 active = Seq(MED::sv1::1220..., MED::sv4::1220..., MED::sv2::1220...)
237 active = Seq(MED::sv1::1220..., MED::sv4::1220..., MED::sv2::1220..., MED::sv3::1220...)
192 active = Seq(MED::sv1::1220..., MED::sv4::1220...)
237 threshold = 2
```
sv2 delays, so the group of four with threshold 2 was already active for this request (VerdictSender rule from the
11274-11276 packet addendum: with 3 active and threshold 2 nobody waits). sv3's mediator first received an event after
the request:
```
for m in 2 3; do zcat $F | jq -r --arg m "mediator=globalMediatorSv$m" 'select(.logger_name|contains($m)) | select(.message|test("Received event at")) | "\(.["@timestamp"][11:23]) \($m) \(.message[0:90])"' | head -1; done
```
```
12:10:27.473 mediator=globalMediatorSv2 [sv2::12203f779782...] Received event at 1970-01-01T00:14:43.285001Z with hash SHA-256:953
12:10:30.956 mediator=globalMediatorSv3 [sv3::1220f062d674...] Received event at 1970-01-01T00:14:43.392005Z with hash SHA-256:115
```
sv2's delayed send needs the clock past 00:14:46.311Z (request + 3 s). The last results sent before the JVM exit are
for requests below 00:14:44.3Z:
```
zcat $F | jq -r 'select(.logger_name|test("DefaultVerdictSender:mediator=globalMediatorSv[1-4]")) | select(.message|test("Sent result for request")) | "\(.["@timestamp"][11:23]) \(.message)"' | tail -3
```
```
12:12:31.411 Sent result for request 1970-01-01T00:14:44.243Z
12:12:38.269 Sent result for request 1970-01-01T00:14:44.294Z
12:12:38.298 Sent result for request 1970-01-01T00:14:44.294Z
```
Of the 12 refused sv4 results, request 00:14:43.311Z is the only one whose second result never came (the full
`DefaultVerdictSender` list, saved by `zcat $F | grep -a 'DefaultVerdictSender' | jq ... > log/11337/verdict-sender.txt`,
shows sv1 plus sv2 or sv3 for 00:14:43.407Z through 00:14:43.477Z).

## 6. sv4Validator: 120 s timeout, 19 in-flight refusals, circuit breaker, init failure

```
zcat $T | grep -a 'validator=sv4V' | jq -r 'select(.message|test("SUBMISSION_ALREADY_IN_FLIGHT")) | select(.message|test("failed with a retryable")) | select(.logger_name|test("ValidatorApp")) | .["@timestamp"][11:23]' | awk 'NR==1{f=$0} {n++; l=$0} END{print n, "retries refused as in flight,", f, "to", l}'
zcat $T | grep -a -E 'Circuit breaker|Initialization failed|wallet users (started|failed)|DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999822264s' | grep -a 'sv4V\|validator=sv4' | jq -r '"\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>(SpliceCircuitBreaker|ValidatorApp))").n) | \(.message[0:170]|gsub("\n";" "))"'
```
```
19 retries refused as in flight, 12:12:11.860 to 12:12:48.738
12:10:10.411 DEBUG ValidatorApp | sv4Validator app initialization: Onboard validator wallet users started
12:12:11.718 INFO ValidatorApp | The operation 'createValidatorRight' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999822264s. Name
12:12:11.718 INFO ValidatorApp | Detected an error.
12:12:48.737 WARN SpliceCircuitBreaker | Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure
12:12:51.653 INFO ValidatorApp | sv4Validator app initialization: Onboard validator wallet users failed
12:12:51.653 INFO ValidatorApp | sv4Validator app initialization: Initialize node failed
12:12:51.653 INFO ValidatorApp | sv4Validator app initialization: Initialize app failed
12:12:51.654 ERROR ValidatorApp | sv4Validator app initialization: Initialization failed
```
1 timeout + 19 in-flight refusals = the 20 failures of the breaker. The in-flight entry would only clear at max
sequencing time 00:15:43Z (section 4), which the sim clock does not reach during setup.

## 7. Duplicate check

Family B section and the 11255 occurrences in `known-families.md` (lost-verdict shape: 11306, 11308; parent 11255
comment of 2026-11-09 lists 11258, 11275, 11284, 11287, 11306, 11308 as lost-verdict hits). Confirming grep for the
lost-verdict shape: a `Failed to send result` from the blacklisted node's mediator for the stuck request plus a
`Delaying sending of verdict` from another mediator with no later `Sent result` for that request (sections 3 and 5).

Tracker searches:
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "TimeBasedTestNetPreviewIntegrationTest"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason) \(.created_at[0:10]) \(.title[0:100])"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Onboard validator wallet users failed"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason) \(.created_at[0:10]) \(.title[0:100])"'
```
```
#7618 closed/completed 2026-03-19 TimeBasedTestNetPreviewIntegrationTest fails on: TestNet initializes correctly, does not  advance Is
#6274 closed/duplicate 2025-10-30 GHA Run 18940700756 : job simtime (0) in scala_test Failed :fire:
#5967 closed/completed 2025-10-17 EventHistorySanityCheckPlugin: finalizationTime is not equal
#4739 closed/completed 2025-06-26 TimeBasedTestNetPreviewIntegrationTest fails "TestNet initializes correctly" with "Check balances fo
#2106 closed/completed 2025-01-28 TimeBasedTestNetPreviewIntegrationTest - testnet initialize correctly 
```
(the second search returned no hits.) None is this failure: all predate DA BFT in all tests (splice#8234,
2026-11-06); 7618 is a mining-round assertion, 4739 a balance check, 5967 an event-history plugin, 2106 and 6274 from
2025.

Parent and fix state:
```
gh api repos/canton-network/splice/pulls/8356 --jq '"#\(.number) state=\(.state) merged=\(.merged) \(.title)"'
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"#\(.number) [\(.state)] \(.title) assignees=\([.assignees[].login]|join(","))"'
```
```
#8356 state=open merged=false Disable BFT sequencer blacklisting in integration tests
#11255 [open] Timeouts due to changing bft ordering topology assignees=rautenrieth-da
```

## Verdict

Duplicate of 11255, lost-verdict shape (family B, 11255 occurrences). Flake caused by the Canton BFT onboarding
window (sv4 blacklisted after a 2 -> 4 step it was not a newcomer of) combined with simtime setup (the delayed
verdict of the other mediator and the in-flight entry wait for sim time that never comes). First hit of this shape in
TimeBasedTestNetPreviewIntegrationTest and the first one seen on the Canton 3.7.3 snapshot (#8381). Fix: splice#8356
(disable blacklisting in sim-time tests) covers this shape; it is open, so the run cannot contain it. No new fix here.

Not verified: that sv2's 3000 ms delay is scheduled on the sim clock (taken from the 11287 and 11306 analyses; here
only that no later send happened and sequenced time stayed below request + 3 s); the activation time of the
four-mediator group (inferred from sv2 delaying, as in 11306).
