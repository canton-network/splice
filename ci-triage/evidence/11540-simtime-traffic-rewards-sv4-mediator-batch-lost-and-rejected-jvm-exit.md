# 11540 - simtime (0): MED::sv4 add loses two of four SV signatures at the 1 -> 4 switch (one batched and never ordered, one rejected), sv4Validator exits the JVM (run 44113386349)

DUPLICATE of 11255 (family B, rejected-send shape, mixed hit as 11409 / 11410 / 11535). Main 95f6581dc9, job
simtime (0), Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. In the setup of
TrafficBasedRewardsSvAppTimeBasedIntegrationTest (first suite of the shard) the four SV participants push their
signatures on the MediatorSynchronizerState that adds MED::sv4 (TxHash c76771024d76). sv2 (15:36:52.152) and sv1
(15:36:52.233) are sequenced. sv4's send (message id 63acbc58) is accepted by sv1's sequencer at 15:36:52.536 and
put into a local batch at 15:36:52.582 (`LocalBatchCreated`, BatchId ..6db0); the ordering topology grows from 1 to
4 nodes at 15:36:52.612 (epoch 13, `blacklisted nodes = List()`), 30 ms later, and the batch is never ordered (no
`Deliver` for the message id). sv3's send (23449fa5) arrives at 15:36:52.811 and is rejected at 15:36:52.837, 225 ms
after the switch, with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. The proposal
stays at 2 signatures. Both lost sends count as pending until max sequencing time 00:00:20 on a sim clock at about
00:00:00.190, so the sv3 and sv4 outbox flushes never close (`Another flush cycle is currently ongoing`). The sv4 SV
app waits on `Mediator has been granted unlimited traffic` from 15:36:57.013 to the end of the log (76 retries),
never creates `sv4_validator_user`, and sv4Validator gives up on `Wait for user` at 15:41:32.084 and exits the JVM
(PERMISSION_DENIED = `UserNotFound(sv4_validator_user-d5898c11)`). No ScalaTest report (family H end state). Not the
lost-verdict shape: zero `Failed to send result`, zero `currently blacklisted`.

- Run: https://github.com/canton-network/splice/actions/runs/44113386349, main 95f6581dc9 ("Fix repo-wide checks
  from sbt (#8510)"), job 134145250021 `ci / scala_test_sim_time / simtime (0)`; the only failed job of the run.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 95f6581dc9).
- Component: Canton BFT ordering (sends around an ordering topology change with unauthenticated newcomers) under
  simulated time.

`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.
Artifact `logs-simtime-0` (14344480355) in `log/11540/logs-simtime-0/`; commands run there unless they name
`log/11540/job.log`. `T=canton_network_test.clog.gz`, `C=canton-simtime.clog.gz`.

## 1. Job: JVM exit in the first suite, no report

Failed jobs of the run.
```
gh run view 44113386349 -R canton-network/splice --json jobs,headSha,headBranch,event,createdAt | jq -c '{headSha,headBranch,event,createdAt, jobs:[.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{name,conclusion,databaseId}]}'
```
```
{"headSha":"95f6581dc93de2510d327f0fee38e440bb433517","headBranch":"main","event":"push","createdAt":"2026-11-17T15:21:26Z","jobs":[{"name":"ci / scala_test_sim_time / simtime (0)","conclusion":"failure","databaseId":134145250021}]}
```
Classification grep of the job log (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134145250021/logs > log/11540/job.log`):
no `Tests:` summary, no `*** FAILED ***`, the console stack is the sv4Validator init failure.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]|app initialization: Initialization failed|^\S+ Caused by|Attempt 1 failed' | sed -E 's/^[^Z]*Z //' | cut -c1-230
```
```
sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 487f19fe2b9082c7eadf6b5731e7df70 with tid 487f19fe2b9082c7eadf6b5731e7df70
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id 1838d7b0-e29c-11f1-b372-fbc8047d7fbc): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```
The participant's reason for that tid.
```
zcat $C | grep -a 487f19fe2b9082c7eadf6b5731e7df70 | sed -E "$F" | cut -c1-330 | head -1
```
```
2026-11-17T15:41:32.081Z PERMISSION_DENIED(7,487f19fe): Could not resolve is_deactivated status for user 'sv4_validator_user-d5898c11' and identity_provider_id 'Default' due to 'UserNotFound(sv4_validator_user-d5898c11)'",[c.d.c.a.AuthInterceptor:participant=sv4Participant] WARN
```

## 2. Suite timeline: only the first suite started, exit during `Running setup`

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |has been reset|Starting creating environment" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat $T | tail -1 | cut -c1-120
```
```
2026-11-17T15:33:13.888Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-17T15:33:17.294Z Starting creating environment for TrafficBasedRewardsSvAppTimeBasedIntegrationTest, test 'Enable, disable of dryRunVersion/mintingVersion take effect at round closure':",
2026-11-17T15:33:17.295Z Starting creating environment: Running plugins before",
2026-11-17T15:33:17.311Z Starting creating environment: Creating fixture",
2026-11-17T15:33:19.682Z Starting creating environment: Creating test console",
2026-11-17T15:33:19.706Z Starting creating environment: Updating retry policy",
2026-11-17T15:33:19.709Z Starting creating environment: Running plugins after",
2026-11-17T15:33:19.710Z Starting creating environment: Starting all nodes",
2026-11-17T15:34:00.307Z Starting creating environment: Running setup",
{"@timestamp":"2026-11-17T15:41:32.087Z","message":"Request fetch remote time has succeeded for remote clock server.","l
```

## 3. What sv4 waits on: its mediator never gets a traffic state

```
zcat $T | grep -a 'SV=sv4"' | grep -a 'Mediator has been granted unlimited traffic' | sed -E "$F" | cut -c1-200 | sed -n '1p;$p'
```
```
2026-11-17T15:36:57.013Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=d5898c11/SV=sv4] INFO
2026-11-17T15:41:31.987Z The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an exception. Retrying after a number of 76 failures, and after 4979 milliseconds. ",
```

## 4. Confirming grep: ordering topology 1 -> 4, one rejected send, no blacklisting

The global synchronizer's ordering topology goes from size 1 directly to size 4 (first line per size; the first
line is the splitwell synchronizer).
```
zcat $C | grep -a -E 'New epoch [0-9]+ .*size = ' | sed -E "$F" | cut -c1-330 | awk '{ match($0,/size = [0-9]+/); s=substr($0,RSTART,RLENGTH); k=($0~/sv1/); if (s!=p[k]) print; p[k]=s }'
```
```
2026-11-17T15:25:55.348Z New epoch 0 has started with leaders = List(SEQ::splitwellValidator::..8095)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 0001-01-01T00:00:00.000002Z,\n  size = 1,\n  weakQuorum = 1,\n  strongQuorum = 1,\n  nodesTopologyInfo = \"SEQ::splitwellValidator::..8095\
2026-11-17T15:34:34.894Z New epoch 0 has started with leaders = List(SEQ::sv1::..6025)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 0001-01-01T00:00:00.000002Z,\n  size = 1,\n  weakQuorum = 1,\n  strongQuorum = 1,\n  nodesTopologyInfo = \"SEQ::sv1::..6025\" -> NodeTopologyInfo(keyIds =
2026-11-17T15:36:52.612Z New epoch 13 has started with leaders = List(SEQ::sv2::..499e, SEQ::sv3::..d374, SEQ::sv4::..1b8f, SEQ::sv1::..6025)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T00:00:00.190002Z,\n  size = 4,\n  weakQuorum = 2,\n  strongQuorum = 3,\n  nodesTopologyI
```
```
zcat $C | grep -a -E 'Rejecting submission request by' | sed -E "$F" | cut -c1-420
zcat $C | grep -a -c 'P2P connectivity is not ready'; zcat $C | grep -a -c 'currently blacklisted'; zcat $C | grep -a -c 'Failed to send result'
```
```
2026-11-17T15:36:52.837Z Rejecting submission request by Some(PAR::sv3::1220f05a7318...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T15:37:02.701Z Rejecting submission request by Some(MED::sv3::1220b5b69b21...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:ce742bff76b5... is already marked as delivered)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv3] INFO
18
0
0
```
(The second line, on globalSequencerSv3, is an unrelated aggregation duplicate of a mediator send.)

## 5. The rejected and the lost send are signatures on the MED::sv4 add

All four SV participants push TxHash c76771024d76; the outboxes of sv3 and sv4 never finish that flush.
```
zcat $C | grep -a -E 'Attempting to push|Another flush cycle' | grep -a -E 'T15:36:5[0-9]|T15:3[7-9]|T15:4' | grep -a -E 'participant=sv[1-4]Participant' | sed -E "$F" | cut -c1-250 | tail -8
```
```
2026-11-17T15:36:52.152Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:c76771024d76...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220ebd19ef7::37-0
2026-11-17T15:36:52.233Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:c76771024d76...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220ebd19ef7::37-0
2026-11-17T15:36:52.525Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:c76771024d76...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220ebd19ef7::37-0
2026-11-17T15:36:52.805Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:c76771024d76...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220ebd19ef7::37-0
2026-11-17T15:38:48.315Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220ebd19ef7::37-0] DEBUG
2026-11-17T15:38:51.542Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220ebd19ef7::37-0] DEBUG
2026-11-17T15:40:44.345Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220ebd19ef7::37-0] DEBUG
2026-11-17T15:40:52.984Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220ebd19ef7::37-0] DEBUG
```
The transaction is the MediatorSynchronizerState with MED::sv4 in `active`.
```
zcat $C | grep -a 'c76771024d76' | grep -a -o -E 'MediatorSynchronizerState\([^)]{0,300}' | sed -E 's/\\n +/ /g' | sort -u
```
```
MediatorSynchronizerState( synchronizerId = global-domain::1220ebd19ef7..., group = 0, threshold = 2, active = Seq(MED::sv1::1220c57172b4..., MED::sv3::1220b5b69b21..., MED::sv2::1220baf6f913..., MED::sv4::1220a353ed02...
```
The four sends at sv1's sequencer, and the rejection of sv3's.
```
zcat $C | grep -a -E 'sends request with id|Rejecting submission request' | grep -a -E 'T15:36:5[2-5]' | grep -a 'globalSequencerSv1' | grep -a -E 'ALL=1|Rejecting' | sed -E "$F" | cut -c1-260
```
```
2026-11-17T15:36:52.159Z 'PAR::sv2::1220a24be2fd...' sends request with id '275832da-d9eb-4f51-b400-f357ca38c9e8' of size 794 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T15:36:52.241Z 'PAR::sv1::12205c00a058...' sends request with id '5f1924ac-29c5-48ad-837f-334fec675767' of size 787 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T15:36:52.536Z 'PAR::sv4::122069481138...' sends request with id '63acbc58-68d8-4306-8b8f-6008dd0d6664' of size 789 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T15:36:52.811Z 'PAR::sv3::1220f05a7318...' sends request with id '23449fa5-3092-4850-870e-ca71acfe4ca3' of size 1032 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T15:36:52.837Z Rejecting submission request (23449fa5-3092-4850-870e-ca71acfe4ca3) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",
2026-11-17T15:36:52.837Z Rejecting submission request by Some(PAR::sv3::1220f05a7318...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.G
```
sv4's send: max sequencing time 00:00:20 sim time, batched 30 ms before the switch.
```
zcat $C | grep -a '63acbc58-68d8-4306-8b8f-6008dd0d6664' | grep -a -E 'LocalBatchCreated|Deliver|Rejecting|max sequencing' | sed -E "$F" | cut -c1-300
```
```
2026-11-17T15:36:52.537Z Request to send submission with id 63acbc58-68d8-4306-8b8f-6008dd0d6664 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv4::122069481138... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220ebd19ef7::37-0] DEBUG
2026-11-17T15:36:52.582Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..6db0) with reference epoch 12 for batch received from local mempool containing 1 requests (tag=send, messageId=63acbc58-68d8-4306-8b8f-6008dd0d6664, payloadSize=786, orderingStartInstant=Some(2026-
```
`Deliver` count per message id (sv2, sv1, sv4, sv3): two sequenced, two lost.
```
for m in 275832da-d9eb-4f51-b400-f357ca38c9e8 5f1924ac-29c5-48ad-837f-334fec675767 63acbc58-68d8-4306-8b8f-6008dd0d6664 23449fa5-3092-4850-870e-ca71acfe4ca3; do echo "$m $(zcat $C | grep -a $m | grep -a -c 'validated to: Deliver')"; done
```
```
275832da-d9eb-4f51-b400-f357ca38c9e8 4
5f1924ac-29c5-48ad-837f-334fec675767 4
63acbc58-68d8-4306-8b8f-6008dd0d6664 0
23449fa5-3092-4850-870e-ca71acfe4ca3 0
```

## Verdict

Duplicate of 11255 (open, assignee rautenrieth-da, "Timeouts due to changing bft ordering topology"), family B,
rejected-send shape with one batch-never-ordered send (mixed hit as 11409, 11410, 11535). Flake, cause in Canton BFT
ordering at the 1 -> 4 ordering topology step under simulated time; the test-side mitigation is splice#8363
("Onboard SV sequencers one at a time in tests"), still open on 2026-11-17. No fix branch. The JVM exit happened in
the first suite, so no later suite ran and there is no blast radius to check.

Not verified: that 2 of 4 signatures is below the DSO namespace threshold was not read from the log (inferred from
MED::sv4 never getting a traffic state); the fate of batch ..6db0 on the newcomers was not traced beyond the missing
`Deliver`.
