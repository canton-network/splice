# 11240 - splitwellValidator circuit breaker trips on SUBMISSION_ALREADY_IN_FLIGHT during the BFT 1 -> 4 step in CopyVotesIntegrationTest setup (run 42214429624)

DUPLICATE of family B (umbrella 10165), same symptom as 10611 (family B third signature; also the AI signature).
Post-merge main 97df2c4399, canton 3.7.2, all 16 tests passed, checkErrors flagged one WARN: `Circuit breaker
high-priority-connection tripped after 20 failures` on splitwellValidator in CopyVotesIntegrationTest, during the
environment's "Running setup" phase (12:19:05 to 12:22:51). The ordering topology went from 2 to 4 sequencers at
epoch 32 (12:22:20.138) while sv1 had only 2 authenticated P2P peers (below strong quorum 3 until 12:22:23.842) and
the newcomers sv3 and sv2 only set up epoch 32 at 12:22:27.591 and 12:22:31.222; all four epoch-32 segments needed a
view change, the last (block 490) completed at 12:22:34.292, so no epoch-32 block was output before 12:22:34.500.
splitwellValidator's first TopupMemberTrafficTrigger submission (create ValidatorTopUpState) was sent at
12:22:18.450, accepted by sv1's sequencer at 12:22:20.86, delivered back only at 12:22:35.186 (sequencing time
12:22:27.845046Z), and its confirmation result at 12:22:43.549. The test-only 5 s gRPC deadline of the
buy-extra-traffic path cancelled the client wait at 12:22:23.450 (failure 1); the trigger resubmitted the same
command id every ~1 s and got 19 `SUBMISSION_ALREADY_IN_FLIGHT` (failures 2-20, the last at 12:22:43.616); the
breaker tripped at 12:22:43.617, 14 ms after the original submission was stored (Phase 7, 12:22:43.603) and 4 ms
before its dedup entry was updated (12:22:43.621). Unlike 10611, the send was not rejected: it was ordered late.

- Run: https://github.com/canton-network/splice/actions/runs/42214429624, main 97df2c4399 ("Add grafana for verdict
  ingestion tests (#8269)"), job 127881385513 `ci / scala_test_wall_clock_time / wall-clock-time (7)`, attempt 1.
- Runtime canton: 3.7.2 (`nix/canton-sources.json` at 97df2c4399).
- Component: Canton BFT orderer (new ordering topology active before the newcomers are authenticated and caught up);
  splice test config (5 s `buyExtraTraffic.grpcDeadline` in `ConfigTransforms.defaults`) and
  `SpliceCircuitBreaker` (counts DEADLINE_EXCEEDED and SUBMISSION_ALREADY_IN_FLIGHT).

## 1. Issue body

```
gh api repos/DACH-NY/cn-test-failures/issues/11240 --jq '"#\(.number) [\(.state)] \(.title) \(.created_at)\n\(.body)"'
```

```
#11240 [open] GHA Run 42214429624 : job wall-clock-time (7) in scala_test Failed :fire: 2026-11-06T12:36:11Z

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42214429624).
Job: wall-clock-time (7) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [97df2c4](https://github.com/canton-network/splice/commit/97df2c4399431d85f0810c9ad4d69cffb9c7a913) Add grafana for verdict ingestion tests (#8269)
Actor: jagathweerasinghe-da

```

## 2. Job resolution and canton pin

Exactly one failed job matches; one attempt.

```
RUN=42214429624; export NAME='wall-clock-time (7)'
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
gh api repos/canton-network/splice/actions/runs/$RUN --jq '"\(.name) \(.head_branch) \(.head_sha[0:10]) \(.created_at) attempts=\(.run_attempt)"'
git show 97df2c4399431d85f0810c9ad4d69cffb9c7a913:nix/canton-sources.json | grep -m1 version
```

```
127881385513 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (7)
CI post-merge to main or release-line branches main 97df2c4399 2026-11-06T12:00:51Z attempts=1
  "version": "3.7.2",
```

## 3. Classification, flagged line, shard suites

checkErrors failure: all tests passed, one flagged line (the only line without the ignore suffix).

```
# gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/127881385513/logs > log/11240/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11240/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11240/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in check-sbt' | cut -c1-560
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/11240/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```

```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 23 minutes, 54 seconds.
[info] Tests: succeeded 16, failed 0, canceled 0, ignored 4, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-06T12:22:43.617Z","message":"Circuit breaker high-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:CopyVotesIntegrationTest/config=98229cc6/validator=splitwellValidator","thread_name":"CopyVotesIntegrationTest-98229cc6-env-ec-886","level":"WARN","stack_trace":"io.grpc.StatusRuntimeException: ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,d10106f2): The submission is already in-flight\n\tat io.grpc.Status.asRuntimeException(Status.java:532)\n\tat io.grpc.stub.ClientCalls$U
Total: 1 lines with problems.
AutomationControlIntegrationTest
CopyVotesIntegrationTest
SplitwellUpgradeIntegrationTest
SvOnboardingViaNonFoundingSvIntegrationTest
TokenStandardFetchFallbackIntegrationTest
WalletNoDevNetIntegrationTest
WalletSurviveCantonRestartIntegrationTest
WalletSweepToValidatorOperatorIntegrationTest
```

## 4. Suite timeline: the WARN is in CopyVotesIntegrationTest environment setup

The flagged WARN (12:22:43.617) falls in the first CopyVotes test case's "Running setup" (12:19:05.095 to
12:22:51.751). splitwellValidator was the last validator to bootstrap its scan connection (12:22:18.331).

```
# gh run download 42214429624 --repo canton-network/splice -n logs-wall-clock-time-7 -D log/11240/logs-wall-clock-time-7   (artifact 13502119042, created 2026-11-06T12:35:57Z, attempt 1)
D=log/11240/logs-wall-clock-time-7
zcat $D/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished creating environment" | grep -a -E 'T12:(1[89]|2[0-4])' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-160
zcat $D/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-06T12:2[0-2]' | grep -a 'Bootstrapping with seed nodes' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name":"[^"]*(validator=[A-Za-z0-9]+)".*/ \1/'
```

```
2026-11-06T12:18:28.304Z Test succeeded: 'SvOnboardingViaNonFoundingSvIntegrationTest/A new SV can: 1) onboard via a non-sv1 while sv1 is offboarded from the DS
2026-11-06T12:18:31.301Z Starting test suite 'SplitwellUpgradeIntegrationTest'...",
2026-11-06T12:18:31.321Z Starting test suite 'CopyVotesIntegrationTest'...",
2026-11-06T12:18:31.321Z Starting 'CopyVotesIntegrationTest/CopyVotesTrigger mirrors source votes across accept, reject, update, and no-op cases'...",
2026-11-06T12:18:32.117Z Finished creating environment: Running plugins before",
2026-11-06T12:18:32.274Z Finished creating environment: Creating fixture",
2026-11-06T12:18:32.274Z Finished creating environment: Creating test console",
2026-11-06T12:18:32.274Z Finished creating environment: Updating retry policy",
2026-11-06T12:18:32.274Z Finished creating environment: Running plugins after",
2026-11-06T12:19:05.095Z Finished creating environment: Starting all nodes",
2026-11-06T12:22:51.751Z Finished creating environment: Running setup",
2026-11-06T12:23:05.426Z Test succeeded: 'CopyVotesIntegrationTest/CopyVotesTrigger mirrors source votes across accept, reject, update, and no-op cases'",
2026-11-06T12:23:05.427Z Starting 'CopyVotesIntegrationTest/CopyVotesTrigger does not loop when sv1 and sv2 copy votes from each other'...",
2026-11-06T12:23:05.456Z Finished creating environment: Running plugins before",
2026-11-06T12:23:05.545Z Finished creating environment: Creating fixture",
2026-11-06T12:23:05.546Z Finished creating environment: Creating test console",
2026-11-06T12:23:05.546Z Finished creating environment: Updating retry policy",
2026-11-06T12:23:05.546Z Finished creating environment: Running plugins after",
2026-11-06T12:23:38.259Z Finished creating environment: Starting all nodes",
2026-11-06T12:24:16.008Z Finished creating environment: Running setup",
2026-11-06T12:24:24.754Z Test succeeded: 'CopyVotesIntegrationTest/CopyVotesTrigger does not loop when sv1 and sv2 copy votes from each other'",
2026-11-06T12:24:24.762Z Starting test suite 'AutomationControlIntegrationTest'...",
2026-11-06T12:24:24.762Z Starting 'AutomationControlIntegrationTest/report correct rounds as round automation is controlled'...",
2026-11-06T12:24:25.092Z Finished creating environment: Running plugins before",
2026-11-06T12:24:25.294Z Finished creating environment: Creating fixture",
2026-11-06T12:24:25.295Z Finished creating environment: Creating test console",
2026-11-06T12:24:25.295Z Finished creating environment: Updating retry policy",
2026-11-06T12:24:25.295Z Finished creating environment: Running plugins after",
2026-11-06T12:24:41.659Z Finished creating environment: Starting all nodes",
2026-11-06T12:21:36.342Z Bootstrapping with seed nodes to fetch the full network scan list.", validator=bobValidator
2026-11-06T12:21:50.478Z Bootstrapping with seed nodes to fetch the full network scan list.", validator=aliceValidator
2026-11-06T12:22:18.331Z Bootstrapping with seed nodes to fetch the full network scan list.", validator=splitwellValidator
```

## 5. The 20 failures counted by the breaker

One DEADLINE_EXCEEDED after 5 s, then 19 SUBMISSION_ALREADY_IN_FLIGHT, one per trigger poll; the breaker trips on
the 20th. The other failed calls (GetUser, GetUpdates) are not submissions. No breaker reset for
splitwellValidator in the whole log (last count).

```
D=log/11240/logs-wall-clock-time-7
# every failed Ledger API call of splitwellValidator from 12:21 to 12:22:59, by method and error
zcat $D/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -E '"@timestamp":"2026-11-06T12:2[12]:' | grep -a 'ApiClientRequestLogger' | grep -a -o -E 'Request \(tid:[0-9a-f]+\) [A-Za-z.0-9]+/[A-Za-z]+ to [0-9.:]+: failed with [A-Z_]+/[A-Z_]+' | sed -E 's/tid:[0-9a-f]+/tid/' | sort | uniq -c
# the trigger's submissions and the trip
zcat $D/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -E '"@timestamp":"2026-11-06T12:22:(1[89]|[2-4][0-9])' | grep -a -E 'SubmitAndWaitForTransaction to [0-9.:]+: (sending request|failed with)|tripped after|Starting trigger polling loop' | grep -a -E 'TopupMemberTrafficTrigger|tripped|SubmitAndWait' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/Request \(tid:[0-9a-f]+\) com.daml.ledger.api.v2.CommandService\/SubmitAndWaitForTransaction to 127.0.0.1:5701: //; s/(sending request SubmitAndWaitForTransactionRequest).*/\1/; s/(SUBMISSION_ALREADY_IN_FLIGHT\([0-9],[0-9a-f]+\)).*/\1/; s/(deadline exceeded after [0-9.]+s).*/\1/; s/(tripped after 20 failures)[^"]*.*/\1/; s/"logger_name":"([^"]*)".*/ [\1]/; s/:CopyVotesIntegrationTest\/config=98229cc6\/validator=splitwellValidator//' | cut -c1-200
# breaker resets for splitwellValidator in the whole log
zcat $D/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -c 'Resetting circuit breaker'
```

```
     19 Request (tid) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5701: failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT
      1 Request (tid) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5701: failed with DEADLINE_EXCEEDED/C
      1 Request (tid) com.daml.ledger.api.v2.UpdateService/GetUpdates to 127.0.0.1:5701: failed with ABORTED/STALE_STREAM_AUTHORIZATION
      3 Request (tid) com.daml.ledger.api.v2.admin.UserManagementService/GetUser to 127.0.0.1:5701: failed with NOT_FOUND/USER_NOT_FOUND
2026-11-06T12:22:18.388Z Starting trigger polling loop, ConfigSummary(pollingInterval = 1 second, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)", [o.l.s.v.a.TopupMemberTrafficTrigger]
2026-11-06T12:22:18.450Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:23.450Z failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 4.999796767s
2026-11-06T12:22:24.499Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:24.543Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,00f9fab2)
2026-11-06T12:22:25.523Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:25.543Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,1340eb5f)
2026-11-06T12:22:26.505Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:26.524Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,7c3d1915)
2026-11-06T12:22:27.539Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:27.563Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,3b6f2a10)
2026-11-06T12:22:28.650Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:28.669Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,e655cc68)
2026-11-06T12:22:29.767Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:29.784Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,34fb8f52)
2026-11-06T12:22:30.752Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:30.781Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,ee99f5dc)
2026-11-06T12:22:31.769Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:31.788Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,8a634f63)
2026-11-06T12:22:32.732Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:32.751Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,029d029a)
2026-11-06T12:22:33.824Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:33.842Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,e3227c6e)
2026-11-06T12:22:34.915Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:34.947Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,156e6474)
2026-11-06T12:22:36.069Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:36.220Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,fcdfb00a)
2026-11-06T12:22:37.222Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:37.245Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,ed481ae6)
2026-11-06T12:22:38.263Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:38.311Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,26f5bff2)
2026-11-06T12:22:39.310Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:39.340Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,7431647e)
2026-11-06T12:22:40.381Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:40.416Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,5b3c88a9)
2026-11-06T12:22:41.465Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:41.485Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,8a0d6ba1)
2026-11-06T12:22:42.430Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:42.452Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,4b3977ca)
2026-11-06T12:22:43.550Z sending request SubmitAndWaitForTransactionRequest
2026-11-06T12:22:43.616Z failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,d10106f2)
2026-11-06T12:22:43.617Z Circuit breaker high-priority-connection tripped after 20 failures
2026-11-06T12:22:46.524Z sending request SubmitAndWaitForTransactionRequest
0
```

## 6. All 19 rejections point at the first submission

```
D=log/11240/logs-wall-clock-time-7
# all 19 rejections name the same in-flight submission; their error category
zcat $D/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a 'TopupMemberTrafficTrigger' | grep -a -o -E 'existingSubmissionId -> Some\([0-9a-f-]+|changeId -> ChangeId\([^,]+,[^,]{0,90}|category=Some\([A-Za-z]+\)' | sort | uniq -c
```

```
     19 category=Some(ContentionOnSharedResources)
     19 changeId -> ChangeId(splitwell_validator_user-98229cc6,org.lfdecentralizedtrust.splice.validator.automation.TopupMemberTrafficTrigger.getOrCreate
     19 existingSubmissionId -> Some(0e2bec6b-f74b-4ede-ac1d-92c1855ff09e
```

## 7. The first submission on splitwellParticipant, end to end

Sent to sv1's sequencer at 12:22:20.860; client deadline cancelled the wait at 12:22:23.451; the deliver
(sequencing time 12:22:27.845046Z) arrived at 12:22:35.186; Phase 4 response sent 12:22:35.342; the confirmation
result (sequenced 12:22:43.240704Z) processed at 12:22:43.549; stored 12:22:43.603; dedup updated 12:22:43.621.
Total 25.2 s from the request (12:22:18.450, section 5) to Phase 7.

```
D=log/11240/logs-wall-clock-time-7
# splitwellParticipant: the in-flight submission 0e2bec6b (message c83c8959, request 12:22:27.845046Z) end to end
zcat $D/canton_before_shutdown.clog.gz | grep -a 'participant=splitwellParticipant' | grep -a -E '"@timestamp":"2026-11-06T12:22:' | grep -a -E 'Registering submission InFlightSubmission\(\\n  change ID hash = ChangeIdHash\(8861f04f2bd0...\),\\n  submission id = 0e2bec6b|REQUEST_TIME_OUT\(3,0e2bec6b\): Timed out|Sending message ID c83c8959|Validating sequenced event .* with timestamp 2026-11-06T12:22:27.845046Z|Phase 3: Validating Transaction request=2026-11-06T12:22:27.845046Z|Phase 4: Sending for request=2026-11-06T12:22:27.845046Z|Processing event at sc=[0-9]+, ts=[^,]+, with contents=SignedProtocolMessage\(\\n  ConfirmationResultMessage\(psid = [^,]+, viewType = TransactionViewType, requestId = 2026-11-06T12:22:27.845046Z|Phase 7: Storing at offset=181|Updated command deduplication data for Hash\(8861f04f' | grep -a -v 'Request com.daml' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g; s/ +/ /g; s/"logger_name":"([^":]*)[^"]*".*"level":"([A-Z]+)".*/ [\1] \2/' | cut -c1-200
```

```
2026-11-06T12:22:20.840Z Registering submission InFlightSubmission( change ID hash = ChangeIdHash(8861f04f2bd0...), submission id = 0e2bec6b-f74b-4ede-ac1d-92c1855ff09e, submission synchronizer = glob
2026-11-06T12:22:20.860Z Sending message ID c83c8959-7965-49b1-ae57-7a47a34788a7 to sequencer SEQ::sv1::1220dffd54b5...", [c.d.c.s.c.RichSequencerClientImpl] DEBUG
2026-11-06T12:22:23.451Z REQUEST_TIME_OUT(3,0e2bec6b): Timed out while awaiting for a completion corresponding to a command submission with command-id=org.lfdecentralizedtrust.splice.validator.automat
2026-11-06T12:22:35.186Z Validating sequenced event coming from SEQ::sv1::1220dffd54b5... (alias = Sequencer 'sequencer-connection-SEQ::sv1::..c705-0') with timestamp 2026-11-06T12:22:27.845046Z", [c.
2026-11-06T12:22:35.227Z Phase 3: Validating Transaction request=2026-11-06T12:22:27.845046Z with 1 envelope(s)", [c.d.c.p.p.TransactionProcessor] INFO
2026-11-06T12:22:35.342Z Phase 4: Sending for request=2026-11-06T12:22:27.845046Z with msgId=424bd212-f5b4-4063-9040-220d8eef1545 approved=1, rejected=0, abstained=0", [c.d.c.p.p.TransactionProcessor]
2026-11-06T12:22:43.549Z Processing event at sc=108, ts=2026-11-06T12:22:43.240704Z, with contents=SignedProtocolMessage( ConfirmationResultMessage(psid = global-domain::12204a84902e...::37-0, viewTyp
2026-11-06T12:22:43.603Z Phase 7: Storing at offset=181 SequencedTransactionAccepted( recordTime = 2026-11-06T12:22:27.845046Z, updateId = ..8b98, transactionMeta = TransactionMeta(ledgerEffectiveTime
2026-11-06T12:22:43.621Z Updated command deduplication data for Hash(8861f04f2bd0a12ba7ea797ee4e83dfa81a812c9722420b30cc8c0b4e5f8d3d8) to CommandDeduplicationData( change id = ChangeId( user Id = spli
```

## 8. Sequencer side: batched in epoch 32 at 12:22:20.898, output only at 12:22:34.920

```
D=log/11240/logs-wall-clock-time-7
# sequencer side of message c83c8959: accepted by sv1, batched in epoch 32, output in block 570 at 12:22:34.9
zcat $D/canton_before_shutdown.clog.gz | grep -a 'c83c8959-7965-49b1-ae57-7a47a34788a7' | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'sends request with id|LocalBatchCreated|in output block|validated to: Deliver' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/"logger_name":"[^"]*sequencer=([^/"]*)[^"]*".*"level":"([A-Z]+)".*/ [\1] \2/' | cut -c1-240
```

```
2026-11-06T12:22:20.898Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..1e21) with reference epoch 32 for batch received from local mempool containing 2 requests (tag=send, messageId=0c6f9ffb-c7f5-483b-b208-f5
2026-11-06T12:22:34.920Z BFT ordering request at index 6 in output block (epochNumber=33, blockNumber=570) with message ID c83c8959-7965-49b1-ae57-7a47a34788a7 may change ordering topology: false", [globalSequencerSv1] DEBUG
2026-11-06T12:22:34.957Z At block 570, the submission request c83c8959-7965-49b1-ae57-7a47a34788a7 at 2026-11-06T12:22:27.845046Z validated to: Deliver", [globalSequencerSv1] DEBUG
```

## 9. BFT epochs on sv1: the 2 -> 4 step at epoch 32, then sv4 blacklisted for epochs 33-35

Epoch 32 lasted 14.6 s (12:22:20.138 to 12:22:34.773); earlier epochs took 4-20 s at size 1-2.

```
D=log/11240/logs-wall-clock-time-7
# sv1 BFT epochs 12:21-12:23: ordering topology size and blacklist
zcat $D/canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E '"@timestamp":"2026-11-06T12:2[12]:' | grep -a 'New epoch [0-9]* has started' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\); ordering topology = OrderingTopology\(\\n  activationTime = ([^,]*),\\n  size = ([0-9]+).*/leaders=[\1] blacklisted=[\2] activation=\3 size=\4/'
```

```
2026-11-06T12:21:01.376Z New epoch 22 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:20:36.129857Z size=1
2026-11-06T12:21:21.155Z New epoch 23 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:20:36.129857Z size=1
2026-11-06T12:21:34.606Z New epoch 24 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:20:36.129857Z size=1
2026-11-06T12:21:42.531Z New epoch 25 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:20:36.129857Z size=1
2026-11-06T12:21:48.659Z New epoch 26 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:20:36.129857Z size=1
2026-11-06T12:21:53.616Z New epoch 27 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:20:36.129857Z size=1
2026-11-06T12:21:57.813Z New epoch 28 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:20:36.129857Z size=1
2026-11-06T12:22:02.321Z New epoch 29 leaders=[SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:22:01.908488Z size=1
2026-11-06T12:22:06.619Z New epoch 30 leaders=[SEQ::sv1::..c705, SEQ::sv4::..faeb] blacklisted=[] activation=2026-11-06T12:22:06.149429Z size=2
2026-11-06T12:22:15.307Z New epoch 31 leaders=[SEQ::sv4::..faeb, SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:22:06.189428Z size=2
2026-11-06T12:22:20.138Z New epoch 32 leaders=[SEQ::sv1::..c705, SEQ::sv2::..28e8, SEQ::sv3::..3510, SEQ::sv4::..faeb] blacklisted=[] activation=2026-11-06T12:22:19.653611Z size=4
2026-11-06T12:22:34.773Z New epoch 33 leaders=[SEQ::sv1::..c705, SEQ::sv2::..28e8, SEQ::sv3::..3510] blacklisted=[SEQ::sv4::..faeb] activation=2026-11-06T12:22:19.653611Z size=4
2026-11-06T12:22:43.704Z New epoch 34 leaders=[SEQ::sv2::..28e8, SEQ::sv3::..3510, SEQ::sv1::..c705] blacklisted=[SEQ::sv4::..faeb] activation=2026-11-06T12:22:19.653611Z size=4
2026-11-06T12:22:49.079Z New epoch 35 leaders=[SEQ::sv3::..3510, SEQ::sv1::..c705, SEQ::sv2::..28e8] blacklisted=[SEQ::sv4::..faeb] activation=2026-11-06T12:22:19.653611Z size=4
2026-11-06T12:22:54.642Z New epoch 36 leaders=[SEQ::sv1::..c705, SEQ::sv2::..28e8, SEQ::sv3::..3510, SEQ::sv4::..faeb] blacklisted=[] activation=2026-11-06T12:22:19.653611Z size=4
2026-11-06T12:22:58.726Z New epoch 37 leaders=[SEQ::sv2::..28e8, SEQ::sv3::..3510, SEQ::sv4::..faeb, SEQ::sv1::..c705] blacklisted=[] activation=2026-11-06T12:22:19.653611Z size=4
```

## 10. Why epoch 32 stalled: quorum and newcomer catch-up

At the epoch-32 start sv1 had 2 authenticated nodes, below strong quorum 3, until 12:22:23.842. The newcomers set up
epoch 32 only at 12:22:27.591 (sv3) and 12:22:31.222 (sv2). All four segments of epoch 32 went through a view
change; sv1's own segment (block 490) completed last at 12:22:34.292 and the first epoch-32 blocks were output at
12:22:34.500, after which the submission was delivered (section 7).

```
D=log/11240/logs-wall-clock-time-7
# P2P authentication on sv1 and the first epoch-32 output on sv1 (epoch 32 started 12:22:20.138)
zcat $D/canton_before_shutdown.clog.gz | grep -a -E '"@timestamp":"2026-11-06T12:22:(19|2[0-9]|3[0-5])' | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'Authenticated P2P nodes count|View change completed; metadata: \(epochNumber=32|Polled blocks from Peano queue List\(\(epochNumber=32, blockNumber=490\)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/(Polled blocks from Peano queue List\(\(epochNumber=32, blockNumber=[0-9]+\)).*"logger_name"/\1, ...)"logger_name"/; s/"logger_name":"[^"]*sequencer=([^/"]*)[^"]*".*"level":"([A-Z]+)".*/ [\1] \2/' | cut -c1-200
# when sv2 and sv3 (the newcomers) set up epoch 32
zcat $D/canton_before_shutdown.clog.gz | grep -a -E '"@timestamp":"2026-11-06T12:22:' | grep -a -E 'sequencer=globalSequencerSv[23]/' | grep -a 'Setting up new epoch 32' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name":"[^"]*sequencer=([^/"]*)[^"]*".*"level":"([A-Z]+)".*/ [\1] \2/'
```

```
2026-11-06T12:22:20.135Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated", [global
2026-11-06T12:22:23.830Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated", [global
2026-11-06T12:22:23.842Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3", [globalSequencerSv1] INFO
2026-11-06T12:22:27.625Z View change completed; metadata: (epochNumber=32, blockNumber=493), view: 1", [globalSequencerSv1] INFO
2026-11-06T12:22:27.636Z View change completed; metadata: (epochNumber=32, blockNumber=491), view: 1", [globalSequencerSv1] INFO
2026-11-06T12:22:27.655Z View change completed; metadata: (epochNumber=32, blockNumber=492), view: 1", [globalSequencerSv1] INFO
2026-11-06T12:22:34.292Z View change completed; metadata: (epochNumber=32, blockNumber=490), view: 1", [globalSequencerSv1] INFO
2026-11-06T12:22:34.500Z Polled blocks from Peano queue List((epochNumber=32, blockNumber=490), ...) [globalSequencerSv1] DEBUG
2026-11-06T12:22:27.591Z Setting up new epoch 32", [globalSequencerSv3] DEBUG
2026-11-06T12:22:31.222Z Setting up new epoch 32", [globalSequencerSv2] DEBUG
```

## 11. Code at 97df2c4399: the submission, the test-only 5 s deadline, the breaker's ignore lists

`TopupMemberTrafficTrigger.getOrCreateValidatorTopupState` submits with `deadline = grpcDeadline` (the validator's
`domains.global.buyExtraTraffic.grpcDeadline`, documented in `ValidatorAppConfig.scala` as "only intended for
testing purposes"). `ConfigTransforms.defaults` sets it to 5 s for every test environment. The breaker ignores
neither DEADLINE_EXCEEDED nor SUBMISSION_ALREADY_IN_FLIGHT (category ContentionOnSharedResources, section 6).

```
S=97df2c4399431d85f0810c9ad4d69cffb9c7a913
git show $S:apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/automation/TopupMemberTrafficTrigger.scala | sed -n '226,240p'
git show $S:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala | sed -n '193,203p;285,303p'
git show $S:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/util/SpliceCircuitBreaker.scala | sed -n '47,57p'
```

```
          topupState <- connection
            .submit(
              Seq(validator),
              Seq(validator),
              ValidatorTopUpState.create(
                store.key.dsoParty.toProtoPrimitive,
                validator.toProtoPrimitive,
                participantId.toProtoPrimitive,
                activeSynchronizerId.toProtoPrimitive,
                domainMigrationId,
                Instant.ofEpochSecond(0),
              ),
              priority = CommandPriority.High,
              deadline = grpcDeadline,
            )
  def defaults(testId: Option[String] = None): Seq[ConfigTransform] = {
    Seq(
      makeAllTimeoutsBounded,
      ensureNovelDamlNames(testId),
      useSelfSignedTokensForAuth,
      reducePollingInterval,
      withPausedSvDomainComponentsOffboardingTriggers(),
      disableOnboardingParticipantPromotionDelay(),
      setDefaultGrpcDeadlineForBuyExtraTraffic(),
      setDefaultGrpcDeadlineForTreasuryService(),
      updateAllAutomationConfigs(
  def setDefaultGrpcDeadlineForBuyExtraTraffic(): ConfigTransform =
    ConfigTransforms.updateAllValidatorAppConfigs_(c =>
      c.copy(domains =
        c.domains.copy(global =
          c.domains.global.copy(buyExtraTraffic =
            c.domains.global.buyExtraTraffic
              .copy(grpcDeadline = Some(NonNegativeFiniteDuration.ofSeconds(5)))
          )
        )
      )
    )

  def setDefaultGrpcDeadlineForTreasuryService(): ConfigTransform =
    ConfigTransforms.updateAllValidatorAppConfigs_(c =>
      c.copy(treasury =
        c.treasury.copy(
          grpcDeadline = Some(NonNegativeFiniteDuration.ofSeconds(30))
        )
      )
  private val errorCategoriesToIgnore: Set[ErrorCategory] = Set(
    InvalidIndependentOfSystemState,
    InvalidGivenCurrentSystemStateOther,
    InvalidGivenCurrentSystemStateResourceExists,
    InvalidGivenCurrentSystemStateResourceMissing,
    InvalidGivenCurrentSystemStateSeekAfterEnd,
  )

  private val errorCodesToIgnore: Set[LocalRejectErrorCode] = Set(
    LockedContracts
  )
```

## 12. Current main: still 5 s; the treasury deadline was raised to 30 s in #7909 to outlast sequencer stalls

```
# current main still has the 5 s deadline; #7909 raised the treasury one to 30 s
git fetch -q --no-tags https://github.com/canton-network/splice.git main:refs/remotes/https-origin/main
git log -1 --format='%h %ad' --date=iso https-origin/main
git show https-origin/main:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala | grep -n -A8 'def setDefaultGrpcDeadline' | grep -E 'def |ofSeconds'
git log --format='%h %ad %s' --date=short -S'ofSeconds(30)' https-origin/main -- apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala
```

```
c218905f75 2026-11-06 14:01:16 +0000
283:  def setDefaultGrpcDeadlineForBuyExtraTraffic(): ConfigTransform =
289-              .copy(grpcDeadline = Some(NonNegativeFiniteDuration.ofSeconds(5)))
295:  def setDefaultGrpcDeadlineForTreasuryService(): ConfigTransform =
299-          grpcDeadline = Some(NonNegativeFiniteDuration.ofSeconds(30))
c8542ad58a 2026-10-23 Raise test-only treasury gRPC deadline to 30s to outlast reference-driver insert-block stalls (#7909)
```

## 13. Duplicate check: known families and index

Hits, one by one:
- known-families line 50 / 664 (AI): same signature (high-priority breaker, SUBMISSION_ALREADY_IN_FLIGHT,
  TopupMemberTrafficTrigger); the confirm grep (20 failures for the same trigger's resubmissions) holds here with one
  DEADLINE_EXCEEDED plus 19 rejections (sections 5, 6). AI does not name the cause of the slow first submission.
- known-families line 79-83 (family B third signature, 10611, CopyVotesIntegrationTest): same test, same trigger,
  same BFT 1 -> N step during setup; differs in the hop: 10611's send was rejected (`P2P connectivity is not ready`),
  here it was accepted and ordered 14 s late (sections 8-10). Same failure class.
- INDEX 10611: same failure (above). INDEX 11147: different (sim-clock jumps, medium-priority breaker, SV app).
  INDEX 11207: different (release-line treasury tap 429, missing backport of #7909), though the same deadline
  pattern.

```
grep -n -E 'SUBMISSION_ALREADY_IN_FLIGHT|tripped after 20|CopyVotes' ci-triage/known-families.md | cut -c1-180
awk -F'\t' '$7 ~ /SUBMISSION_ALREADY_IN_FLIGHT|circuit breaker/ {print $1, $2, $5, substr($7,1,110), "|", $8}' ci-triage/evidence/INDEX.tsv
```

```
50:| AI | SUBMISSION_ALREADY_IN_FLIGHT trips the validator circuit breaker | 9752 (dups 10300, 11173) | closed 9752, fix cni#2785 status unknown |
79:- Third signature (10611, 2026-10-26, CopyVotesIntegrationTest): a participant send rejected with
83:  `SUBMISSION_ALREADY_IN_FLIGHT`, and after 20 the `SpliceCircuitBreaker` WARNs `tripped after 20 failures`.
664:## AI. SUBMISSION_ALREADY_IN_FLIGHT counted as failures by the circuit breaker (checkErrors)
665:- Signature (log check only, canton_network_test.clog): WARN `Circuit breaker high-priority-connection tripped after 20
666:  failures` with stack `ABORTED: SUBMISSION_ALREADY_IN_FLIGHT`, usually on a validator's TopupMemberTrafficTrigger.
667:- Confirm: the 20 failures before the trip are `SUBMISSION_ALREADY_IN_FLIGHT` for the same trigger's resubmissions
10611 2026-10-27 121541858995 `wall-clock-time (8)` aliceValidator circuit breaker WARN on 20x SUBMISSION_ALREADY_IN_FLIGHT; first top-up send rejected by sv1 BFT | 10165 (family B)
11147 2026-11-03 125500702596 `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)` ScanNewNetworkBackfillingTimeBasedIntegrationTest hourly sim jumps: after the jump at 19:54:35.970 sv2 logs 47 | new (AF), related 10475
11207 2026-11-05 127132238725 `wall-clock-time (8)` ValidatorReonboardingWithPartiesToMigrateIntegrationTest tap: treasury SubmitAndWait DEADLINE_EXCEEDED at the  | new (as 10473, missing backport of #7909)
```

## 14. Duplicate check: board export (2026-11-06 13:28)

- triaged 10731: different (TopupMemberTrafficTrigger race on traffic totals in a test assertion, no breaker).
- triaged 11207: different (see section 13).
- inbox 11173: same AI signature (aliceValidator, DistributedDomainIntegrationTest per known-families); not
  re-analysed here, so its first-submission cause is not known; candidate sibling only.
- in-progress 10165: the family B umbrella, open, no assignee.

```
X=/run/sandbox/source/ci-triage/export; ls -l --time-style=+%Y-%m-%dT%H:%M $X/inbox.jsonl $X/triaged.jsonl $X/in-progress.jsonl | awk '{print $6, $7}'
for f in inbox triaged in-progress; do jq -r --arg f $f 'select((.title + " " + .body + " " + ([.comments[].body] | join(" "))) | test("tripped after|SUBMISSION_ALREADY_IN_FLIGHT|CopyVotes|TopupMemberTraffic")) | "\($f) #\(.number) \(.createdAt[0:10]) \(.title[0:100])"' $X/$f.jsonl; done
for f in inbox triaged in-progress; do jq -r --arg f $f 'select(.number == 10165 or .number == 11173) | "\($f) #\(.number) \(.title[0:80])"' $X/$f.jsonl; done
```

```
2026-11-06T13:28 /run/sandbox/source/ci-triage/export/in-progress.jsonl
2026-11-06T13:28 /run/sandbox/source/ci-triage/export/inbox.jsonl
2026-11-06T13:28 /run/sandbox/source/ci-triage/export/triaged.jsonl
triaged #10731 2026-10-28 GHA Run 40668702825 : job wall-clock-time (1) in scala_test Failed :fire:
triaged #11207 2026-11-05 ValidatorReonboardingWithPartiesToMigrateIntegrationTest (0.9 release lines): tap 429 SUBMISSION_ALR
inbox #11173 GHA Run 41674340437 : job wall-clock-time (3) in scala_test Failed :fire:
in-progress #10165 sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 seque
```

## 15. Duplicate check: GitHub search (open and closed)

Per hit:
- 10300 (2026-10-07, closed duplicate): same signature, CopyVotesIntegrationTest, bobValidator
  TopupMemberTrafficTrigger breaker (comment in section 16); same failure (cause not analysed there).
- 9752 (2026-08-24, closed completed): same signature (aliceValidator, FeaturedAppActivityMarkerIntegrationTest);
  closed after no recurrence, not as fixed.
- 10911 (2026-10-29, closed not_planned as dup of 10165): CopyVotes setup, different symptom (mediator acknowledge
  DEADLINE_EXCEEDED), same family B cause.
- 11207, 10473: different (treasury tap 429 path).
- 9487, 7925, 8243, 8414, 5726, 6502, 5606, 20: older or other breakers (medium priority, LSU, SV frontend,
  treasury, shutdown); not the same failure.
- 9207, 7865, 5610, 3641, 5151, 3536, 1439, 3533: SUBMISSION_ALREADY_IN_FLIGHT in other flows (tap, splitwell,
  ANS); not the same failure.
- 9742, 10183, 8430, 8335, 8144: CopyVotesIntegrationTest named for other reasons (timeouts, onboarding, clock
  warn); not the same failure.

```
for q in '"tripped after 20 failures"' '"SUBMISSION_ALREADY_IN_FLIGHT"' 'CopyVotesIntegrationTest'; do echo "== $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q" -f per_page=50 --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:100])"'; done
```

```
== "tripped after 20 failures"
#20 closed/completed 2024-10-02 something bad happened
#9487 closed/completed 2026-08-05 Circuit breaker medium-priority-connection tripped after 20 failures
#10300 closed/duplicate 2026-10-07 GHA Run 37620873312 : job wall-clock-time (7) in scala_test Failed :fire:
#9752 closed/completed 2026-08-24 Log problems: SUBMISSION_ALREADY_IN_FLIGHT trips the circuit-breaker
#7925 closed/completed 2026-04-07 `Circuit breaker high-priority-connection tripped after 20 failures` in SvFrontendIntegrationTest
#8243 closed/duplicate 2026-05-01 circuit breaker trips in LSU test
#8414 closed/completed 2026-05-13 LsuIntegrationTest: `sequencerUrlSet` misses a sequencer
#5726 closed/completed 2025-09-24 Circuit breaker high-priority-connection tripped after 20 failures
#6502 closed/completed 2025-11-13 ManualSignatureIntegrationTest : Canton shuts down in the middle of the test
#5606 closed/completed 2025-09-18 Circuit breaker treasury tripped
== "SUBMISSION_ALREADY_IN_FLIGHT"
#11207 open/- 2026-11-05 ValidatorReonboardingWithPartiesToMigrateIntegrationTest (0.9 release lines): tap 429 SUBMISSION_ALR
#10473 closed/completed 2026-10-20 WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest: tap returns HTTP 429 SUBMISSION_ALREADY_IN
#10300 closed/duplicate 2026-10-07 GHA Run 37620873312 : job wall-clock-time (7) in scala_test Failed :fire:
#9752 closed/completed 2026-08-24 Log problems: SUBMISSION_ALREADY_IN_FLIGHT trips the circuit-breaker
#9207 closed/not_planned 2026-07-14 WalletMetricsTest: User wallet automation metrics should are labeled with the party ID of the wallet
#7865 closed/completed 2026-04-01 SplitwellIntegrationTest should allocate unique groups per party, even when multiple requests race f
#5610 closed/completed 2025-09-18 Validator1PreflightIntegrationTest fails at "test splitwell group creation and payment against valid
#3641 closed/not_planned 2025-04-14 AppUpgradeIntegrationTest tap fails on SUBMISSION_ALREADY_IN_FLIGHT
#5151 closed/completed 2025-07-25 AnsIntegrationTest: ans should allocate unique ans entries failed
#3536 closed/not_planned 2024-10-28 Tap times out / DEADLINE_EXCEEDED then SUBMISSION_ALREADY_IN_FLIGHT
#1439 closed/duplicate 2024-12-18 Pipeline 268085 : job 1917477 Failed :fire:
#3533 closed/duplicate 2025-04-07 GHA Run 14302540970 : job scala_test Failed :fire:
== CopyVotesIntegrationTest
#10911 closed/not_planned 2026-10-29 GHA Run 40868731583 : job wall-clock-time (5) in scala_test Failed :fire:
#10300 closed/duplicate 2026-10-07 GHA Run 37620873312 : job wall-clock-time (7) in scala_test Failed :fire:
#9742 closed/completed 2026-08-20 WalletSweepToEndUserIntegrationTest : slow wall-clock run times out, no particular test took especia
#10183 closed/completed 2026-09-21 SvStateManagementIntegrationTest teardown: the fourth owner's decentralized-namespace reset proposal
#8430 closed/duplicate 2026-05-14 Found problems in log/canton_network_test.clog: (POST) resulted in a timeout after 38 seconds
#8335 closed/completed 2026-05-08 CopyVotesIntegrationTest: splitwellValidator failed `/api/sv/v0/onboard/validator`: `UNKNOWN_CONTRAC
#8144 closed/completed 2026-04-22 CopyVotesIntegrationTest - timestamp in the future log warn
```

## 16. Candidate parents: state

```
for n in 9752 10300 10611 10911 10165 11173; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"#\(.number) \(.state)/\(.state_reason // "-") created=\(.created_at[0:10]) assignees=[\([.assignees[].login]|join(","))] \(.title[0:90])"'; gh api "repos/DACH-NY/cn-test-failures/issues/$n/comments?per_page=100" --jq '.[] | "   -- \(.user.login) \(.created_at[0:10]): \(.body[0:260] | gsub("\n";" "))"' | iconv -f utf-8 -t ascii//TRANSLIT; done
```

```
#9752 closed/completed created=2026-08-24 assignees=[] Log problems: SUBMISSION_ALREADY_IN_FLIGHT trips the circuit-breaker
   -- OriolMunoz-da 2026-08-24: ```  Found problems in log/canton_network_test.clog:       ***"@timestamp":"2026-08-24T08:01:13.040Z","message":"Circuit breaker high-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:FeaturedApp
   -- stephencompall-DA 2026-09-28: No recurrences >2 weeks.
#10300 closed/duplicate created=2026-10-07 assignees=[] GHA Run 37620873312 : job wall-clock-time (7) in scala_test Failed :fire:
   -- OriolMunoz-da 2026-10-07: ``` 2026-10-07T12:41:30.575Z [?] DEBUG - o.l.s.a.a.c.ApiClientRequestLogger:CopyVotesIntegrationTest/config=bb35555b/validator=bobValidator (94369a06323d2866831fe43f59bac55e-TopupMemberTrafficTrigger-work-07e2c08bc297b4f0-fb3b3d8556b4165e) - Request (tid:4bb7e
#10611 closed/not_planned created=2026-10-26 assignees=[] GHA Run 40313835584 : job wall-clock-time (8) in scala_test Failed :fire:
   -- ray-roestenburg-da 2026-10-27: Duplicate of #10165
#10911 closed/not_planned created=2026-10-29 assignees=[] GHA Run 40868731583 : job wall-clock-time (5) in scala_test Failed :fire:
   -- ray-roestenburg-da 2026-10-29: Duplicate of #10165: all 26 tests pass; checkErrors flags globalMediatorSv1 acknowledge-signed DEADLINE_EXCEEDED after 120 s to the sv1 sequencer during CopyVotesIntegrationTest (12:11:08). Not related to the secret rotation.
#10165 open/- created=2026-09-17 assignees=[] sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 sequencer-add t
   -- ray-roestenburg-da 2026-09-17: BFT sequencer onboarding step activates the new ordering topology before the newcomers are P2P-authenticated; incumbents lose quorum for 5-25 s and get blacklisted, surfacing as checkErrors WARNs  Seen on main (canton 3.6.0-snapshot.20260910 and .20260916) and
   -- ray-roestenburg-da 2026-09-17: ``` export RUN=35223275137 JOB=105208906926 D=log/10165/logs-wall-clock-time-9 mkdir -p log/10165 gh api repos/canton-network/splice/actions/jobs/$JOB/logs > log/10165/job.log gh run download $RUN --repo canton-network/splice -n logs-wall-clock-time-9 -D $D gh
#11173 open/- created=2026-11-03 assignees=[] GHA Run 41674340437 : job wall-clock-time (3) in scala_test Failed :fire:
```

## Verdict

Duplicate of 10165 (family B: BFT ordering topology activated before newcomers are authenticated and caught up),
with the 10611 / AI symptom: the first TopupMemberTrafficTrigger submission of a validator started during the
1 -> 4 step outlives the test-only 5 s gRPC deadline, the 19 resubmissions are SUBMISSION_ALREADY_IN_FLIGHT, and the
breaker WARN fails checkErrors. Flake, not caused by the commit under test.

Fix location: the root cause is Canton (family B; no splice change fixes the 14 s ordering stall). A cheap
splice-side mitigation for this symptom, in the test config only: raise
`ConfigTransforms.setDefaultGrpcDeadlineForBuyExtraTraffic` from 5 s to 30 s (same change as #7909 for the
treasury). Here the submission completed 25.2 s after it was sent, so 30 s would likely have avoided all 20
failures (not verified: assumes the completion would have been observed at the same time). The other option, adding
SUBMISSION_ALREADY_IN_FLIGHT to `SpliceCircuitBreaker` ignores, would still count the DEADLINE_EXCEEDED and changes
production behaviour. No fix branch.

Not verified: 11173's first-submission cause (sibling candidate in the inbox); why the participant registered the
submission 2.4 s after the request (12:22:18.450 to 12:22:20.840).
