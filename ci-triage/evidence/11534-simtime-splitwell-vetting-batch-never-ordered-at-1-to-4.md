# 11534 - simtime (1): splitwellValidator VettedPackages broadcast batched 2 ms before the 1 -> 4 switch and never ordered, splitwell_provider never created, providerSplitwellBackend exits the JVM (run 44043765534)

DUPLICATE of 11255 (family B, batch-never-ordered variant; same shape as 11326, same end state as 11408 and 11413).
Main 54f83a5ddd, job simtime (1), Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. In the setup of
TrafficBasedRewardsSvAppTimeBasedIntegrationTest (first suite of the shard) the splitwellValidator participant
broadcasts its VettedPackages (serial 3, splitwell-0.1.26) on the global synchronizer at 08:19:14.032. sv1's
sequencer accepts the send at 08:19:14.035 and puts it into a local batch at 08:19:14.066 (`LocalBatchCreated`,
BatchId ..5c5a). The ordering topology grows from 1 to 4 nodes at 08:19:14.068 (epoch 11, `blacklisted nodes =
List()`), 8 ms after sv1 logged `Authenticated P2P nodes count ... 1 is currently below weak quorum size 2`. The
batch is disseminated to sv2, sv3, sv4 and never appears again: no `Deliver` for message id 59f6ad2d in the whole
log. No rejection reaches the participant, so the broadcast counts as pending until max sequencing time 00:00:20 on a
sim clock at about 00:00:00.160; the outbox flush never closes (`Another flush cycle is currently ongoing` at
08:21:10 and 08:23:19) and the validator retries `check established dars ... are vetted on global-domain`
(`Condition is not yet observed`) until 08:23:51. It never creates `splitwell_provider`; providerSplitwellBackend
gives up on that user at 08:23:55.982 and exits the JVM (`Wait for user failed`, PERMISSION_DENIED =
`UserNotFound(splitwell_provider-991929d)`). No ScalaTest report (family H end state). Not the rejected-send shape:
zero `P2P connectivity is not ready`; not the lost-verdict shape: zero `Failed to send result`, zero
`currently blacklisted`.

- Run: https://github.com/canton-network/splice/actions/runs/44043765534, main 54f83a5ddd ("Remove stale TODOs
  (#8506)"), job 133905191421 `ci / scala_test_sim_time / simtime (1)`. The same run also failed simtime (3)
  (job 133905191530); that job is not part of this ref and was not looked at.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 54f83a5ddd).
- Component: Canton BFT ordering (batch created around an ordering topology change with unauthenticated newcomers)
  under simulated time.

`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.
Artifact `logs-simtime-1` (14311572297) in `log/11534/logs-simtime-1/`; commands run there unless they name
`log/11534/job.log`. `T=canton_network_test.clog.gz`, `C=canton-simtime.clog.gz`.

## 1. Job: JVM exit in the first suite, no report

Failed jobs of the run.
```
gh run view 44043765534 -R canton-network/splice --json jobs,headSha,headBranch,event,createdAt --jq '{headSha,headBranch,event,createdAt, jobs:[.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{name,databaseId,conclusion}]}'
```
```
{"createdAt":"2026-11-17T08:05:25Z","event":"push","headBranch":"main","headSha":"54f83a5ddd184548886de23af0487f9f8861fa5b","jobs":[{"conclusion":"failure","databaseId":133905191421,"name":"ci / scala_test_sim_time / simtime (1)"},{"conclusion":"failure","databaseId":133905191530,"name":"ci / scala_test_sim_time / simtime (3)"}]}
```
Classification grep of the job log (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/133905191421/logs > log/11534/job.log`):
no `Tests:` summary, the console stack is the splitwell app's init failure.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]|app initialization: Initialization failed|^\S+ Caused by|Attempt 1 failed' | sed -E 's/^[^Z]*Z //' | cut -c1-230
```
```
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 70ee3b468c48536afcc01defe9da67d7 with tid 70ee3b468c48536afcc01defe9da67d7
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id 182d52b0-e25f-11f1-b5d9-d1685b2de0a6): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```
The participant's reason for that tid.
```
zcat $C | grep -a 70ee3b468c48536afcc01defe9da67d7 | sed -E "$F" | cut -c1-330 | head -1
```
```
2026-11-17T08:23:55.981Z PERMISSION_DENIED(7,70ee3b46): Could not resolve is_deactivated status for user 'splitwell_provider-991929d' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-991929d)'",[c.d.c.a.AuthInterceptor:participant=splitwellParticipant] WARN
```

## 2. Suite timeline: only the first suite started, exit during `Running setup`

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |has been reset|Starting creating environment" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat $T | tail -1 | cut -c1-120
```
```
2026-11-17T08:16:16.712Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-17T08:16:18.793Z Starting creating environment for TrafficBasedRewardsSvAppTimeBasedIntegrationTest, test 'Enable, disable of dryRunVersion/mintingVersion take effect at round closure':",
2026-11-17T08:16:18.794Z Starting creating environment: Running plugins before",
2026-11-17T08:16:18.807Z Starting creating environment: Creating fixture",
2026-11-17T08:16:20.160Z Starting creating environment: Creating test console",
2026-11-17T08:16:20.173Z Starting creating environment: Updating retry policy",
2026-11-17T08:16:20.176Z Starting creating environment: Running plugins after",
2026-11-17T08:16:20.176Z Starting creating environment: Starting all nodes",
2026-11-17T08:16:59.986Z Starting creating environment: Running setup",
{"@timestamp":"2026-11-17T08:23:55.984Z","message":"Request fetch remote time has succeeded for remote clock server.","l
```

## 3. splitwellValidator is stuck vetting splitwell-0.1.26 on the global synchronizer

The validator init reaches `Set up app instance splitwell` and never leaves it; the last step is the vetting on
global-domain started at 08:19:14.012.
```
zcat $T | grep -a 'validator=splitwellValidator' | awk -F'"' '$4>="2026-11-17T08:19:10.668Z"' | grep -a -E 'Set up app instance|upload dar|Success: dars|Ensuring that dars' | sed -E "$F; s/TrafficBasedRewardsSvAppTimeBasedIntegrationTest\/config=991929d\///" | cut -c1-300
```
```
2026-11-17T08:19:10.668Z splitwellValidator app initialization: Set up app instance splitwell started",[o.l.s.v.ValidatorApp:validator=splitwellValidator] DEBUG
2026-11-17T08:19:10.711Z Sending upload dar for splitwell-0.1.26",[o.l.s.e.ParticipantAdminConnection:validator=splitwellValidator] INFO
2026-11-17T08:19:10.711Z Success: dars List() are removed from global-domain::1220ef7199a0...",[o.l.s.e.ParticipantAdminConnection:validator=splitwellValidator] INFO
2026-11-17T08:19:13.411Z Ensuring that dars List(4c380e09c99b2c5cb938f6cce2a92c03f8941ca6f7af7df7c7370b2d421346da) are vetted on splitwellUpgrade::1220bc352f04... from None",[o.l.s.e.ParticipantAdminConnection:validator=splitwellValidator] INFO
2026-11-17T08:19:14.007Z Success: dars List(4c380e09c99b2c5cb938f6cce2a92c03f8941ca6f7af7df7c7370b2d421346da) are vetted on splitwellUpgrade::1220bc352f04... from None",[o.l.s.e.ParticipantAdminConnection:validator=splitwellValidator] INFO
2026-11-17T08:19:14.012Z Ensuring that dars List(4c380e09c99b2c5cb938f6cce2a92c03f8941ca6f7af7df7c7370b2d421346da) are vetted on global-domain::1220ef7199a0... from None",[o.l.s.e.ParticipantAdminConnection:validator=splitwellValidator] INFO
```
Retries of the check (count of `Now retrying`, then first and last retryable failure; the first one is the
splitwellUpgrade vetting that succeeded, the last one the global one).
```
zcat $T | grep -a 'validator=splitwellValidator' | grep -a -E "check established dars List\(4c380e09" | grep -a -c 'Now retrying'
zcat $T | grep -a 'validator=splitwellValidator' | grep -a "check established dars List(4c380e09" | grep -a 'failed with a retryable error' | sed -n '1p;$p' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-330
```
```
84
2026-11-17T08:19:13.431Z The operation 'check established dars List(4c380e09c99b2c5cb938f6cce2a92c03f8941ca6f7af7df7c7370b2d421346da) are vetted on splitwellUpgrade::1220bc352f04... from None' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: VettedPackages(\n  participantId = PAR::splitwel
2026-11-17T08:23:51.964Z The operation 'check established dars List(4c380e09c99b2c5cb938f6cce2a92c03f8941ca6f7af7df7c7370b2d421346da) are vetted on global-domain::1220ef7199a0... from None' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: VettedPackages(\n  participantId = PAR::splitwellVali
```

## 4. The participant's outbox pushes the broadcast once at 08:19:14.032 and the flush never closes

```
zcat $C | grep -a 'participant=splitwellParticipant/psid=global-domain' | awk -F'"' '$4>="2026-11-17T08:19:14.031Z"' | grep -a -E 'QueueBasedSynchronizerOutbox|SynchronizerOutboxQueue|SequencerBasedRegisterTopologyTransactionHandle' | sed -E "$F" | cut -c1-260
```
```
2026-11-17T08:19:14.031Z enqueuing: List(TxHash(SHA-256:76a369db4cb3...))",[c.d.c.t.SynchronizerOutboxQueue:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:19:14.031Z Invoked flush with queue size 1",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:19:14.031Z dequeuing: List(Traced(TxHash(SHA-256:76a369db4cb3...), tid:404cc848dbfeb94212255cdb83dff8a3))",[c.d.c.t.SynchronizerOutboxQueue:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:19:14.032Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:76a369db4cb3...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:19:14.032Z Broadcasting topology transaction: List(TxHash(SHA-256:76a369db4cb3...))",[c.d.c.c.s.SequencerBasedRegisterTopologyTransactionHandle:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:21:10.920Z enqueuing: List(TxHash(SHA-256:76a369db4cb3...))",[c.d.c.t.SynchronizerOutboxQueue:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:21:10.921Z Invoked flush with queue size 2",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:21:10.921Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:23:19.407Z enqueuing: List(TxHash(SHA-256:76a369db4cb3...))",[c.d.c.t.SynchronizerOutboxQueue:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:23:19.407Z Invoked flush with queue size 3",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:23:19.407Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
```

## 5. Confirming grep (family B, batch variant): batch created 2 ms before the size-4 epoch, never delivered

Every line of the whole canton log that carries the message id: accepted by sv1's sequencer (max sequencing time
00:00:20 sim), batched at 08:19:14.066, nothing after that. Then the count of `Deliver` among them.
```
zcat $C | grep -a '59f6ad2d-2860-4468-87ec-9cadb27dfe2f' > ../msg-59f6ad2d.jsonl; wc -l < ../msg-59f6ad2d.jsonl
sed -E "$F" ../msg-59f6ad2d.jsonl | cut -c1-330 | sed -n '3p;5,6p;9p;12p'
grep -a -c 'Deliver' ../msg-59f6ad2d.jsonl
```
```
12
2026-11-17T08:19:14.034Z Sending message ID 59f6ad2d-2860-4468-87ec-9cadb27dfe2f to sequencer SEQ::sv1::122020933444...",[c.d.c.s.c.RichSequencerClientImpl:participant=splitwellParticipant/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:19:14.035Z 'PAR::splitwellValidator::12200bd33b12...' sends request with id '59f6ad2d-2860-4468-87ec-9cadb27dfe2f' of size 4434 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-17T08:19:14.036Z Request to send submission with id 59f6ad2d-2860-4468-87ec-9cadb27dfe2f with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::12200bd33b12... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220ef7199a0::37-0] DEBUG
2026-11-17T08:19:14.038Z Request send-async-versioned/59f6ad2d-2860-4468-87ec-9cadb27dfe2f has succeeded for server-DefaultSequencer-0.",[c.d.c.s.c.p.GrpcConnection:participant=splitwellParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=DefaultSequencer-0] DEBUG
2026-11-17T08:19:14.066Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..5c5a) with reference epoch 11 for batch received from local mempool containing 1 requests (tag=send, messageId=59f6ad2d-2860-4468-87ec-9cadb27dfe2f, payloadSize=4431, orderingStartInstant=Some(2026-11-17T08:19:14.037865660Z)); storing it locally",[c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=
0
```
The ordering topology switch on globalSequencerSv1 (epochs 8-13) and the P2P authentication state 8 ms before it.
```
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a 'New epoch' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | grep -a -oE '^[^ ]+ New epoch [0-9]+ .*blacklisted nodes = List\([^)]*\).*size = [0-9]+' | sed -E 's/leaders = .*and (blacklisted nodes = List\([^)]*\)).*(size = [0-9]+)/\1 \2/' | sed -n '9,14p'
zcat $C | awk -F'"' '$4>="2026-11-17T08:19:14.030Z" && $4<"2026-11-17T08:19:16.5"' | grep -a 'sequencer=globalSequencerSv1' | grep -a 'below weak quorum' | sed -E "$F" | cut -c1-230 | head -1
```
```
2026-11-17T08:18:49.402Z New epoch 8 has started with blacklisted nodes = List() size = 1
2026-11-17T08:18:59.745Z New epoch 9 has started with blacklisted nodes = List() size = 1
2026-11-17T08:19:07.288Z New epoch 10 has started with blacklisted nodes = List() size = 1
2026-11-17T08:19:14.068Z New epoch 11 has started with blacklisted nodes = List() size = 4
2026-11-17T08:19:26.683Z New epoch 12 has started with blacklisted nodes = List() size = 4
2026-11-17T08:19:33.816Z New epoch 13 has started with blacklisted nodes = List() size = 4
2026-11-17T08:19:14.060Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-domain::12
```
The batch after its creation: stored, signed, disseminated to the three newcomers, then no further line.
```
zcat $C | grep -a -E 'BatchId\(\.\.5c5a\)' | sed -E "$F" | cut -c1-200
```
```
2026-11-17T08:19:14.066Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..5c5a) with reference epoch 11 for batch received from local mempool containing 1 requests (tag=send, messageId=59f6ad2d-2860-4468-
2026-11-17T08:19:14.068Z Availability.LocalDissemination.LocalBatchesStored: persisted local batches List(Traced(BatchId(..5c5a))(TraceContext(trace id=0d5254404a4eb6e01b42237c997c426d, W3C context=W3CTraceContext(parent=00-0d5254404
2026-11-17T08:19:14.068Z Availability.LocalDissemination.LocalBatchesStoredSigned: local batches List(Traced(BatchId(..5c5a))(TraceContext(trace id=0d5254404a4eb6e01b42237c997c426d, W3C context=W3CTraceContext(parent=00-0d5254404a4eb
2026-11-17T08:19:14.068Z Availability.LocalDissemination.LocalBatchesStoredSigned: progress of stored and signed BatchId(..5c5a) is InProgress(\n  membership = Membership(\n    myId = \"SEQ::sv1::..ea10\",\n    orderingTopology = Orde
2026-11-17T08:19:14.069Z Availability.LocalDissemination.LocalBatchesStoredSigned: disseminating BatchId(..5c5a) to SEQ::sv2::..c6b7, SEQ::sv4::..0e3f, SEQ::sv3::..cb53",[c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequenc
```
Ruling out the other 11255 shapes (rejected send, blacklisting, lost verdict); the last count shows the log does
contain delivered sends in general.
```
for p in 'P2P connectivity is not ready' 'currently blacklisted' 'Failed to send result' 'validated to: Deliver'; do printf '%s: ' "$p"; zcat $C | grep -a -c "$p"; done
```
```
P2P connectivity is not ready: 0
currently blacklisted: 0
Failed to send result: 0
validated to: Deliver: 906
```

## 6. Parent and fix state

```
gh issue view 11255 -R DACH-NY/cn-test-failures --json title,state,stateReason,assignees --jq '{title,state,stateReason,a:[.assignees[].login]}'
gh pr view 8363 -R canton-network/splice --json state,mergedAt,title --jq '.'
git show 54f83a5ddd:nix/canton-sources.json | grep -m1 version
```
```
{"a":["rautenrieth-da"],"state":"OPEN","stateReason":"","title":"Timeouts due to changing bft ordering topology"}
{"mergedAt":null,"state":"OPEN","title":"Onboard SV sequencers one at a time in tests"}
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## Verdict

Duplicate of 11255 (family B), batch-never-ordered variant as 11326: the family's confirming grep for that variant
(`LocalBatchCreated` for the stuck submission within 0.5 s of the size-4 switch, then no `Deliver` for it) holds,
with the batch created 2 ms before the switch. 11255 is open and assigned, not itself a duplicate. Flake, cause in
Canton BFT ordering (a batch created at the 1 -> 4 ordering topology step with unauthenticated newcomers is never
ordered); test-side mitigation is the open splice#8363 (one SV sequencer onboarding at a time). No fix branch, nothing
changed outside this packet.

Not verified: why the disseminated batch is dropped inside the orderer (no Canton source or jar consulted, only the
runtime log); whether splice#8363 would have prevented this hit; the simtime (3) failure of the same run.
