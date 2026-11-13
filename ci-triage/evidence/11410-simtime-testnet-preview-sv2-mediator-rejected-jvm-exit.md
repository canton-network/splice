# 11410 - simtime (5): MED::sv2 MediatorSynchronizerState pushes of sv2, sv1, sv3 rejected after the 1 -> 4 switch, sv4's outbox stuck on a batch never ordered; sv2Validator exits the JVM (run 43411023137)

DUPLICATE of 11255 (rejected-send shape). Main 20054fa63d, job simtime (5), Canton 3.7.3. No ScalaTest report: in the
setup of TimeBasedTestNetPreviewIntegrationTest (third suite of the shard, the two before passed) the global ordering
topology goes from 1 to 4 nodes at 11:10:50.320. sv1's sequencer rejects the pushes of the MediatorSynchronizerState
proposal that adds MED::sv2 (serial 4) from sv2 (11:10:50.425), sv1 (11:10:50.726) and sv3 (11:10:53.358) with `P2P
connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. sv4 never pushes it: its previous push
(serial 3, the MED::sv3 add) was batched 82 ms before the switch, never ordered, and its flush cycle stays open. sv2's
SV app waits on `Mediator has been granted unlimited traffic`, never creates sv2_validator_user, and sv2Validator
gives up `Wait for user` at 11:15:37 and exits the JVM (family H). Same suite, same SV and same step as 11409 in the
previous post-merge run and 11399. Not caused by #8471 (party allocator ramp-down in the load tester).

- Run: https://github.com/canton-network/splice/actions/runs/43411023137, main 20054fa63d ("Less aggressivle ramp
  down of the party allocator on failures (#8471)"), job 131837486162 `ci / scala_test_sim_time / simtime (5)`,
  attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at 20054fa63d).
- Component: Canton BFT ordering under simulated time.

Artifact `logs-simtime-5` in `log/11410/logs-simtime-5/`; the commands below run in that directory unless they name
`log/11410/job.log`.

## 1. Job: JVM exit, no report

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11410/job.log | grep -a -E 'still running|Attempt 1|##\[error\]Process' | sed -E 's/^[^Z]*Z //' | tail -3
sed -E 's/\x1b\[[0-9;]*m//g' log/11410/job.log | grep -a -E 'sv2Validator app initialization|PERMISSION_DENIED' | sed -E 's/^([^ ]+T)([0-9:]+)\.[0-9]+Z /\2 /' | cut -c1-230
```
```
[info] *** Test still running after 7 minutes, 48 seconds: suite name: TimeBasedTestNetPreviewIntegrationTest, test name: TestNet initializes correctly. 
Attempt 1 failed with exit code 1, retrying
##[error]Process completed with exit code 1.
11:15:37 sv2Validator app initialization: Initialization failed, so exiting; check the application logs for details
11:15:37 java.lang.RuntimeException: sv2Validator app initialization: Initialize app failed
11:15:37 Caused by: java.lang.RuntimeException: sv2Validator app initialization: Wait for user failed
11:15:37 Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request c840ca37c4aeebc867000d580ace570b with tid c840ca37c4aeebc867000d580ace570b
```

## 2. Suite timeline

```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |has been reset|creating environment: Running setup" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-120 | tail -5
```
```
2026-11-13T11:07:33.303Z Test succeeded: 'WalletMintingDelegationTimeBasedIntegrationTest/MintingDelegationCollectRewa
2026-11-13T11:07:34.329Z decentralized namespace has been reset",
2026-11-13T11:07:34.345Z sequencer domain state threshold has been reset",
2026-11-13T11:07:34.420Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
2026-11-13T11:08:11.156Z Starting creating environment: Running setup",
```

## 3. The 1 -> 4 switch and the rejections

```
zcat canton-simtime.clog.gz | grep -a -E 'T11:(0[89]|1[0-5])' | grep -a -E "New epoch [0-9]+ has started.*sequencer=globalSequencerSv1|Rejecting submission request by " | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220([0-9a-f]{12})[0-9a-f]{56}/1220\1.../g' | awk '/New epoch/{match($0,/size = [0-9]+/); s=substr($0,RSTART,RLENGTH); if(s!=prev){match($0,/blacklisted nodes = [^;]*/); print substr($0,1,45) " ... " s ", " substr($0,RSTART,RLENGTH); prev=s}; next} {print substr($0,1,235)}'
zcat canton-simtime.clog.gz | grep -a -c 'Failed to send result'
zcat canton-simtime.clog.gz | grep -a -c 'currently blacklisted'
```
```
2026-11-13T11:08:09.255Z New epoch 92 has sta ... size = 1, blacklisted nodes = List()
2026-11-13T11:10:50.320Z New epoch 101 has st ... size = 4, blacklisted nodes = List()
2026-11-13T11:10:50.425Z Rejecting submission request by Some(PAR::sv2::1220f41a778a...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)","logger
2026-11-13T11:10:50.726Z Rejecting submission request by Some(PAR::sv1::12202251f262...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)","logger
2026-11-13T11:10:53.358Z Rejecting submission request by Some(PAR::sv3::1220c0887963...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)","logger
2026-11-13T11:11:16.097Z Rejecting submission request by Some(MED::sv3::1220ad08fee0...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:5d8bfc5e032c... is already mar
0
0
```

## 4. The pushes around the switch

Outbox pushes to the global synchronizer (time, participant, tx hash) and the mappings:
```
zcat canton-simtime.clog.gz | grep -a -E 'T11:10:(49|5[0-3])' | grep -a 'QueueBasedSynchronizerOutbox' | grep -a 'Attempting to push' | grep -a "Synchronizer 'global'" | sed -E 's/\{"@timestamp":"([^"]+)","message":"Attempting to push ([0-9]+) topology transactions.*List\((.*)\)\)","logger_name":"[^:]*:participant=(sv[0-9])Participant.*/\1 \4 \3/; s/TxHash\(SHA-256:([0-9a-f]{12})[0-9a-f.]*\)?/\1/g'
for h in 1cd46763043f 69a56ad4d853; do zcat canton-simtime.clog.gz | grep -a "$h" | grep -a -oE 'active = [^)]{0,140}' | awk '!s[$0]++' | head -1 | sed -E 's/1220([0-9a-f]{8})[0-9a-f]{60}/1220\1/g'; done
```
```
2026-11-13T11:10:49.929Z sv4 1cd46763043f
2026-11-13T11:10:50.415Z sv2 69a56ad4d853
2026-11-13T11:10:50.718Z sv1 69a56ad4d853
2026-11-13T11:10:53.350Z sv3 69a56ad4d853
active = Seq(MED::sv1::12206de7d877..., MED::sv4::122043d0a0c4..., MED::sv3::1220ad08fee0...
active = Seq(MED::sv1::12206de7d877..., MED::sv4::122043d0a0c4..., MED::sv3::1220ad08fee0..., MED::sv2::122051af35fc...
```
1cd46763043f is the MediatorSynchronizerState serial 3 (adds MED::sv3), 69a56ad4d853 serial 4 (adds MED::sv2). The
three pushes of 69a56ad4d853 match the three rejections in section 3 (each 8-10 ms after the push).

The outboxes afterwards: no response for 69a56ad4d853, sv4 never pushes it, every later flush finds the cycle open.
```
zcat canton-simtime.clog.gz | grep -a -E 'T11:1[0-5]' | grep -a -E 'QueueBasedSynchronizerOutbox:participant=sv[0-9]Participant/psid=global' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"[^:]*:participant=(sv[0-9])[^"]*".*"level":"([A-Z]+)".*/ | \1 \2/' | awk '$1>="2026-11-13T11:10:50.2"' | head -14
zcat canton-simtime.clog.gz | grep -a -E 'T11:1[0-5]' | grep -a -E 'QueueBasedSynchronizerOutbox:participant=sv[0-9]Participant/psid=global' | grep -a -c 'responded the following.*69a56ad4d853'
```
```
2026-11-13T11:10:50.228Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:1cd46763043f...))) | sv1 DEBUG
2026-11-13T11:10:50.244Z Marked flush as done. Current queue size: 0. IsClosing: false | sv1 DEBUG
2026-11-13T11:10:50.413Z Invoked flush with queue size 1 | sv2 DEBUG
2026-11-13T11:10:50.415Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:69a56ad4d853...)) | sv2 DEBUG
2026-11-13T11:10:50.717Z Invoked flush with queue size 1 | sv1 DEBUG
2026-11-13T11:10:50.718Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:69a56ad4d853...)) | sv1 DEBUG
2026-11-13T11:10:52.717Z Invoked flush with queue size 2 | sv4 DEBUG
2026-11-13T11:10:52.717Z Another flush cycle is currently ongoing | sv4 DEBUG
2026-11-13T11:10:53.348Z Invoked flush with queue size 1 | sv3 DEBUG
2026-11-13T11:10:53.350Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:69a56ad4d853...)) | sv3 DEBUG
2026-11-13T11:12:44.642Z Invoked flush with queue size 2 | sv2 DEBUG
2026-11-13T11:12:44.642Z Another flush cycle is currently ongoing | sv2 DEBUG
2026-11-13T11:12:48.433Z Invoked flush with queue size 2 | sv1 DEBUG
2026-11-13T11:12:48.433Z Another flush cycle is currently ongoing | sv1 DEBUG
0
```

## 5. sv4's open flush: its serial-3 push is batched 82 ms before the switch and never ordered

```
zcat canton-simtime.clog.gz | grep -a 59f762a5a96e5f407ff4539364a79dab | grep -a -E 'sends request with id|max sequencing time|Mempool accepting|LocalBatchCreated' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ | \1 \2/; s/1220([0-9a-f]{8})[0-9a-f]{60}/1220\1/g' | cut -c1-330
zcat canton-simtime.clog.gz | grep -a '12209f0b53bf3074' | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] > "2026-11-13T11:10:50.3") print}' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat canton-simtime.clog.gz | grep -a 'c1dfc82b-1c6d-4034-b8f6-7ead02dd25f1' | grep -a -c 'Deliver'
```
```
2026-11-13T11:10:49.933Z 'PAR::sv4::1220e6e10f01...' sends request with id 'c1dfc82b-1c6d-4034-b8f6-7ead02dd25f1' of size 751 bytes with 1 envelopes to ALL=1. | c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1 INFO
2026-11-13T11:10:49.933Z Request to send submission with id c1dfc82b-1c6d-4034-b8f6-7ead02dd25f1 with max sequencing time 1970-01-01T03:07:21Z from PAR::sv4::1220e6e10f01... to Set(All).  | c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::122089597f39::37-0 DEBUG
2026-11-13T11:10:49.937Z Mempool accepting client request with tag 'send' of size 748 | c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::122089597f39::37-0 DEBUG
2026-11-13T11:10:50.238Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(12209f0b53bf3074eb0c146acf84e44107a622e922431bdf5d1859939047714f5fdc) with reference epoch 100 for batch received from local mempool containing 1 requests (tag=send, messageId=c1dfc82b-1c6d-4034-b8f6-7ead02dd25f1, payloadSize=748
2026-11-13T11:10:50.320Z Availability.LocalDissemination.LocalBatchesStoredSigned: local batches List(Traced(BatchId(12209f0b53bf3074eb0c146acf84e44107a622e922431bdf5d1859939047714f5fdc))(TraceContext
2026-11-13T11:10:50.321Z Availability.LocalDissemination.LocalBatchesStoredSigned: disseminating BatchId(12209f0b53bf3074eb0c146acf84e44107a622e922431bdf5d1859939047714f5fdc) to SEQ::sv4::12200a8982ad
0
```
The serial-3 mapping itself became effective through the other SVs (sv1 got `Accepted` for 1cd46763043f at
11:10:50.228, section 4); only sv4's outbox is left waiting on this send.

## 6. sv2 waits on its mediator, sv2_validator_user never created

```
zcat canton_network_test.clog.gz | grep -a -E 'T11:(0[89]|1[0-5])' | grep -a -E "\"message\":\"(Got 'user sv[0-9]_validator_user|Gave up waiting until Mediator|sv2Validator app initialization: (Wait for user failed|Initialization failed))" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*/ | \1/' | cut -c1-200
zcat canton_network_test.clog.gz | grep -a 'SV=sv2' | grep -a -E '"message":"(Waiting until Mediator has been granted|Retrying on same error kind.*No traffic state found)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*/ | \1/; s/1220([0-9a-f]{12})[0-9a-f]{56}/1220\1.../g' | cut -c1-260 | sed -n '1p;$p'
```
```
2026-11-13T11:08:30.144Z Got 'user sv1_validator_user-71959f7a': () | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=71959f7a/validator=sv1Validator
2026-11-13T11:11:32.661Z Got 'user sv4_validator_user-71959f7a': () | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=71959f7a/validator=sv4Validator
2026-11-13T11:11:35.895Z Got 'user sv3_validator_user-71959f7a': () | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=71959f7a/validator=sv3Validator
2026-11-13T11:15:37.366Z sv2Validator app initialization: Wait for user failed | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=71959f7a/validator=sv2Validator
2026-11-13T11:15:37.366Z sv2Validator app initialization: Initialization failed | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=71959f7a/validator=sv2Validator
2026-11-13T11:10:59.900Z Waiting until Mediator has been granted unlimited traffic | o.l.s.s.LocalSynchronizerNode:TimeBasedTestNetPreviewIntegrationTest/config=71959f7a/SV=sv2
2026-11-13T11:15:34.708Z Retrying on same error kind transient error (request infinite retries) for StatusRuntimeException/NOT_FOUND: No traffic state found for member MED::sv2::122051af35fc... | o.l.s.s.LocalSynchronizerNode:TimeBasedTestNetPreviewIntegration
```

## Verdict

Duplicate of 11255, rejected-send shape, simulated time, 1 -> 4 step: three of the four SV signatures on the MED::sv2
MediatorSynchronizerState add are rejected with `P2P connectivity is not ready` 0.1 to 3.0 s after the switch, the
fourth is never sent because sv4's outbox waits on a send batched just before the switch and never ordered. Zero
`Failed to send result`, zero `currently blacklisted`, `blacklisted nodes = List()`. Flake in Canton BFT ordering; no
splice change beyond what 11255 tracks. The JVM exit ended the shard, so the three remaining suites did not run.

Not verified: why sv4's batch gets no dissemination acks after the switch.
