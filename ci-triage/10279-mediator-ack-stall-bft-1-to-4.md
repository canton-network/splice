# 10279 - globalMediatorSv1 acknowledge-signed DEADLINE_EXCEEDED after the BFT 1 -> 4 step (run 37315295211)

DUPLICATE of family B (umbrella 10165), signature (1), same shape as 10212 and 10225. main a3132ebe27, job
111781648386 `ci / scala_test_wall_clock_time / wall-clock-time (0)`, canton 3.6.0-snapshot.20261001.20345.0.v85a9270a.
All 31 tests passed; checkErrors flagged one WARN in `canton_before_shutdown.clog`: the mediator's periodic
`acknowledge-signed` for clean timestamp 13:44:56.087186 to globalSequencerSv1 (:5108) timed out after 120 s. That
clean timestamp is 1 us before the activation (13:44:56.087187) of the ordering topology that stepped the global
synchronizer from 1 to 4 BFT nodes while sv1 had only itself authenticated. sv1's mempool rejected the ack within 1 ms
(13:44:58.908) but the AcknowledgeSigned call stayed open until the client cancelled it at the 120 s deadline. The
step was the 4-SV `initDso()` of ValidatorIntegrationTest "validator apps connect to all DSO sequencers": sv2-sv4
called `onboard/sv/sequencer` within 2.3 s, so all three newcomers entered in one topology change. Flake,
Canton-side; no fix branch.

- Run: https://github.com/canton-network/splice/actions/runs/37315295211, main a3132ebe27 ("Set gha env vars when
  generating update expected files (#7633)"), job 111781648386 `ci / scala_test_wall_clock_time / wall-clock-time (0)`.
  Only failed job in the run.
- Runtime canton: 3.6.0-snapshot.20261001.20345.0.v85a9270a.
- Component: Canton BFT orderer (mempool rejection does not complete the AcknowledgeSigned call; ordering topology
  activated before P2P authentication).
- Artifact: `logs-wall-clock-time-0` in `log/10279/logs-wall-clock-time-0/`.

## 1. Classification: all tests passed, one flagged WARN

```
$ gh api repos/canton-network/splice/actions/jobs/111781648386 --jq '"\(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)"'
failure 2026-10-05T13:32:55Z 2026-10-05T14:02:11Z self-hosted-k8s-large-4p78d-runner-m8s98

$ gh api repos/canton-network/splice/actions/jobs/111781648386/logs > log/10279/job.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10279/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems' | sed -E 's/^[^Z]*Z //' | sort -u
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[info] All tests passed.
[info] Tests: succeeded 31, failed 0, canceled 0, ignored 0, pending 0

$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10279/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -a -v 'ignore this line in check-sbt-output' | cut -c1-600
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-10-05T13:46:58.908Z","message":"Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999930336s. Name resolution delay 0.000000000 seconds. [closed=[], open=[[call_credentials_delay=5939ns, remote_addr=localhost/127.0.0.1:5108]]]\n  Request: acknowledge-signed/2026-10-05T13:44:56.087186Z","logger_name":"c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/psid=global-domain::122049ba17c3::36-0/pool=main/connection=DefaultSequencer-0","thread_name":"canton-env-ec-45","level":"WARN",...
Total: 1 lines with problems.
```

The companion `Failed to acknowledge clean timestamp` WARN (13:46:58.932) is ignored by
`project/ignore-patterns/canton_log.ignore.txt:83`; only the GrpcConnection line is flagged.

## 2. Which test was running

```
$ T=log/10279/logs-wall-clock-time-0/canton_network_test.clog.gz
$ zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'T13:4[0-8]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | head -7
2026-10-05T13:41:25.781Z Starting test suite 'ValidatorIntegrationTest'...",
2026-10-05T13:41:25.796Z Starting 'ValidatorIntegrationTest/start and restart cleanly'...",
2026-10-05T13:43:07.545Z Test succeeded: 'ValidatorIntegrationTest/start and restart cleanly'",
2026-10-05T13:43:07.546Z Starting 'ValidatorIntegrationTest/initialize DSO and validator apps'...",
2026-10-05T13:43:45.994Z Test succeeded: 'ValidatorIntegrationTest/initialize DSO and validator apps'",
2026-10-05T13:43:45.995Z Starting 'ValidatorIntegrationTest/validator apps connect to all DSO sequencers'...",
2026-10-05T13:48:09.810Z Test succeeded: 'ValidatorIntegrationTest/validator apps connect to all DSO sequencers'",
```

The test starts with a 4-SV `initDso()` (`ValidatorIntegrationTest.scala:167-168` at a3132ebe27). The three joining
SVs call sv1's onboarding endpoints within about 2 s of each other:

```
$ zcat $T | grep -a 'T13:4[3-5]' | grep -a -E 'onboard/sv/start|onboard/sv/sequencer' | grep -a 'received request' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
2026-10-05T13:44:39.391Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:42250): received request.",[o.l.s.a.a.HttpRequestLogger:ValidatorIntegrationTest/config=12c4561d/SV=sv1] DEBUG
2026-10-05T13:44:39.967Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:42274): received request.",[o.l.s.a.a.HttpRequestLogger:ValidatorIntegrationTest/config=12c4561d/SV=sv1] DEBUG
2026-10-05T13:44:41.152Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:42280): received request.",[o.l.s.a.a.HttpRequestLogger:ValidatorIntegrationTest/config=12c4561d/SV=sv1] DEBUG
2026-10-05T13:44:51.871Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:57624): received request.",[o.l.s.a.a.HttpRequestLogger:ValidatorIntegrationTest/config=12c4561d/SV=sv1] DEBUG
2026-10-05T13:44:52.393Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:57638): received request.",[o.l.s.a.a.HttpRequestLogger:ValidatorIntegrationTest/config=12c4561d/SV=sv1] DEBUG
2026-10-05T13:44:54.191Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:57640): received request.",[o.l.s.a.a.HttpRequestLogger:ValidatorIntegrationTest/config=12c4561d/SV=sv1] DEBUG
```

## 3. Family B confirmation on globalSequencerSv1: 1 -> 4 step, quorum loss, sv1 blacklisted

```
$ C=log/10279/logs-wall-clock-time-0/canton_before_shutdown.clog.gz
$ zcat $C | grep -a 'globalSequencerSv1' | grep -a -E 'New epoch (2[4-9]|30) has started' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\); ordering topology = OrderingTopology\(\\n  activationTime = ([^,]+),\\n  size = ([0-9]+).*/\1 epoch=\2 activation=\5 size=\6 blacklisted=[\4]/'
2026-10-05T13:44:41.527Z epoch=24 activation=2026-10-05T13:42:26.298871Z size=1 blacklisted=[]
2026-10-05T13:44:48.629Z epoch=25 activation=2026-10-05T13:44:48.466797Z size=1 blacklisted=[]
2026-10-05T13:44:56.149Z epoch=26 activation=2026-10-05T13:44:56.087187Z size=4 blacklisted=[]
2026-10-05T13:45:19.744Z epoch=27 activation=2026-10-05T13:45:17.998424Z size=4 blacklisted=[SEQ::sv1::..2837]
2026-10-05T13:45:32.630Z epoch=28 activation=2026-10-05T13:45:17.998424Z size=4 blacklisted=[SEQ::sv1::..2837]
2026-10-05T13:45:47.278Z epoch=29 activation=2026-10-05T13:45:17.998424Z size=4 blacklisted=[SEQ::sv1::..2837]
2026-10-05T13:46:01.094Z epoch=30 activation=2026-10-05T13:45:17.998424Z size=4 blacklisted=[]

$ zcat $C | grep -a -E 'below weak quorum' | grep -a globalSequencerSv1 | sed -E 's/"logger_name".*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-150
2026-10-05T13:44:56.137Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may n
2026-10-05T13:45:00.957Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may n

$ zcat $C | grep -a 'P2P connectivity is not ready' | grep -a MempoolModule | grep -a -oE '"@timestamp":"[^"]+"' | sed -n '1p;$p'; zcat $C | grep -a 'P2P connectivity is not ready' | grep -a -c MempoolModule
"@timestamp":"2026-10-05T13:44:56.272Z"
"@timestamp":"2026-10-05T13:44:59.854Z"
27
```

The topology steps 1 -> 4 in one change (epoch 26), sv1 is below weak quorum from 13:44:56.137, its mempool rejects
submissions for 3.6 s (13:44:56.272-13:44:59.854), and sv1 is blacklisted for epochs 27-29. The ack's clean
timestamp 13:44:56.087186 is 1 us before epoch 26's activationTime 13:44:56.087187, as in 10212.

## 4. The stuck ack, end to end by trace id

```
$ zcat $C | grep -a b4be09b4ae623ba6fdfc37cebfd45cc3 | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-260
2026-10-05T13:44:58.905Z Acknowledging clean timestamp: 2026-10-05T13:44:56.087186Z",[c.d.c.s.c.PeriodicAcknowledgements:mediator=globalMediatorSv1/psid=global-domain::122049ba17c3::36-0] DEBUG
2026-10-05T13:44:58.906Z [acknowledge] requesting 1 connection(s) excluding Set() allowing only Set(sv1, sv2, sv3, sv4)",[c.d.c.s.c.p.SequencerConnectionPoolImpl:mediator=globalMediatorSv1/psid=global-domain::122049ba17c3::36-0/pool=main] DEBUG
2026-10-05T13:44:58.906Z [acknowledge] returning Set(sequencer-connection-DefaultSequencer-0)",[c.d.c.s.c.p.SequencerConnectionPoolImpl:mediator=globalMediatorSv1/psid=global-domain::122049ba17c3::36-0/pool=main] DEBUG
2026-10-05T13:44:58.906Z Sending request acknowledge-signed/2026-10-05T13:44:56.087186Z to server-DefaultSequencer-0.",[c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/psid=global-domain::122049ba17c3::36-0/pool=main/connection=DefaultSequencer-0] DEBUG
2026-10-05T13:44:58.907Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:60214: received a message AcknowledgeSignedRequest(ByteString)",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
2026-10-05T13:44:58.907Z Request for member MED::sv1::12206d77e154... to acknowledge timestamp 2026-10-05T13:44:56.087186Z",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::122049ba17c3::36-0] DEBUG
2026-10-05T13:44:58.908Z member MED::sv1::12206d77e154... acknowledging timestamp 2026-10-05T13:44:56.087186Z",[c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=globalSequencerSv1/psid=global-domain::122049ba17c3::36-0] DEBUG
2026-10-05T13:44:58.908Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::122049ba17c3::36-0] INFO
2026-10-05T13:46:58.906Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:60214: cancelled",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] INFO
2026-10-05T13:46:58.908Z Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999930336s. Name resolution delay 0.000000000 seconds. [closed=[], open=[[call_credentials_delay=5939ns, remot
2026-10-05T13:46:58.908Z Retry has not been configured for GrpcClientGaveUp, giving up.",[c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/psid=global-domain::122049ba17c3::36-0/pool=main/connection=DefaultSequencer-0] DEBUG
2026-10-05T13:46:58.932Z Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.9999
2026-10-05T13:46:58.960Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:60214: sending response AcknowledgeSignedResponse()",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
2026-10-05T13:46:58.960Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:60214: succeeded(OK)",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
```

As in 10225: the mempool rejection at 13:44:58.908 does not complete the AcknowledgeSigned call; the server answers
only 52 ms after the client cancelled at the 120 s deadline. The sequencer was not down for 120 s; the rejection
window lasted 3.6 s (section 3).

## 5. Canton pin and fix state on main

```
$ git show a3132ebe27:nix/canton-sources.json | grep -m1 '"version"'
  "version": "3.6.0-snapshot.20261001.20345.0.v85a9270a",
$ git log --format='%h %ad %s' --date=iso origin/main -3 -- nix/canton-sources.json
8bf7f8c034 2026-10-05 16:44:03 +0200 Bump canton to 3.6.1 (#7635)
a7258b8440 2026-10-02 09:34:41 +0200 Bump canton to 3.6.0-snapshot.20261001.20345.0.v85a9270a (#7584)
825a1daf84 2026-09-30 13:17:10 +0200 Upgrade Canton to 3.6.0-snapshot.20260929.20331.0.v07b3f95b (#7537)
```

No splice-side family B mitigation (serialised SV sequencer onboarding, P2P-authentication gate) has landed on main
since 10225; main moved to canton 3.6.1 after this run (#7635).

## Verdict

Duplicate of family B (umbrella 10165), signature (1); same shape as 10212 (sv1Participant) and 10225 (also the
mediator as the acking client). Flake, Canton-side: the BFT ordering topology activates the 1 -> 4 step before the
newcomers are P2P-authenticated, and a mempool rejection leaves the AcknowledgeSigned call open until the client's
120 s deadline. Trigger here is ValidatorIntegrationTest's 4-SV `initDso()` (sv2-sv4 onboard within 2.3 s). No ignore
pattern proposed (the WARN carries the signal for this open Canton bug); no fix branch. Not verified: whether canton
3.6.1 (now on main) changes either the activation or the unanswered-rejection behaviour.
