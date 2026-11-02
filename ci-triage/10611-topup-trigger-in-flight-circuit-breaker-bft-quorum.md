# 10611 - aliceValidator circuit breaker trips on SUBMISSION_ALREADY_IN_FLIGHT after the BFT sequencer rejected the first send (run 40313835584)

DUPLICATE of family B (umbrella 10165), new symptom. Post-merge main, all tests passed, checkErrors flagged two
`SpliceCircuitBreaker` WARNs from aliceValidator in CopyVotesIntegrationTest. During environment start the ordering
topology went from 1 to 4 sequencers (epoch 21, 11:30:09.636) while sv1's sequencer had only itself authenticated.
aliceParticipant's first `TopupMemberTrafficTrigger` submission (create ValidatorTopUpState) was rejected by sv1's
sequencer with `SEQUENCER_UNAVAILABLE: P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`;
the participant does not retry that send, so the command stayed in flight until max sequencing time (rejected
unsequenced at 11:31:09.955). The trigger resubmitted the same command id every second, each answered
`SUBMISSION_ALREADY_IN_FLIGHT`, and after 20 of those the circuit breaker WARNed.

- Run: https://github.com/canton-network/splice/actions/runs/40313835584, main bb5b8554a9 (#7818), job 121541858995
  `wall-clock-time (8)`.
- Runtime canton: 3.7.0-snapshot.20261023.20808.0.vf4863aea.
- Component: Canton BFT orderer (newcomers active before P2P authentication) and participant send path (no retry
  on SEQUENCER_UNAVAILABLE); splice circuit breaker counts SUBMISSION_ALREADY_IN_FLIGHT as a failure.

## 1. Flagged lines

```
gh api repos/canton-network/splice/actions/jobs/121541858995/logs > log/10611/job.log
s=$(grep -a -n -m1 'Found problems in' log/10611/job.log | cut -d: -f1); e=$(grep -a -n -m1 'contains problems' log/10611/job.log | cut -d: -f1)
sed -n "${s},${e}p" log/10611/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' > log/10611/flagged.txt
grep -a -o -E '"@timestamp":"[^"]*|"level":"[A-Z]+"|"logger_name":"[^"]{0,110}' log/10611/flagged.txt | sed -E 's/config=[0-9a-f]+/config=X/' | paste - - -
```
```
"@timestamp":"2026-10-26T11:30:34.734Z	"logger_name":"o.l.s.u.SpliceCircuitBreaker:CopyVotesIntegrationTest/config=X/validator=aliceValidator	"level":"WARN"
"@timestamp":"2026-10-26T11:31:05.227Z	"logger_name":"o.l.s.u.SpliceCircuitBreaker:CopyVotesIntegrationTest/config=X/validator=aliceValidator	"level":"WARN"
```
Both: `Circuit breaker high-priority-connection tripped after 20 failures. Attaching last failure`, stack
`ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,3b7459ec): The submission is already in-flight`.

## 2. The first submission and why it never completed (canton_before_shutdown.clog.gz, aliceParticipant)

```
unzip -p log/10611/a.zip canton_before_shutdown.clog.gz | gunzip -c | grep -a -E 'T11:30:11\.(77|78)' | grep -a "aliceParticipant" | grep -a -E "GrpcConnection|SequencerConnectionPool" | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/[0-9a-f]{60,}//g' | cut -c1-420 | head -8
```
Selected lines:
```
2026-10-26T11:30:11.771Z Request failed for server-SEQ::sv1::-0. Is the server running? Did you configure the server address as 0.0.0.0? Are you using the right TLS settings? (details logged as DEBUG)\n  GrpcServiceUnavailable: UNAVAILABLE/SEQUENCER_UNAVAILABLE(1,2addd264): P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting\n  Request: send-async-versioned/4083acb5-223e-4a8d-9ade-
2026-10-26T11:30:11.771Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=aliceParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=SEQ::sv1::-0] DEBUG
```
Message 4083acb5 is the send for submission 6a7ab2c0 (command
`TopupMemberTrafficTrigger.getOrCreateValidatorTopupState_8e65566497...`, in flight from 11:30:11.741). It resolves
only at max sequencing time:
```
unzip -p log/10611/a.zip canton_before_shutdown.clog.gz | gunzip -c | grep -a -E "6a7ab2c0-f1ec-4fc8-b744-661c5fea7ab9|ChangeIdHash\(6890eab0945c" | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | tail -3
```
```
2026-10-26T11:31:10.496Z Storing at offset=232 UnSequencedCommandRejected(\n  recordTime = 2026-10-26T11:31:09.955492Z,\n  completion = CompletionInfo(\n    actAs = alice-validatorc34d6dc0-1::1220be59953b...,\n    commandId = org.lfdecentralizedtrust.splice.validator.automation.T
2026-10-26T11:31:10.510Z Updated command deduplication data for Hash(6890eab0945cf8f067fba15bc3a0c6e40cec9bb455cb8138ab2ff51fb51e282a) to CommandDeduplicationData(\n  change id = ChangeId(\n    user Id = alice_validator_user-c34d6dc0,\n    command Id = org.lfdecentralizedtrust.sp
2026-10-26T11:32:06.567Z Registering submission InFlightSubmission(\n  change ID hash = ChangeIdHash(6890eab0945c...),\n  submission id = 0ed6c7d6-6029-4612-b7e0-6638e0948414,\n  submission synchronizer = global-domain::1220c20924e2...,\n  message UUID = 2344a2ed-db9...,\n  seque
```

## 3. Family B confirming grep on globalSequencerSv1

```
unzip -p log/10611/a.zip canton_before_shutdown.clog.gz | gunzip -c | grep -a -E 'T11:(29:[0-5][0-9]|30:[0-5][0-9]|31:0)' | grep -a -E "New epoch [0-9]+ has started|below weak quorum|blacklisted nodes|Authenticated P2P nodes count" | grep -a -i "sv1" | cut -c1-200
```
Selected lines:
```
2026-10-26T11:30:02.996Z New epoch 20 has started with leaders = List(SEQ::sv1::)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 2026-10-26T11:30:02.492509Z,\n  size = 1,\n  weakQuorum = 1
2026-10-26T11:30:09.621Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated
2026-10-26T11:30:09.636Z New epoch 21 has started with leaders = List(SEQ::sv2::, SEQ::sv3::, SEQ::sv4::, SEQ::sv1::)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 2026-10-26T11:30:09.560
2026-10-26T11:30:13.663Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3
2026-10-26T11:30:31.578Z New epoch 22 has started with leaders = List(SEQ::sv3::, SEQ::sv4::, SEQ::sv1::)and blacklisted nodes = List(SEQ::sv2::); ordering topology = OrderingTopology(\n  activationTime = 2026-10-26T11:30:31.31888
```

## Verdict

Family B (BFT 1 -> N with unauthenticated newcomers), umbrella 10165; the new symptom is a lost first submission
plus a circuit-breaker WARN instead of an acknowledge timeout. Flake. Splice-side options besides the family-B
mitigations: do not count `SUBMISSION_ALREADY_IN_FLIGHT` as a circuit-breaker failure
(`SpliceCircuitBreaker.errorCodesToIgnore`). Not verified: whether that change alone would have avoided the WARN
(the trigger would still log transient failures at its own level).
