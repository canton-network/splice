# 10225 - globalMediatorSv1 acknowledge-signed DEADLINE_EXCEEDED after the BFT 1 -> 4 step (run 36148619987)

DUPLICATE of family B (umbrella 10165), signature (1). main 759345b108, job 108116692364
`ci / scala_test_wall_clock_time / wall-clock-time (3)`, canton 3.6.0-snapshot.20260916.20284.0.vf27c4824. All 24
tests passed; checkErrors flagged two WARNs in `canton_before_shutdown.clog`: the mediator's periodic
`acknowledge-signed` to globalSequencerSv1 (:5108) timed out after 120 s. The ack was sent at 15:42:17.899, inside a
4 s window after the ordering topology of the global synchronizer stepped from 1 to 4 nodes (activation
15:42:13.904672) while sv1 had only itself authenticated; sv1's mempool rejected it, and the gRPC call was not
answered until the client had cancelled it. The 1 -> 4 step was the environment start of
ValidatorSequencerConnectionIntegrationTest (4-SV initDso). Flake, Canton-side; no fix branch.

- Run: https://github.com/canton-network/splice/actions/runs/36148619987, main 759345b108 ("Remove old ACS snapshot
  endpoints using after: Long pagination token ..."), job 108116692364 `ci / scala_test_wall_clock_time / wall-clock-time (3)`.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824.
- Component: Canton (BFT orderer onboarding step); the splice test only triggers it.

## 0. Run, job, canton pin

```
gh run view 36148619987 --repo canton-network/splice --json headBranch,headSha,displayTitle,createdAt,jobs \
  --jq '{b:.headBranch,sha:.headSha[0:10],t:.displayTitle,c:.createdAt}, (.jobs[]|select(.conclusion=="failure")|{id:.databaseId,name:.name})'
```
```
{"b":"main","c":"2026-09-25T14:36:32Z","sha":"759345b108","t":"Remove old ACS snapshot endpoints using after: Long pagination token ..."}
{"id":108116692364,"name":"ci / scala_test_wall_clock_time / wall-clock-time (3)"}
```
(GitHub truncates the title with a Unicode ellipsis; replaced by `...` here to keep the file ASCII.)
```
git show 759345b108:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.6.0-snapshot.20260916.20284.0.vf27c4824",
```

## 1. Flagged lines

The job log classifies as a checkErrors failure: all tests passed, one log contains problems.
```
gh api repos/canton-network/splice/actions/jobs/108116692364/logs > log/10225/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10225/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id d9f924e0-b8f8-11f1-a71e-95e87d3c2477): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[info] All tests passed.
[info] Run completed in 17 minutes, 9 seconds.
[info] Tests: succeeded 24, failed 0, canceled 0, ignored 0, pending 0
```
The two non-ignored lines (the console masks `{` `}` as `***`):
```
grep -a -B400 'contains problems' log/10225/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' | sed -E 's/^[^Z]*Z //' | cut -c1-900
```
```
***"@timestamp":"2026-09-25T15:44:17.901Z","message":"Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999897474s. Name resolution delay 0.000000000 seconds. [closed=[CANCELLED], committed=[call_credentials_delay=21519ns, remote_addr=localhost/127.0.0.1:5108]]\n  Request: acknowledge-signed/2026-09-25T15:42:12.856515Z","logger_name":"c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/psid=global-domain::12205fe53ed3::36-0/pool=main/connection=DefaultSequencer-0","thread_name":"canton-env-ec-84","level":"WARN","trace-id":"71db6750f01d8812656995a6b58bcd68","span-name":"schedule_next_periodic_ack","span-id":"162dfb6948f29d8f"***
***"@timestamp":"2026-09-25T15:44:17.901Z","message":"Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999897474s. Name resolution delay 0.000000000 seconds. [closed=[CANCELLED], committed=[call_credentials_delay=21519ns, remote_addr=localhost/127.0.0.1:5108]]\n  Request: acknowledge-signed/2026-09-25T15:42:12.856515Z))","logger_name":"c.d.c.s.c.PeriodicAcknowledgements:mediator=globalMediatorSv1/psid=global-domain::12205fe53ed3::36-0","thread_name":"canton-env-ec-84","level":"WARN","trace-id":"71db6750f01d8812656995a6b58bcd68","span-name":"schedule_next_periodic_ack","span-id":"162dfb6948f29d8f"***
```
Suites in the shard:
```
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/10225/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
Ans4SvsIntegrationTest
ExternalPartySetupProposalIntegrationTest
PeriodicTopologySnapshotIntegrationTest
RewardExpiryIntegrationTest
SvIdentitiesDumpIntegrationTest
SvOnboardingVettingIntegrationTest
UnclaimedSvRewardsScriptIntegrationTest
ValidatorSequencerConnectionIntegrationTest
WalletBuyTrafficRequestIntegrationTest
```

## 2. Which test was running

Artifact `logs-wall-clock-time-3` (206647987 bytes), downloaded gzipped:
```
export TMPDIR=$PWD/log/10225/ghtmp
gh run download 36148619987 --repo canton-network/splice -n logs-wall-clock-time-3 -D log/10225/logs-wall-clock-time-3
cd log/10225/logs-wall-clock-time-3; T=canton_network_test.clog.gz; C=canton_before_shutdown.clog.gz
```
The ack (sent 15:42:17.899, for clean timestamp 15:42:12.856) falls in ValidatorSequencerConnectionIntegrationTest
(15:41:15.641 to 15:43:48.785), whose environment start is a 4-SV initDso:
```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a -E 'T15:(3[5-9]|4[0-6])' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-09-25T15:41:15.597Z Test succeeded: 'ExternalPartySetupProposalIntegrationTest/TransferPreapprovals get expired by SV automation'",
2026-09-25T15:41:15.641Z Starting test suite 'ValidatorSequencerConnectionIntegrationTest'...",
2026-09-25T15:41:15.642Z Starting 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL chan
2026-09-25T15:43:48.785Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks U
2026-09-25T15:43:48.792Z Starting test suite 'Ans4SvsIntegrationTest'...",
2026-09-25T15:43:48.795Z Starting 'Ans4SvsIntegrationTest/ans should terminated subscriptions are archived'...",
2026-09-25T15:45:17.366Z Test succeeded: 'Ans4SvsIntegrationTest/ans should terminated subscriptions are archived'",
```
(excerpt of the command's output: the lines from 15:41:15 on; the earlier lines are ExternalPartySetupProposalIntegrationTest tests.)

## 3. The ordering topology stepped 1 -> 4 with only sv1 authenticated (family B confirming check)

On globalSequencerSv1: epoch 64 activates a 4-node ordering topology at 15:42:13.904672, 4 ms after sv1 reports 1
authenticated node against a weak quorum of 2; strong quorum returns at 15:42:18.646; sv1 is then blacklisted for
epochs 65 to 67 (15:42:37 to 15:43:17).
```
zcat $C | grep -a -F 'sequencer=globalSequencerSv1' | grep -a -E '"@timestamp":"2026-09-25T15:4[23]' | grep -a -E 'New epoch (6[3-8]) has started|Authenticated P2P nodes count' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/ordering topology = OrderingTopology\(\\n  activationTime = ([^,]*),\\n  size = ([0-9]+).*/ activation=\1 size=\2/; s/",?"logger_name".*//; s/::1220[0-9a-f]{56}([0-9a-f]{4})/::..\1/g' | cut -c1-240
```
```
2026-09-25T15:42:06.713Z New epoch 63 has started with leaders = List(SEQ::sv1::..b0c988a6)and blacklisted nodes = List();  activation=2026-09-25T15:34:47.574417Z size=1
2026-09-25T15:42:14.191Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated
2026-09-25T15:42:14.195Z New epoch 64 has started with leaders = List(SEQ::sv1::..b0c988a6, SEQ::sv2::..9e5579fe, SEQ::sv3::..05d95911, SEQ::sv4::..0dfc4ff0)and blacklisted nodes = List();  activation=2026-09-25T15:42:13.904672Z size=4
2026-09-25T15:42:18.138Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated
2026-09-25T15:42:18.154Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated
2026-09-25T15:42:18.539Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated
2026-09-25T15:42:18.646Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3
2026-09-25T15:42:37.267Z New epoch 65 has started with leaders = List(SEQ::sv4::..0dfc4ff0, SEQ::sv2::..9e5579fe, SEQ::sv3::..05d95911)and blacklisted nodes = List(SEQ::sv1::..b0c988a6);  activation=2026-09-25T15:42:13.904672Z size=4
2026-09-25T15:42:50.559Z New epoch 66 has started with leaders = List(SEQ::sv2::..9e5579fe, SEQ::sv3::..05d95911, SEQ::sv4::..0dfc4ff0)and blacklisted nodes = List(SEQ::sv1::..b0c988a6);  activation=2026-09-25T15:42:13.904672Z size=4
2026-09-25T15:43:04.172Z New epoch 67 has started with leaders = List(SEQ::sv3::..05d95911, SEQ::sv4::..0dfc4ff0, SEQ::sv2::..9e5579fe)and blacklisted nodes = List(SEQ::sv1::..b0c988a6);  activation=2026-09-25T15:42:13.904672Z size=4
2026-09-25T15:43:17.719Z New epoch 68 has started with leaders = List(SEQ::sv1::..b0c988a6, SEQ::sv2::..9e5579fe, SEQ::sv3::..05d95911, SEQ::sv4::..0dfc4ff0)and blacklisted nodes = List();  activation=2026-09-25T15:42:13.904672Z size=4
```
sv1's mempool rejected submissions for about 4 s, from every SV participant as well as the mediator:
```
zcat $C | grep -a -F 'sequencer=globalSequencerSv1' | grep -a -F 'P2P connectivity is not ready' | sed -E 's/\{"@timestamp":"([^"]+)".*/\1/' | awk 'NR==1{f=$0} {l=$0; n++} END{print "first", f; print "last ", l; print "count", n}'
```
```
first 2026-09-25T15:42:14.206Z
last  2026-09-25T15:42:18.110Z
count 65
```
Each rejection is logged by three globalSequencerSv1 loggers (ApiRequestLogger, MempoolModule, GrpcSequencerService),
so the 65 lines are 23 rejected submissions; counting MempoolModule only:
```
zcat $C | grep -a -F 'sequencer=globalSequencerSv1' | grep -a -F 'P2P connectivity is not ready' | grep -a -F 'MempoolModule' | sed -E 's/\{"@timestamp":"([^"]+)".*/\1/' | awk 'NR==1{f=$0} {l=$0; n++} END{print "first", f; print "last ", l; print "count", n}'
```
```
first 2026-09-25T15:42:14.206Z
last  2026-09-25T15:42:18.109Z
count 23
```

## 4. The ack: rejected by the mempool, answered only after the client cancelled

Following the WARN's trace id end to end: the mediator sends the ack to DefaultSequencer-0 (sv1) at 15:42:17.899;
the BFT orderer's mempool rejects it at 15:42:17.900 (`authenticated = 1 < dissemination quorum = 2`), yet the
request stays open until the 120 s client deadline cancels it at 15:44:17.898; the client gives up without retry
(`Retry has not been configured for GrpcClientGaveUp`), and the sequencer sends its response 114 ms after that.
```
zcat $C | grep -a 71db6750f01d8812656995a6b58bcd68 | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/(psid=[^/\]]*)//' | cut -c1-260
```
```
2026-09-25T15:42:17.898Z Acknowledging clean timestamp: 2026-09-25T15:42:12.856515Z",[c.d.c.s.c.PeriodicAcknowledgements:mediator=globalMediatorSv1/lobal-domain::12205fe53ed3::36-0] DEBUG
2026-09-25T15:42:17.899Z [acknowledge] requesting 1 connection(s) excluding Set() allowing only Set(sv1, sv2, sv4, sv3)",[c.d.c.s.c.p.SequencerConnectionPoolImpl:mediator=globalMediatorSv1/lobal-domain::12205fe53ed3::36-0/pool=main] DEBUG
2026-09-25T15:42:17.899Z [acknowledge] returning Set(sequencer-connection-DefaultSequencer-0)",[c.d.c.s.c.p.SequencerConnectionPoolImpl:mediator=globalMediatorSv1/lobal-domain::12205fe53ed3::36-0/pool=main] DEBUG
2026-09-25T15:42:17.899Z Sending request acknowledge-signed/2026-09-25T15:42:12.856515Z to server-DefaultSequencer-0.",[c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/lobal-domain::12205fe53ed3::36-0/pool=main/connection=DefaultSequencer-0] DEBUG
2026-09-25T15:42:17.900Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:57858: received a message AcknowledgeSignedRequest(ByteString)",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
2026-09-25T15:42:17.900Z Request for member MED::sv1::122098eb5fbf... to acknowledge timestamp 2026-09-25T15:42:12.856515Z",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/lobal-domain::12205fe53ed3::36-0] DEBUG
2026-09-25T15:42:17.900Z member MED::sv1::122098eb5fbf... acknowledging timestamp 2026-09-25T15:42:12.856515Z",[c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=globalSequencerSv1/lobal-domain::12205fe53ed3::36-0] DEBUG
2026-09-25T15:42:17.900Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/lobal-domain::12205fe53ed3::36-0] INFO
2026-09-25T15:44:17.898Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:57858: cancelled",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] INFO
2026-09-25T15:44:17.901Z Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999897474s. Name resolution delay 0.000000000 seconds. [closed=[CANCELLED], committed=[call_credentials_delay=
2026-09-25T15:44:17.901Z Retry has not been configured for GrpcClientGaveUp, giving up.",[c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/lobal-domain::12205fe53ed3::36-0/pool=main/connection=DefaultSequencer-0] DEBUG
2026-09-25T15:44:17.901Z Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.9998
2026-09-25T15:44:18.012Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:57858: sending response AcknowledgeSignedResponse()",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
2026-09-25T15:44:18.012Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:57858: succeeded(OK)",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
```
(the psid-stripping sed in this command also eats the `psid=g` prefix, hence `lobal-domain`; cosmetic.)

## Verdict

- Duplicate of family B (umbrella 10165), signature (1), confirmed by the check in section 3: 1 -> 4 step with
  authenticated 1 < weak quorum 2 at activation, sv1 blacklisted for three epochs.
- New suite for the family's occurrence list: ValidatorSequencerConnectionIntegrationTest (its 4-SV initDso).
- Detail this occurrence adds: the mempool rejected the ack within 1 ms, but the AcknowledgeSigned call was held
  open until the client's 120 s deadline, so a 4 s connectivity gap produced a 120 s ack timeout and the WARN.
  Whether the rejection should complete the call (so `PeriodicAcknowledgements` retries on the next tick) is a
  Canton question for the 10165 report.
- Flake; fix location Canton (BFT onboarding topology activation, ack handling on mempool rejection). No
  test-side fix branch: the family's splice-side mitigations are listed under family B and are not one-screen.
- Not verified: Canton source for the ack path at 3.6.0-snapshot.20260916.20284 (no jar was downloaded); why sv2
  to sv4 took until 15:42:18 to authenticate (their onboarding timeline was not traced).
