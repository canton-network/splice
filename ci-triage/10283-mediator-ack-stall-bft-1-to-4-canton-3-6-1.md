# 10283 - globalMediatorSv1 acknowledge-signed DEADLINE_EXCEEDED after the BFT 1 -> 4 step, on canton 3.6.1 (run 37351458092)

DUPLICATE of family B (umbrella 10165), signature (1), same shape as 10279 (5 h earlier on the 3.6.0 snapshot), 10212
and 10225. First family B hit on canton 3.6.1 (#7635): the bump does not fix it. main 073e1872f0, job 111903622596
`ci / scala_test_wall_clock_time / wall-clock-time (0)`. All 35 tests passed; checkErrors flagged one WARN in
`canton_before_shutdown.clog`: the mediator's periodic `acknowledge-signed` for clean timestamp 18:23:01.014947 to
globalSequencerSv1 (:5108) timed out after 120 s. The step was again the 4-SV `initDso()` of ValidatorIntegrationTest
"validator apps connect to all DSO sequencers": sv2-sv4 called `onboard/sv/sequencer` within 3.2 s, epoch 26 stepped
1 -> 4 at 18:23:02.754111, sv1 was below weak quorum from 18:23:02.869 and was blacklisted for epochs 27-29. sv1's
mempool rejected the ack within 1 ms (18:23:04.595) but the AcknowledgeSigned call stayed open until the client
cancelled it at the 120 s deadline. Flake, Canton-side; no fix branch.

- Run: https://github.com/canton-network/splice/actions/runs/37351458092, main 073e1872f0 ("Bump versions for next
  release (#7637)"), job 111903622596 `ci / scala_test_wall_clock_time / wall-clock-time (0)`. Only failed job in the run.
- Runtime canton: 3.6.1 (`git show 073e1872f0:nix/canton-sources.json`; includes 8bf7f8c034 "Bump canton to 3.6.1 (#7635)").
- Component: Canton BFT orderer (mempool rejection does not complete the AcknowledgeSigned call; ordering topology
  activated before P2P authentication).
- Artifact: `logs-wall-clock-time-0` in `log/10283/logs-wall-clock-time-0/`.

## 1. Classification: all tests passed, one flagged WARN

```
$ gh api repos/canton-network/splice/actions/jobs/111903622596 --jq '"\(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)"'
failure 2026-10-05T18:09:31Z 2026-10-05T18:39:45Z self-hosted-k8s-large-4p78d-runner-dcrzv

$ gh api repos/canton-network/splice/actions/jobs/111903622596/logs > log/10283/job.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10283/job.log | grep -a -E 'Tests: succeeded|All tests passed|contains problems' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++'
[info] Tests: succeeded 35, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.

$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10283/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -a -v 'ignore this line in check-sbt-output' | cut -c1-420
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-10-05T18:25:04.703Z","message":"Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999946558s. Name resolution delay 0.000000000 seconds. [closed=[], open=[[call_credentials_delay=12790ns, remote_addr=localhost/127.0.0.1:5108]]]\n  Request: acknowledge-signed/2026-10-05T18:23:01.014947Z","logger_name":"c.d.c.s.c.p.GrpcCon
Total: 1 lines with problems.

$ git show 073e1872f0:nix/canton-sources.json | grep -m1 '"version"'; git merge-base --is-ancestor 8bf7f8c034 073e1872f0 && echo "3.6.1 bump included"
  "version": "3.6.1",
3.6.1 bump included
```

The companion `Failed to acknowledge clean timestamp` WARN (18:25:04.703) is ignored by
`project/ignore-patterns/canton_log.ignore.txt:83`; only the GrpcConnection line is flagged.

## 2. Which test was running, and the onboarding burst

```
$ T=log/10283/logs-wall-clock-time-0/canton_network_test.clog.gz
$ zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'T18:2[0-6]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | sed -n '3,5p'
2026-10-05T18:21:59.559Z Test succeeded: 'ValidatorIntegrationTest/initialize DSO and validator apps'",
2026-10-05T18:21:59.559Z Starting 'ValidatorIntegrationTest/validator apps connect to all DSO sequencers'...",
2026-10-05T18:24:33.147Z Test succeeded: 'ValidatorIntegrationTest/validator apps connect to all DSO sequencers'",

$ zcat $T | grep -a 'T18:2[1-4]' | grep -a -E 'onboard/sv/start|onboard/sv/sequencer' | grep -a 'received request' | sed -E 's/"logger_name".*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-110
2026-10-05T18:22:43.585Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:55574): received request.",
2026-10-05T18:22:43.697Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:55596): received request.",
2026-10-05T18:22:44.384Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:55618): received request.",
2026-10-05T18:22:56.563Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:47554): received request.",
2026-10-05T18:22:57.684Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:47566): received request.",
2026-10-05T18:22:59.702Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:39058): received request.",
```

## 3. Family B confirmation on globalSequencerSv1: 1 -> 4 step, quorum loss, sv1 blacklisted

```
$ C=log/10283/logs-wall-clock-time-0/canton_before_shutdown.clog.gz
$ zcat $C | grep -a 'globalSequencerSv1' | grep -a -E 'New epoch (2[4-9]|30) has started' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\).*/\1 epoch=\2 leaders=[\3] blacklisted=[\4]/' | sed -E 's/SEQ::(sv[0-9])::\.\.[0-9a-f]*/\1/g' | cut -c1-120
2026-10-05T18:22:48.778Z epoch=24 leaders=[sv1] blacklisted=[]
2026-10-05T18:22:55.801Z epoch=25 leaders=[sv1] blacklisted=[]
2026-10-05T18:23:02.881Z epoch=26 leaders=[sv3, sv4, sv1, sv2] blacklisted=[]
2026-10-05T18:23:25.057Z epoch=27 leaders=[sv2, sv3, sv4] blacklisted=[sv1]
2026-10-05T18:23:37.960Z epoch=28 leaders=[sv3, sv4, sv2] blacklisted=[sv1]
2026-10-05T18:23:51.438Z epoch=29 leaders=[sv4, sv2, sv3] blacklisted=[sv1]
2026-10-05T18:24:05.289Z epoch=30 leaders=[sv3, sv4, sv1, sv2] blacklisted=[]

$ zcat $C | grep -a 'globalSequencerSv1' | grep -a -m1 'New epoch 26 has started' | grep -a -oE 'activationTime = [0-9T:.Z-]+'
activationTime = 2026-10-05T18:23:02.754111Z

$ zcat $C | grep -a 'below weak quorum' | grep -a globalSequencerSv1 | sed -E 's/"logger_name".*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-120
2026-10-05T18:23:02.869Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, di
2026-10-05T18:23:05.870Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, di

$ zcat $C | grep -a 'P2P connectivity is not ready' | grep -a MempoolModule | grep -a globalSequencerSv1 | grep -a -oE '"@timestamp":"[^"]+"' | sed -n '1p;$p'; zcat $C | grep -a 'P2P connectivity is not ready' | grep -a MempoolModule | grep -a -c globalSequencerSv1
"@timestamp":"2026-10-05T18:23:02.895Z"
"@timestamp":"2026-10-05T18:23:05.714Z"
20
```

Epoch 26 activates the 1 -> 4 ordering topology at 18:23:02.754111; sv1 is below weak quorum from 18:23:02.869, its
mempool rejects 20 submissions in 2.8 s (18:23:02.895-18:23:05.714), and sv1 is blacklisted for epochs 27-29. Unlike
10212 and 10279, the acked clean timestamp (18:23:01.014947) is 1.74 s before the activation, not 1 us: what matters
is that the ack is SENT inside the rejection window, not which timestamp it acknowledges.

## 4. The stuck ack, end to end by trace id

```
$ zcat $C | grep -a f25e20c7249a273c54fc78ee81f1d3e2 | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-200
2026-10-05T18:23:04.593Z Acknowledging clean timestamp: 2026-10-05T18:23:01.014947Z",[c.d.c.s.c.PeriodicAcknowledgements:mediator=globalMediatorSv1/psid=global-domain::1220145559c7::36-0] DEBUG
2026-10-05T18:23:04.594Z Sending request acknowledge-signed/2026-10-05T18:23:01.014947Z to server-DefaultSequencer-0.",[c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/...] DEBUG
2026-10-05T18:23:04.595Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:40424: received a message AcknowledgeSignedRequest(ByteString)",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
2026-10-05T18:23:04.595Z member MED::sv1::12206805b4c0... acknowledging timestamp 2026-10-05T18:23:01.014947Z",[c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=globalSequencerSv1/...] DEBUG
2026-10-05T18:23:04.595Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/...] INFO
2026-10-05T18:25:04.699Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:40424: cancelled",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] INFO
2026-10-05T18:25:04.703Z Request failed for server-DefaultSequencer-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999946558s. ...
2026-10-05T18:25:04.703Z Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(...
2026-10-05T18:25:05.102Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:40424: sending response AcknowledgeSignedResponse()",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
```

(Middle lines abbreviated with `...`; the unabbreviated output is the command's.) As in 10225 and 10279: the
mempool rejection at 18:23:04.595 does not complete the AcknowledgeSigned call; the server answers 403 ms after the
client cancelled at the 120 s deadline. The sequencer was not down for 120 s; the rejection window lasted 2.8 s.

## 5. What changed since 10279

```
$ git log --format='%h %s' a3132ebe27..073e1872f0 | grep -i -E 'bft|sequencer onboard|onboard.*sequencer|p2p|canton'
8bf7f8c034 Bump canton to 3.6.1 (#7635)
```

Eight commits between the 10279 sha and this one; the only relevant one is the canton 3.6.1 bump. No splice-side
family B mitigation (serialised SV sequencer onboarding, P2P-authentication gate) is on main.

## Verdict

Duplicate of family B (umbrella 10165), signature (1); identical to 10279 (same suite, same test, same client) and
the first occurrence on canton 3.6.1, so the 3.6.1 bump neither gates the 1 -> 4 activation on P2P authentication
nor completes a rejected AcknowledgeSigned call. Flake, Canton-side; trigger is ValidatorIntegrationTest's 4-SV
`initDso()`. No ignore pattern proposed (the WARN is the signal for this open Canton bug); no fix branch. Not
verified: the 3.6.1 source of the mempool rejection path (no 3.6.1 jar inspected); whether the 1.74 s gap between
the clean timestamp and activation (vs 1 us in 10212 and 10279) has any significance beyond the ack being sent
inside the rejection window.
