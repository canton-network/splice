# 11241 11242 11248 - sends lost in the BFT 1 -> 4 onboarding epoch: init failures (simtime) and circuit breaker WARN (run 42230239729)

DUPLICATE of 10165 (family B, the 10611 signature for 11241 and 11248; a new hop for 11242). Run 42230239729 on
main c218905f75, the first post-merge run after splice#8234 (all tests on DA BFT). Canton 3.7.2. In each shard the
global synchronizer goes from 1 to 4 BFT sequencers in one epoch when the first 4-SV suite onboards sv2-sv4; sv1 then
has 1 authenticated peer (itself) for 4 to 6 s. A submission made in that window is either rejected
(`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`, 11241, 11248) or accepted into a
batch that is never ordered (11242). The client treats both as "maybe sequenced" and waits for the max sequencing
time. Under wall clock that is about 60 s later (11248: the CopyVotesIntegrationTest top-up stays in flight, the
validator's circuit breaker WARNs, only checkErrors fails). Under sim time the max sequencing time is never reached
because the sim clock does not move during app init, so the wait never ends: splitwellValidator init fails after 20
SUBMISSION_ALREADY_IN_FLIGHT (11241), and in 11242 the topology outboxes of sv3 and sv4 block forever, the sv2 and sv4
mediators are never added, sv2 never finishes onboarding and sv2Validator gives up waiting for its user.
Blacklisting is not involved in any of the three: the rejections are `SEQUENCER_UNAVAILABLE` (P2P), and the lost
batch in 11242 predates sv1's blacklisting by 25 s. The blacklisting branch (splice#8356) would not have prevented
them.

- Run: https://github.com/canton-network/splice/actions/runs/42230239729, main c218905f75 ("[ci] Bump @daml/types
  from 3.6.2 to 3.7.0 ... (#8327)"), jobs 127934590032 `simtime (2)` (11241), 127934590429 `simtime (1)` (11242),
  127934631092 `wall-clock-time (9)` (11248), all attempt 1.
- Runtime canton: 3.7.2 (`git show c218905f75:nix/canton-sources.json`); splice#8234 is an ancestor of c218905f75.
- Component: Canton BFT orderer (newcomer authentication at the epoch boundary, no re-dissemination), splice test
  topology (all SV sequencers onboarded at once).

## 0. Job resolution and Canton pin

```
$ bash .../jobs.sh   # recipe 1 per ref
11241 42230239729 [simtime (2)] -> 127934590032 1
11242 42230239729 [simtime (1)] -> 127934590429 1
11248 42230239729 [wall-clock-time (9)] -> 127934631092 1
$ git show c218905f75:nix/canton-sources.json | head -2; git merge-base --is-ancestor b77153c658 c218905f75 && echo "8234 included"
  "version": "3.7.2",
8234 included
```

## 1. Failing step per job

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/<ref>/job.log | sed -E 's/^[^Z]*Z //' | grep -a -A1 'app initialization: Initialize app failed' | ...
11241: java.lang.RuntimeException: splitwellValidator app initialization: Initialize app failed
       Caused by: io.grpc.StatusRuntimeException: ABORTED: Command submission aborted by circuit breaker due to too many successive failures, next attempt in 30s
       Caused by: io.grpc.StatusRuntimeException: ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,30b2c48a): The submission is already in-flight
11242: java.lang.RuntimeException: sv2Validator app initialization: Initialize app failed
11248: [error] (checkErrors) log/canton_network_test.clog contains problems.
```
11248 flagged lines (recipe 2):
```
"@timestamp":"2026-11-06T14:27:54.424Z","message":"Circuit breaker high-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:CopyVotesIntegrationTest/config=cc7dc18/validator=splitwellValidator",...,"level":"WARN","stack_trace":"io.grpc.StatusRuntimeException: ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,ace4a916): The submission is already in-flight
"@timestamp":"2026-11-06T14:28:25.308Z","message":"Circuit breaker high-priority-connection tripped after 20 failures. ...
```

## 2. Suites running

```
$ zcat log/11241/logs-simtime-2/canton_network_test.clog.gz | jq ... 'Starting test suite|Circuit breaker|Initialize app failed'
2026-11-06T14:17:05.539Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
2026-11-06T14:24:13.985Z WARN validator=splitwellValidator | Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure
2026-11-06T14:24:15.574Z INFO validator=splitwellValidator | splitwellValidator app initialization: Initialize app failed
$ zcat log/11242/logs-simtime-1/canton_network_test.clog.gz | jq ... (WARN/ERROR or Starting test suite)
2026-11-06T14:16:20.928Z Starting test suite 'GovernanceLockTimeBasedIntegrationTest'...
2026-11-06T14:26:19.072Z Starting test suite 'WalletTxLogTimeBasedIntegrationTest'...
2026-11-06T14:31:38.418Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...
2026-11-06T14:39:13.630Z ERROR validator=sv2Validator | Gave up getting 'user sv2_validator_user-82e1cae2' io.grpc.StatusRuntimeException: PERMISSION_DENIED: ...
2026-11-06T14:39:13.630Z ERROR validator=sv2Validator | sv2Validator app initialization: Initialization failed
$ zcat log/11248/.../canton_network_test.clog.gz | jq ... 'Starting test suite|Test (succeeded|failed)' (14:22-14:31)
2026-11-06T14:24:18.408Z Starting test suite 'CopyVotesIntegrationTest'...
2026-11-06T14:29:45.873Z Test succeeded: 'CopyVotesIntegrationTest/CopyVotesTrigger mirrors source votes across accept, reject, update, and no-op cases'
```

## 3. The 1 -> 4 epoch on globalSequencerSv1 in each shard

```
$ zcat <canton log> | grep -a -E 'New epoch|below (weak|strong) quorum|now again above' | jq (globalSequencerSv1; epoch, blacklisted nodes, size)
11241 (canton-simtime.clog.gz):
2026-11-06T14:21:29.861Z 14 List() 1
2026-11-06T14:21:38.555Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated
2026-11-06T14:21:38.560Z 15 List() 4
2026-11-06T14:21:43.952Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3
2026-11-06T14:21:54.962Z 16 List(SEQ::sv4) 4      (17, 18 the same; 19 List())
11242 (canton-simtime.clog.gz):
2026-11-06T14:33:32.474Z 112 List() 1
2026-11-06T14:33:39.207Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are au
2026-11-06T14:33:39.213Z New epoch 113 ... size = 4
2026-11-06T14:33:45.120Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3
2026-11-06T14:33:53.696Z View change completed; metadata: (epochNumber=113, blockNumber=2183), view: 1
2026-11-06T14:34:04.355Z 114 List(SEQ::sv1) 4     (115, 116 the same; 117 List())
11248 (canton_before_shutdown.clog.gz):
2026-11-06T14:27:26.914Z epoch 27 bl=List() size=1
2026-11-06T14:27:30.888Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and
2026-11-06T14:27:30.898Z epoch 28 bl=List() size=4
2026-11-06T14:27:36.372Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3
2026-11-06T14:27:48.337Z epoch 29 bl=List(SEQ::sv2) size=4   (30, 31 the same; 32 List())
```
Each shard has exactly one size step, 1 -> 4, in the first suite that onboards sv2-sv4 (11242: the third suite).

## 4. 11241: createValidatorRight rejected, then in flight forever

```
$ zcat log/11241/logs-simtime-2/canton_network_test.clog.gz | jq ... splitwellValidator SubmitAndWait/createValidatorRight
2026-11-06T14:21:38.559Z DEBUG | Request (tid:1b644bfa3b4b897c59bf425421e39d10) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15701: sending request SubmitAndWaitRequest( ... createValidatorRight_3af5cda0...
2026-11-06T14:23:38.559Z INFO | Request (tid:1b644bfa3b4b897c59bf425421e39d10) ... failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999833974s. ...
2026-11-06T14:23:38.757Z INFO | Request (tid:e6b72f947c4c34e50e4671271a7d0bbd) ... failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,e6b72f94): The submission is already in-flight ...
$ zcat log/11241/logs-simtime-2/canton-simtime.clog.gz | grep -a -E 'P2P connectivity is not ready|blacklisted, rejecting' | jq ...
2026-11-06T14:21:38.742Z DEBUG BlockSequencer:sequencer=globalSequencerSv1/... | Rejecting submission request (f68a3556-b16c-4adb-92ce-b690a3b572fe) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecti
2026-11-06T14:21:38.743Z INFO GrpcSequencerService:sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::splitwellValidator::1220a4b09d9d...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), reject
2026-11-06T14:21:38.744Z INFO ApiRequestLogger:sequencer=globalSequencerSv1 | Request ...SequencerService/SendAsync by grpc:/127.0.0.1:59626: failed with UNAVAILABLE/SEQUENCER_UNAVAILABLE(1,1b644bfa): P2P connectivity is not ready ...
2026-11-06T14:21:38.818Z INFO GrpcSequencerConnection:participant=splitwellParticipant/... | Send [f68a3556-b16c-4adb-92ce-b690a3b572fe] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(...
$ zcat .../canton-simtime.clog.gz | grep -a -c 'blacklisted, rejecting'
0
```
The SendAsync carries the trace id of the createValidatorRight call (1b644bfa). Its max sequencing time and the sim
clock:
```
$ zcat .../canton-simtime.clog.gz | grep -a f68a3556-... | jq 'max sequencing time'
2026-11-06T14:21:38.657Z Request to send submission with id f68a3556-b16c-4adb-92ce-b690a3b572fe with max sequencing time 1970-01-01T00:01:00Z from PAR::splitwellValidator:: ...
$ zcat .../canton_network_test.clog.gz | jq 'Updated domain time to' (14:21:30 - 14:24:20, first and last)
2026-11-06T14:21:30.228Z 1970-01-01T00:00:00.200003Z
2026-11-06T14:24:15.280Z 1970-01-01T00:00:00.220003Z
```
The synchronizer time stays below 00:00:01, so the max sequencing time 00:01:00 is never reached and the command
never leaves in-flight. Breaker and init failure:
```
2026-11-06T14:24:13.985Z WARN validator=splitwellValidator | Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure io.grpc.StatusRuntimeException: ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,475b6627): The submission is already in-flight
2026-11-06T14:24:15.575Z ERROR validator=splitwellValidator | splitwellValidator app initialization: Initialization failed java.lang.RuntimeException: splitwellValidator app initialization: Initialize app failed
```

## 5. 11242: sv2 waits for its mediator traffic, which never comes

sv2 init steps (sv2Validator waits for a user that the sv2 SV app creates after onboarding):
```
$ zcat log/11242/logs-simtime-1/canton_network_test.clog.gz | jq ... 'app initialization:' SV=sv2 / sv2Validator
2026-11-06T14:32:13.561Z DEBUG SV=sv2 | sv2 app initialization: JoiningNodeInitializer joining Dso with key started
2026-11-06T14:32:05.831Z DEBUG validator=sv2Validator | sv2Validator app initialization: Wait for user started
2026-11-06T14:39:13.630Z ERROR validator=sv2Validator | Gave up getting 'user sv2_validator_user-82e1cae2'
$ ... SV=sv2 progress after the promotion
2026-11-06T14:33:44.207Z INFO | Onboarding mediator
2026-11-06T14:33:44.207Z INFO | Waiting until Mediator has been granted unlimited traffic
$ ... 'Mediator has been granted unlimited traffic' (Success | failed with a retryable) per SV
     93 SV=sv2 The operation 'Check whether M
      1 SV=sv3 Success: Mediator has been gra
      5 SV=sv3 The operation 'Check whether M
     93 SV=sv4 The operation 'Check whether M
2026-11-06T14:39:12.558Z (sv2, last) ... NOT_FOUND: No traffic state found for member MED::sv2::122047798e25...
```
MED::sv3 was added and got traffic; MED::sv4 (and MED::sv2) never did. The `MediatorSynchronizerState` adding MED::sv4
(tx 60e841ee4630) needs the DSO signatures: sv1 and sv2 pushes were accepted, sv3 and sv4 pushes never got an answer:
```
$ zcat .../canton-simtime.clog.gz | grep -a 60e841ee4630 | jq (Outbox, TopologyStateProcessor)
2026-11-06T14:33:38.378Z DEBUG QueueBasedSynchronizerOutbox:participant=sv1Participant/... | Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:60e841ee4630...)))
2026-11-06T14:33:38.762Z DEBUG QueueBasedSynchronizerOutbox:participant=sv2Participant/... | Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:60e841ee4630...)))
2026-11-06T14:33:38.912Z DEBUG QueueBasedSynchronizerOutbox:participant=sv4Participant/... | Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:60e841ee4630...))
2026-11-06T14:33:38.918Z DEBUG QueueBasedSynchronizerOutbox:participant=sv3Participant/... | Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:60e841ee4630...))
$ ... sv4Participant Outbox 14:33:38.9 - 14:39:20
2026-11-06T14:33:38.912Z ... Attempting to push 1 topology transactions ...
2026-11-06T14:35:34.173Z ... Invoked flush with queue size 2
2026-11-06T14:35:34.173Z ... Another flush cycle is currently ongoing
2026-11-06T14:37:28.328Z ... Invoked flush with queue size 3
2026-11-06T14:37:28.328Z ... Another flush cycle is currently ongoing
```
The sv3 push (message be103e78) reached sv1's sequencer and went into a batch 2 ms before epoch 113 started:
```
$ zcat .../canton-simtime.clog.gz | grep -a be103e78-ea0e-4ac8-8d02-c9f2686b82ad | jq ...
2026-11-06T14:33:38.924Z DEBUG BlockSequencer:sequencer=globalSequencerSv1/... | Request to send submission with id be103e78-... with max sequencing time 1970-01-01T03:08:54Z from PAR::sv3::... to Set(All).
2026-11-06T14:33:38.925Z DEBUG BftBlockOrderer:sequencer=globalSequencerSv1/... | sending submission with message ID be103e78-... from PAR::sv3::... to Set(All)
2026-11-06T14:33:39.211Z DEBUG AvailabilityModule:sequencer=globalSequencerSv1 | Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..d5a5) with reference epoch 113 for batch received from local mempool containing 2 requests (tag=send, messageId=0c336cd3-..., (tag=send, messageId=be103e78-...
$ B=1220a2cd2f2a4cc882e0bc6364f4cc74b05026a61868680b31e5515dced04aded5a5; zcat .../canton-simtime.clog.gz | grep -a $B | jq ...
2026-11-06T14:33:39.211Z ... LocalBatchCreated: created batch ID BatchId(..d5a5) with reference epoch 113 ...
2026-11-06T14:33:39.213Z ... LocalBatchesStored: persisted local batches List(Traced(BatchId(..d5a5)) ...
2026-11-06T14:33:39.214Z ... LocalBatchesStoredSigned: local batches List(Traced(BatchId(..d5a5)) ...
2026-11-06T14:33:39.214Z ... progress of stored and signed BatchId(..d5a5) is InProgress(   membership = Memb
2026-11-06T14:33:39.215Z ... LocalBatchesStoredSigned: disseminating BatchId(..d5a5) to SEQ::sv3::..bc30, SEQ::sv4::..71bc, SEQ::sv2::..0849
$ zcat .../canton-simtime.clog.gz | grep -a $B | jq -r .logger_name | sort | uniq -c
      5 c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv1/psid=global-domain::122044d7e1c4::37-0
```
The other request in the batch is the sv4 push of the same transaction:
```
$ zcat .../canton-simtime.clog.gz | grep -a 0c336cd3-7a1e-4813-83dc-d5abd2e0e16c | jq 'sends request with id'
2026-11-06T14:33:38.917Z 'PAR::sv4::122011d3058c...' sends request with id '0c336cd3-7a1e-4813-83dc-d5abd2e0e16c' of size 997 bytes with 1 envelo
```
The batch was disseminated once, to sv2, sv3 and sv4, while sv1 had no authenticated peer (14:33:39.207), and is never mentioned
again (no ack, no proposal, no ordering) until the end of the log. Sim clock:
```
$ zcat .../canton_network_test.clog.gz | jq 'Updated domain time to' (>= 14:33:30, first and last)
2026-11-06T14:33:30.080Z validator=sv1Validator Completed processing PeriodicTask(for = 1970-01-01T03:08:34Z) with outcome: Updated domain
2026-11-06T14:39:13.622Z validator=sv1Validator Completed processing PeriodicTask(for = 1970-01-01T03:08:34Z) with outcome: Updated domain
```
The max sequencing time 03:08:54 is never reached, so the outbox flush of sv3 and sv4 never completes and every
later topology push of theirs (`Another flush cycle is currently ongoing`) waits behind it. Rejections in this
shard: one blacklisting rejection at 14:34:10 (sv1 participant), no P2P rejection:
```
$ zcat .../canton-simtime.clog.gz | grep -a -E 'P2P connectivity is not ready|blacklisted, rejecting|possibly sequenced' | grep GrpcSequencerService
2026-11-06T14:34:10.387Z INFO GrpcSequencerService:sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv1::12207b31f875...) with SequencerDeliverError(code = SEQUENCER_OVERLOADED, message = Mempool received client request but this node is currently blacklisted
```

## 6. 11248: CopyVotes top-up rejected, breaker trips while it is in flight

```
$ zcat log/11248/.../canton_network_test.clog.gz | jq ... splitwellValidator
2026-11-06T14:27:29.638Z DEBUG | Request (tid:817a23195d4ba263a00ed338df97666b) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5701: sending request ... (command TopupMemberTrafficTrigger.getOrCreateValidatorTopup)
2026-11-06T14:27:34.639Z INFO | Request (tid:817a23195d4ba263a00ed338df97666b) ... failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 4.999866506s. ...
2026-11-06T14:27:35.690Z INFO | Request (tid:6bbf6d77e4eb3a269204cc9d614c5625) ... failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,6bbf6d77): The submission is already in-flight ...
$ zcat .../canton_before_shutdown.clog.gz | grep -a 817a23195d4ba263a00ed338df97666b | jq ...
2026-11-06T14:27:31.458Z BlockSequencer:sequencer=globalSequencerSv1/... | Request to send submission with id b1e81d5e-1956-4db4-a662-283397517795 with max sequencing time 2026-11-06T14:28:29.652725Z from PAR::splitwellValidator::122035e20aee... ...
2026-11-06T14:27:31.462Z BlockSequencer:sequencer=globalSequencerSv1/... | Rejecting submission request (b1e81d5e-1956-4db4-a662-283397517795) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2
2026-11-06T14:27:31.462Z ApiRequestLogger:sequencer=globalSequencerSv1 | Request ...SequencerService/SendAsync by grpc:/127.0.0.1:55394: failed with UNAVAILABLE/SEQUENCER_UNAVAILABLE(1,817a2319): P2P connectivity is not ready (authenticated = 1 < dissemi
```
Breaker WARNs at 14:27:54.424 and 14:28:25.308 (section 1), both before the max sequencing time 14:28:29.65; the
test then passed. Same mechanism as 10611 (aliceValidator, 2026-10-26) and the same trigger and test setup as 11240.

## 7. Duplicate check

```
$ sed -n '/^## B\./,/^## C\./p' ci-triage/known-families.md   (signature 3: P2P rejection, not retried, in flight until max sequencing time, breaker)
$ awk -F'\t' '$1==10611 || $1==11240 || $1==10165' ci-triage/evidence/INDEX.tsv | cut -f1,8
10165	(umbrella)
10611	10165 (family B)
11240	(dup of 10165)
$ gh api repos/DACH-NY/cn-test-failures/issues/<n> --jq state
10165 open  11240 open (Inbox, dup of 10165 handed out)  10611 closed not_planned
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "P2P connectivity is not ready"'
10279 closed (mediator acknowledge DEADLINE_EXCEEDED, family B signature 1)
10283 closed (wall-clock-time (0), family B)
10147 open (BFT P2P state transition while the DR canton is destroyed; different)
10165 open (umbrella)
10010 closed (P2P auth token fetch WARN; different)
```
Context (splice#8361 comment 2026-11-07): blacklisting and view changes happen once per Canton instance, while the
first multi-SV test onboards sv2-sv4; that is the window of all three failures here.

## Verdict

Duplicate of 10165 (family B). Not blacklisting, not the general BFT slowdown: the cause is the 1 -> 4 ordering
topology step, in which sv1 runs an epoch with no authenticated peer. 11241 and 11248 are the 10611 signature
(synchronous `P2P connectivity is not ready` rejection, client keeps the command in flight until max sequencing time).
11242 is a new hop of the same step: the submission is accepted into a batch that is disseminated once to peers that
are not yet authenticated and never again, so it is never ordered.
Simtime makes both fatal: max sequencing time is a sim-clock instant and the sim clock does not advance during app
init, so the in-flight command (11241) and the topology outbox flush (11242) wait forever. Same outcome as the
simtime (0) failure on PR 8262 (sv2 mediator topology broadcast rejected, never resent).
splice#8356 (no blacklisting) does not address this. Fix options (family B): onboard the SV sequencers one at a time
or gate the ordering-topology change on P2P authentication (Canton), or in splice serialise sv2-sv4 sequencer
onboarding in the test setup; for simtime additionally make sure the sim clock moves past the max sequencing time
after onboarding (e.g. advance time once after the sequencers are onboarded). Not verified: why the batch in 11242
was not re-disseminated after peers authenticated at 14:33:45 (needs the BFT availability module source of 3.7.2).
