# 11173 - aliceValidator circuit breaker trips on SUBMISSION_ALREADY_IN_FLIGHT during a 23 s BFT output stall (sv4 segment) in DistributedDomainIntegrationTest (run 41674340437)

DUPLICATE of family B (umbrella 10165), same symptom as 11240 and 10611 (AI signature). Post-merge main 8dacec5c10
("Monitor event count and event rate (#8207)"), canton 3.7.1, all 28 tests passed, checkErrors flagged one WARN:
`Circuit breaker high-priority-connection tripped after 20 failures` on aliceValidator in
DistributedDomainIntegrationTest. That test onboards the sv3 and sv4 sequencers: the ordering topology went from 2 to
4 sequencers at epoch 279 (17:00:46.106); sv4 was blacklisted in epochs 280-282, back as a leader in epoch 283
(17:01:13.886), failed its segment again and was blacklisted in epochs 284-288 (until 17:02:11). In epoch 283 sv1
output blocks up to 7423 (17:01:15.228) and then nothing until block 7480 (17:01:38.317): view changes for all four
segments (view 1, completed 17:01:21.778 to 17:01:25.981) and a second one for sv4's segment (view 2, completed
17:01:33.679).

aliceValidator's first TopupMemberTrafficTrigger submission (create ValidatorTopUpState) was sent at 17:01:14.563;
the confirmation request and the confirmation response were ordered within 0.6 s (blocks 7413 and 7423), the
mediators finalized at 17:01:19.905, but the mediator's result message (block 7481) was only output at 17:01:38.3,
and alice stored the transaction at 17:01:38.648. The test-only 5 s gRPC deadline expired at 17:01:19.563 (failure
1), the trigger resubmitted the same command every ~1 s and got 19 `SUBMISSION_ALREADY_IN_FLIGHT` (failures 2-20,
from 17:01:20.567), the breaker tripped at 17:01:38.537.

Difference to 11240: there the step epoch itself stalled (newcomers not authenticated); here it is the first epoch
after sv4's first blacklisting ended, 27 s after the step. Both are inside the sv3/sv4 onboarding window.

- Run: https://github.com/canton-network/splice/actions/runs/41674340437, job 126052138512
  `ci / scala_test_wall_clock_time / wall-clock-time (3)`, attempt 1.
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at 8dacec5c10). Wall-clock shard, already on Canton BFT before
  splice#8234.
- Component: Canton BFT orderer (newcomer sv4's segment stalls, output blocked until view change); splice test
  config (5 s `buyExtraTraffic.grpcDeadline`, `ConfigTransforms.setDefaultGrpcDeadlineForBuyExtraTraffic`, still 5 s
  on main 0b2ccec235) and `SpliceCircuitBreaker` counting DEADLINE_EXCEEDED and SUBMISSION_ALREADY_IN_FLIGHT.

## 1. Job resolution, canton pin, classification

```
RUN=41674340437; export NAME='wall-clock-time (3)'
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
gh api repos/canton-network/splice/actions/runs/$RUN --jq '"\(.name) \(.head_branch) \(.head_sha[0:10]) \(.created_at) attempts=\(.run_attempt)"'
git show 8dacec5c10:nix/canton-sources.json | grep -m1 version
```

```
126052138512 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (3)
CI post-merge to main or release-line branches main 8dacec5c10 2026-11-03T16:27:11Z attempts=1
  "version": "3.7.1",
```

```
# gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/126052138512/logs > log/11173/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11173/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11173/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | cut -c1-500
```

```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 27 minutes, 28 seconds.
[info] Tests: succeeded 28, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-03T17:01:38.537Z","message":"Circuit breaker high-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:DistributedDomainIntegrationTest/config=b5e8296c/validator=aliceValidator","thread_name":"DistributedDomainIntegrationTest-b5e8296c-env-ec-8257","level":"WARN","stack_trace":"io.grpc.StatusRuntimeException: ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,dfdd588a): The submission is already in-flight\n\tat io.grpc.St
Total: 1 lines with problems.
```

## 2. Suite timeline

Artifact `logs-wall-clock-time-3` (13253070169). The WARN falls into 'DistributedDomainIntegrationTest/SV onboarding
on distributed domain' (16:59:19.479 to 17:02:21.553).

```
# gh run download 41674340437 --repo canton-network/splice -n logs-wall-clock-time-3 -D log/11173/logs-wall-clock-time-3
D=log/11173/logs-wall-clock-time-3
zcat $D/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-03T(16:5[5-9]|17:0[0-3])' | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished creating environment" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```

```
2026-11-03T16:59:19.479Z Starting test suite 'DistributedDomainIntegrationTest'...",
2026-11-03T16:59:19.479Z Starting 'DistributedDomainIntegrationTest/SV onboarding on distributed domain'...",
2026-11-03T16:59:20.365Z Finished creating environment: Running setup",
2026-11-03T17:02:21.553Z Test succeeded: 'DistributedDomainIntegrationTest/SV onboarding on distributed domain'",
```

## 3. The 20 failures: first submission times out at 5 s, then 19 SUBMISSION_ALREADY_IN_FLIGHT

```
zcat $D/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-03T17:0[01]' | grep -a 'validator=aliceValidator' | grep -a -E 'TopupMemberTraffic|SUBMISSION_ALREADY|DEADLINE|Circuit' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*/ [\1]/' | cut -c1-330
```

(excerpt)

```
2026-11-03T17:01:14.538Z Starting trigger org.lfdecentralizedtrust.splice.validator.automation.TopupMemberTrafficTrigger [...]
2026-11-03T17:01:14.563Z Request (tid:65eaeaa8fff6a3b0134de47662e490b8) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5501: sending request SubmitAndWaitForTransactionRequest(\n  Commands(\n    \"\",\n    alice_validator_user-b5e8296c,\n    org.lfdecentralizedtrust.splice.validator.automation.Top
2026-11-03T17:01:19.563Z Request (tid:65eaeaa8fff6a3b0134de47662e490b8) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5501: failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 4.999845920s. ...
2026-11-03T17:01:20.567Z Request (tid:f19a533e9adaba5531ab24f5f7887a07) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5501: failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,f19a533e): The submission is already in-flight ...
```

The command id is `TopupMemberTrafficTrigger.getOrCreateValidatorTopupState_...` (create ValidatorTopUpState), as in
11240. The participant timed it out at the same moment:

```
2026-11-03T17:01:19.565Z REQUEST_TIME_OUT(3,8a754f3a): Timed out while awaiting for a completion corresponding to a command submission with command-id=org.lfdecentralizedtrust.splice.validator.automation.TopupMemberTrafficTrigger.getOrCreateValidatorTopupState_fe714efad9f6...
```

## 4. The first submission end to end: ordered fast, result ordered 19 s late

Submission id 8a754f3a, message id 4875f042-ad3e-47d2-8416-0639c5b3a447 (aliceParticipant, sent to sv1).

```
zcat $D/canton_before_shutdown.clog.gz | grep -a '4875f042-ad3e-47d2-8416-0639c5b3a447' | sed -E '...' | head -30
zcat $D/canton_before_shutdown.clog.gz | grep -a '2026-11-03T17:01:14.606041Z' | grep -a -E 'participant=aliceParticipant|mediator' | grep -a -iE 'verdict|Phase|result'
zcat $D/canton_before_shutdown.clog.gz | grep -a 'a89a6b21-419f-4f77-89b5-a379c54eba55' | grep -a -E 'output block|validated to|sends request'
zcat $D/canton_before_shutdown.clog.gz | grep -a '2026-11-03T17:01:26.916978Z' | grep -a -E 'Block 7481|validated to'
```

```
2026-11-03T17:01:14.614Z 'PAR::aliceValidator::12204606a4c5...' sends request with id '4875f042-ad3e-47d2-8416-0639c5b3a447' of size 3138 bytes with 3 envelopes to MGR=1, PAR=1. [globalSequencerSv1]
2026-11-03T17:01:14.840Z At block 7413, the submission request 4875f042-ad3e-47d2-8416-0639c5b3a447 at 2026-11-03T17:01:14.606041Z validated to: Deliver [globalSequencerSv1]
2026-11-03T17:01:14.885Z Phase 4: Sending for request=2026-11-03T17:01:14.606041Z with msgId=a89a6b21-419f-4f77-89b5-a379c54eba55 approved=1, rejected=0, abstained=0 [aliceParticipant]
2026-11-03T17:01:15.228Z At block 7423, the submission request a89a6b21-419f-4f77-89b5-a379c54eba55 at 2026-11-03T17:01:14.973835Z validated to: Deliver [globalSequencerSv1]
2026-11-03T17:01:18.170Z Phase 5: Received 1 response(s) for request=2026-11-03T17:01:14.606041Z. [globalMediatorSv2]
2026-11-03T17:01:19.905Z Phase 6: Finalized request=RequestId(2026-11-03T17:01:14.606041Z) with verdict Approve at 2026-11-03T17:02:14.606041Z [globalMediatorSv2]
2026-11-03T17:01:38.321Z At block 7481, the submission request 5f95594d-4373-4a6f-a57d-720ab589edd7 at 2026-11-03T17:01:26.916978Z validated to: Deliver [globalSequencerSv1]
2026-11-03T17:01:38.617Z Got result for Transaction request at RequestId(2026-11-03T17:01:14.606041Z): OpenEnvelope(... [aliceParticipant]
2026-11-03T17:01:38.648Z Phase 7: Storing at offset=2138 SequencedTransactionAccepted(\n  recordTime = 2026-11-03T17:01:14.606041Z, ... [aliceParticipant]
```

Block 7481 carries the mediator group's ConfirmationResultMessage (aggregation rule MediatorGroup reached its
threshold, delivered at 17:01:26.916978Z). Not verified: why the mediators received the response (ordered at
17:01:15.228) only at 17:01:18.170; the 5 s deadline would have been missed even without the output stall
(finalized 17:01:19.905 > deadline 17:01:19.563), but only by 0.3 s, and the 19 resubmissions are caused by the 19 s
output stall.

## 5. BFT epochs: 2 -> 4 at epoch 279, sv4 blacklisted 280-282 and 284-288; epoch 283 stalls on sv4's segment

```
zcat $D/canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | grep -a 'New epoch [0-9]* has started' | sed -E '...' | awk '{k=$3" "$4; if(k!=prev){print; prev=k}}'
```

(sv1 alone from epoch 0, 16:39:10; sv2 joined at epoch 195, 16:53:27; excerpt from 17:00:41)

```
2026-11-03T17:00:41.950Z New epoch 278 leaders=[sv1, sv2] bl=[]
2026-11-03T17:00:46.106Z New epoch 279 leaders=[sv4, sv1, sv2, sv3] bl=[]
2026-11-03T17:00:54.903Z New epoch 280 leaders=[sv2, sv3, sv1] bl=[sv4]
2026-11-03T17:01:01.340Z New epoch 281 ... bl=[sv4]
2026-11-03T17:01:08.746Z New epoch 282 ... bl=[sv4]
2026-11-03T17:01:13.886Z New epoch 283 leaders=[sv4, sv1, sv2, sv3] bl=[]
2026-11-03T17:01:35.982Z New epoch 284 ... bl=[sv4]
... 285-288 bl=[sv4]
2026-11-03T17:02:11.355Z New epoch 289 bl=[]
```

Epoch 283 segments: sv4 leads blocks 7400, 7404, ..., 7424, ...; sv1 7401, 7405, ..., 7413, ...; sv3 7403, ..., 7423.
Block output on sv1:

```
zcat $D/canton_before_shutdown.clog.gz | grep -a -E '"@timestamp":"2026-11-03T17:01:(1[5-9]|2[0-9]|3[0-9]|4[0-2])' | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'Processing block [0-9]+, data chunk 0|View change completed'
```

```
2026-11-03T17:01:15.228Z Processing block 7423
2026-11-03T17:01:21.778Z View change completed; metadata: (epochNumber=283, blockNumber=7400), view: 1", INFO
2026-11-03T17:01:22.959Z View change completed; metadata: (epochNumber=283, blockNumber=7401), view: 1", INFO
2026-11-03T17:01:25.978Z View change completed; metadata: (epochNumber=283, blockNumber=7403), view: 1", INFO
2026-11-03T17:01:25.981Z View change completed; metadata: (epochNumber=283, blockNumber=7402), view: 1", INFO
2026-11-03T17:01:33.679Z View change completed; metadata: (epochNumber=283, blockNumber=7400), view: 2", INFO
2026-11-03T17:01:38.317Z Processing block 7480
```

Next block in order after 7423 is 7424, in sv4's segment. No block was output from 17:01:15.228 to 17:01:38.317 (23 s).
The segment of sv4 needed two view changes (view 2 completed 17:01:33.679). Mempool rejections on sv4 while
blacklisted: `grep -a -c 'currently blacklisted, rejecting'` = 929 (all on globalSequencerSv4, 17:00 to 17:02);
alice sent its submission to sv1, so it was not rejected: it waited for the output stall.

## 6. Placement against splice#8361 / splice#8356

splice#8361 (context-8361.md): blacklisting and view changes happen once per Canton instance, while sv2-sv4 sequencers
are onboarded by the first multi-SV test. Fits: in this shard sv3/sv4 joined only at 17:00:46 in
DistributedDomainIntegrationTest, and epochs 289-300 (17:02:11 to 17:03:04) have no blacklisted node. The stall itself is a view change
on sv4's segment in the one epoch between its two blacklisting periods; splice#8356 (disable blacklisting in tests)
would not remove it and could make sv4 lead more failing segments in that window (not verified).

## 7. Duplicate check

- known-families.md: AI lists 11173 already (9752 lineage); B covers the BFT onboarding stall. 11240 (inbox, same
  symptom, same top-up command, B parent 10165) is the closest sibling.
- 10165 open (umbrella of family B). 9752 closed (completed).

```
for n in 10165 11240 11173 9752; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"\(.number) \(.state) \(.state_reason) \(.title)"'; done
```

```
10165 open null sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 sequencer-add topology transaction cannot be ordered during the 1 -> 3 BFT onboarding step (run 35223275137)
11240 open null GHA Run 42214429624 : job wall-clock-time (7) in scala_test Failed :fire:
11173 open null GHA Run 41674340437 : job wall-clock-time (3) in scala_test Failed :fire:
9752 closed completed Log problems: SUBMISSION_ALREADY_IN_FLIGHT trips the circuit-breaker
```

## Verdict

Duplicate of 10165 (family B), with the 11240 / 10611 / AI symptom. Flake, not caused by the commit under test.
Mitigation for the symptom as in 11240: `ConfigTransforms.setDefaultGrpcDeadlineForBuyExtraTraffic` 5 s -> 30 s (here
the submission completed 24.1 s after it was sent, so 30 s would likely have avoided all 20 failures).
