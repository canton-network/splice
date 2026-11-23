# 11559 - simtime (4): MED::sv4 add loses three of four SV signatures at the 1 -> 4 switch (all rejected), sv4Validator exits the JVM (run 44273112018)

DUPLICATE of 11255 (family B, rejected-send shape, as 11400 / 11289). Main e74e9bf844, job simtime (4), Canton
3.7.4-snapshot.20261112.20915.0.v9e248620. In `Running setup` of SvTimeBasedRoundMgmtIntegrationTest, test
'round management' (first suite of the shard), the four SV participants push their signatures on the
MediatorSynchronizerState that adds MED::sv4 (TxHash 8c4e03c56b20). sv2's send (09:19:12.204) is sequenced. The
ordering topology of the global synchronizer grows from 1 to 4 nodes at 09:19:12.310 (epoch 12, `blacklisted nodes =
List()`). The sends of sv3 (09:19:12.413), sv1 (09:19:12.436) and sv4 (09:19:12.819) are rejected by sv1's sequencer
103, 126 and 509 ms after the switch with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum =
2)` and never delivered. The proposal stays at one signature. The rejected sends count as pending until max
sequencing time 00:00:20 on the sim clock, so the outbox flushes of sv1, sv3 and sv4 never close (`Another flush
cycle is currently ongoing`). The sv4 SV app waits on `Mediator has been granted unlimited traffic` from 09:19:17.534
to the end of the log (81 retries), never creates `sv4_validator_user`, and sv4Validator gives up on `Wait for user`
at 09:24:15 and exits the JVM (PERMISSION_DENIED = `UserNotFound(sv4_validator_user-294b471a)`). No ScalaTest report
(family H end state). Not the lost-verdict shape: zero `Failed to send result`, zero `currently blacklisted`.

- Run: https://github.com/canton-network/splice/actions/runs/44273112018, main e74e9bf844 ("Disable base64
  intrisics (#8563)"), job 134694800897 `ci / scala_test_sim_time / simtime (4)`. The run has a second failed job,
  134694800934 `simtime (0)`; it has its own issue and is not part of this ref (not analysed here).
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at e74e9bf844).
- Component: Canton BFT ordering (sends around an ordering topology change with unauthenticated newcomers) under
  simulated time.

`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.
Artifact `logs-simtime-4` (14414082501) in `log/11559/logs-simtime-4/`; commands run there unless they name
`log/11559/job.log`. `T=canton_network_test.clog.gz`, `C=canton-simtime.clog.gz`.

## 1. Job: JVM exit in the first suite, no report

Failed jobs of the run.
```
gh run view 44273112018 -R canton-network/splice --json jobs,headSha,headBranch,event,createdAt --jq '{headSha,headBranch,event,createdAt, failed:[.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{name,databaseId,conclusion}]}'
```
```
{"createdAt":"2026-11-18T09:08:24Z","event":"push","failed":[{"conclusion":"failure","databaseId":134694800897,"name":"ci / scala_test_sim_time / simtime (4)"},{"conclusion":"failure","databaseId":134694800934,"name":"ci / scala_test_sim_time / simtime (0)"}],"headBranch":"main","headSha":"e74e9bf84477b8ee89a92a75332c395847bbef4b"}
```
Classification of the job log (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134694800897/logs > log/11559/job.log`):
no `Tests:` summary, no `*** FAILED ***`, the console stack is the sv4Validator init failure.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]|app initialization: Initialization failed|^\S+ Caused by|Attempt 1 failed' | sed -E 's/^[^Z]*Z //' | cut -c1-230
```
```
sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 93c494b9a4ea1e4dd47905d6310fe692 with tid 93c494b9a4ea1e4dd47905d6310fe692
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id afacf880-e330-11f1-be62-e386010b1dfd): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```
The participant's reason for that tid.
```
zcat $C | grep -a 93c494b9a4ea1e4dd47905d6310fe692 | sed -E "$F" | cut -c1-330 | head -1
```
```
2026-11-18T09:24:15.780Z PERMISSION_DENIED(7,93c494b9): Could not resolve is_deactivated status for user 'sv4_validator_user-294b471a' and identity_provider_id 'Default' due to 'UserNotFound(sv4_validator_user-294b471a)'",[c.d.c.a.AuthInterceptor:participant=sv4Participant] WARN
```

## 2. Suite timeline: only the first suite started, exit during `Running setup`

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |has been reset|Starting creating environment|Starting '|Finished test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-220
zcat $T | tail -1 | cut -c1-120
```
```
2026-11-18T09:16:50.618Z Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...",
2026-11-18T09:16:50.657Z Starting 'SvTimeBasedRoundMgmtIntegrationTest/round management'...",
2026-11-18T09:16:51.254Z Starting creating environment for SvTimeBasedRoundMgmtIntegrationTest, test 'round management':",
2026-11-18T09:16:51.255Z Starting creating environment: Running plugins before",
2026-11-18T09:16:51.271Z Starting creating environment: Creating fixture",
2026-11-18T09:16:53.800Z Starting creating environment: Creating test console",
2026-11-18T09:16:53.828Z Starting creating environment: Updating retry policy",
2026-11-18T09:16:53.835Z Starting creating environment: Running plugins after",
2026-11-18T09:16:53.836Z Starting creating environment: Running setup",
{"@timestamp":"2026-11-18T09:24:15.785Z","message":"Sending request fetch remote time to remote clock server.","logger
```

## 3. What sv4 waits on: its mediator never gets a traffic state

```
zcat $T | grep -a 'SV=sv4"' | grep -a 'Mediator has been granted unlimited traffic' | sed -E "$F" | cut -c1-230 | sed -n '1p;$p'
```
```
2026-11-18T09:19:17.534Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=294b471a/SV=sv4] INFO
2026-11-18T09:24:14.634Z The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an exception. Retrying after a number of 81 failures, and after 4955 milliseconds. ",[o.l.s.s.LocalSynchronizerNode
```

## 4. Confirming grep: ordering topology 1 -> 4, rejected sends, no blacklisting

The global synchronizer's ordering topology goes from size 1 directly to size 4 (first line per size; the first
line is the splitwell synchronizer).
```
zcat $C | grep -a -E 'New epoch [0-9]+ .*size = ' | sed -E "$F" | cut -c1-330 | awk '{ match($0,/size = [0-9]+/); s=substr($0,RSTART,RLENGTH); k=($0~/sv1/); if (s!=p[k]) print; p[k]=s }'
```
```
2026-11-18T09:13:10.208Z New epoch 0 has started with leaders = List(SEQ::splitwellValidator::..d2b1)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 0001-01-01T00:00:00.000002Z,\n  size = 1,\n  weakQuorum = 1,\n  strongQuorum = 1,\n  nodesTopologyInfo = \"SEQ::splitwellValidator::..d2b1\
2026-11-18T09:17:37.615Z New epoch 0 has started with leaders = List(SEQ::sv1::..6107)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 0001-01-01T00:00:00.000002Z,\n  size = 1,\n  weakQuorum = 1,\n  strongQuorum = 1,\n  nodesTopologyInfo = \"SEQ::sv1::..6107\" -> NodeTopologyInfo(keyIds =
2026-11-18T09:19:12.310Z New epoch 12 has started with leaders = List(SEQ::sv1::..6107, SEQ::sv2::..183b, SEQ::sv3::..bee9, SEQ::sv4::..4043)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T00:00:00.170001Z,\n  size = 4,\n  weakQuorum = 2,\n  strongQuorum = 3,\n  nodesTopologyI
```
```
zcat $C | grep -a -E 'Rejecting submission request by' | sed -E "$F" | cut -c1-420
zcat $C | grep -a -c 'P2P connectivity is not ready'; zcat $C | grep -a -c 'currently blacklisted'; zcat $C | grep -a -c 'Failed to send result'
```
```
2026-11-18T09:19:12.413Z Rejecting submission request by Some(PAR::sv3::1220dbe63513...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-18T09:19:12.436Z Rejecting submission request by Some(PAR::sv1::12205d0a17c8...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-18T09:19:12.819Z Rejecting submission request by Some(PAR::sv4::12202a6777f2...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
84
0
0
```

## 5. The rejected sends are signatures on the MED::sv4 add

All four SV participants push TxHash 8c4e03c56b20, each 7-15 ms before its send reaches sv1's sequencer; the
outboxes of sv1, sv3 and sv4 never finish that flush.
```
zcat $C | grep -a -E 'Attempting to push|Another flush cycle' | grep -a -E 'T09:19:1[0-9]|T09:2' | grep -a -E 'participant=sv[1-4]Participant' | sed -E "$F" | cut -c1-250 | grep -a -E 'T09:19:12|T09:2[34]' | tail -7
```
```
2026-11-18T09:19:12.196Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8c4e03c56b20...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220b0e41349::37-0
2026-11-18T09:19:12.391Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8c4e03c56b20...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220b0e41349::37-0
2026-11-18T09:19:12.426Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8c4e03c56b20...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220b0e41349::37-0
2026-11-18T09:19:12.807Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8c4e03c56b20...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220b0e41349::37-0
2026-11-18T09:23:07.810Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220b0e41349::37-0] DEBUG
2026-11-18T09:23:12.979Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220b0e41349::37-0] DEBUG
2026-11-18T09:23:17.878Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220b0e41349::37-0] DEBUG
```
The transaction is the MediatorSynchronizerState with MED::sv4 in `active`.
```
zcat $C | grep -a '8c4e03c56b20' | grep -a -o -E 'MediatorSynchronizerState\([^)]{0,300}' | sed -E 's/\\n +/ /g; s/(1220[0-9a-f]{8})[0-9a-f]{56}/\1.../g' | sort -u
```
```
MediatorSynchronizerState( synchronizerId = global-domain::1220b0e41349..., group = 0, threshold = 2, active = Seq(MED::sv1::12202777648e..., MED::sv3::12209afb48ed..., MED::sv2::12208c7b5828..., MED::sv4::1220581d8e69...
```
The four sends at sv1's sequencer and the three rejections (lines of 09:19:12.2 to 09:19:12.9 with a sender).
```
zcat $C | grep -a -E 'sends request with id|Rejecting submission request by' | grep -a -E 'T09:19:12' | grep -a 'globalSequencerSv1' | grep -a -E 'ALL=1|Rejecting' | sed -E "$F" | cut -c1-200
```
```
2026-11-18T09:19:12.204Z 'PAR::sv2::12203eb70660...' sends request with id '81eb753f-f19b-4f77-bf4c-919712f7209f' of size 788 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-18T09:19:12.399Z 'PAR::sv3::1220dbe63513...' sends request with id '39686a80-2757-4f48-83d8-d1a24239e174' of size 915 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-18T09:19:12.413Z Rejecting submission request by Some(PAR::sv3::1220dbe63513...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated
2026-11-18T09:19:12.433Z 'PAR::sv1::12205d0a17c8...' sends request with id '02dea4d6-9651-4ced-9a85-2cbca28e643a' of size 916 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-18T09:19:12.436Z Rejecting submission request by Some(PAR::sv1::12205d0a17c8...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated
2026-11-18T09:19:12.815Z 'PAR::sv4::12202a6777f2...' sends request with id '4016935d-c36b-48b0-840a-05abd9d8b48e' of size 913 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequen
2026-11-18T09:19:12.819Z Rejecting submission request by Some(PAR::sv4::12202a6777f2...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated
```
`Deliver` count per message id (sv2, sv3, sv1, sv4) and the max sequencing time of a rejected send.
```
for m in 81eb753f-f19b-4f77-bf4c-919712f7209f 39686a80-2757-4f48-83d8-d1a24239e174 02dea4d6-9651-4ced-9a85-2cbca28e643a 4016935d-c36b-48b0-840a-05abd9d8b48e; do echo "$m $(zcat $C | grep -a $m | grep -a -c 'validated to: Deliver')"; done
zcat $C | grep -a '39686a80-2757-4f48-83d8-d1a24239e174' | grep -a -o -E 'max sequencing time [0-9TZ:.-]+' | sort -u
```
```
81eb753f-f19b-4f77-bf4c-919712f7209f 4
39686a80-2757-4f48-83d8-d1a24239e174 0
02dea4d6-9651-4ced-9a85-2cbca28e643a 0
4016935d-c36b-48b0-840a-05abd9d8b48e 0
max sequencing time 1970-01-01T00:00:20Z
```

## Verdict

Duplicate of 11255 (open, assignee rautenrieth-da, "Timeouts due to changing bft ordering topology"; not itself
closed as a duplicate), family B, rejected-send shape (same suite, node and exit path as 11400). Flake, cause in
Canton BFT ordering at the 1 -> 4 ordering topology step under simulated time; the test-side mitigation is
splice#8363 ("Onboard SV sequencers one at a time in tests"), still open on 2026-11-18. No fix branch. The JVM exit
happened in the first suite, so no later suite ran and there is no blast radius to check.

Not verified: that one of four signatures is below the DSO namespace threshold was not read from the log (inferred
from MED::sv4 never getting a traffic state); the other failed job of the run, simtime (0), was not looked at; the
unattributed `Rejecting submission request (...) synchronously` lines after 09:19:12.7 (other senders, 84
`P2P connectivity is not ready` lines in total) were not traced.
