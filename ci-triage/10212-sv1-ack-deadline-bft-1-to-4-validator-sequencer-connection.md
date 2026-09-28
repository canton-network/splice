# 10212 - sv1Participant ack to globalSequencerSv1 hits the 120 s deadline after the BFT ordering topology steps 1 -> 4 (run 35873276062)

DUPLICATE of the 10165 BFT-onboarding umbrella (family B, signature (1)). All 24 tests pass; checkErrors flags
two WARNs from one acknowledgement. During the environment setup of ValidatorSequencerConnectionIntegrationTest,
sv2, sv3 and sv4 start SV onboarding within 0.4 s of each other, so the BFT ordering topology on the global
synchronizer steps from 1 to 4 sequencers in a single change (activationTime 14:42:40.553747Z, epoch 90). sv1
then has only itself authenticated (1 < weak quorum 2) for about 3 s and is blacklisted by its peers for epochs
91-93 (14:43:01 to 14:43:42). sv1Participant's periodic ack, sent at 14:42:42.898 inside that gap, is rejected by
globalSequencerSv1's mempool but the gRPC call is not answered; the client gives up at the 120 s deadline
(14:44:42.899) and logs the two WARNs. The system self-heals; nothing in the test depends on the ack.

- Run: https://github.com/canton-network/splice/actions/runs/35873276062, `main` 5484da7cab
  ("Use strong token in backport notify_on_failure (#7457)"), job 107223309546
  `ci / scala_test_wall_clock_time / wall-clock-time (1)`.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824.
- Component: Canton (BFT ordering activates the new topology before P2P authentication; the mempool rejection of
  an ack does not complete the gRPC call). Not a test bug.

All commands below are run from the repo root with the artifact `logs-wall-clock-time-1` in
`log/10212/logs-wall-clock-time-1/` and the job log in `log/10212/job.log`
(`gh api repos/canton-network/splice/actions/jobs/107223309546/logs > log/10212/job.log`); long hashes are
trimmed by a sed baked into each command. GitHub masks `{` as `***` in the job log.

## 1. Job result and flagged lines

All 24 tests pass; checkErrors fails on canton_before_shutdown.clog. The flagged lines (the `@timestamp` lines without the ignore suffix) are two WARNs for one ack from sv1Participant.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10212/job.log | grep -a -E 'Tests: succeeded|All tests passed|contains problems' | sed -E 's/^[^Z]*Z //' | sort -u
grep -a -B400 'canton_before_shutdown.clog contains problems' log/10212/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' | sed -E 's/^[^Z]*Z //; s/1220[0-9a-f]{60}/../g; s/("level":"[A-Z]+").*/\1/' | cut -c1-420
```

```
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[info] All tests passed.
[info] Tests: succeeded 24, failed 0, canceled 0, ignored 0, pending 0
***"@timestamp":"2026-09-23T14:44:42.899Z","message":"Request failed for server-SEQ::sv1::..4806-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999937106s. Name resolution delay 0.000000000 seconds. [closed=[CANCELLED], committed=[call_credentials_delay=21503ns, remote_addr=localhost/127.0.0.1:5108]]\n  Request: acknowledge-signed/2026-09-23T14:42:40.553746Z","logger_name":"c.d.c.s.
***"@timestamp":"2026-09-23T14:44:42.901Z","message":"Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-SEQ::sv1::..4806-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999937106s. Name resolution delay 0.000000000 seconds. [closed=[CANCELLED], committed=[call_credentials_delay=21503ns, remote_addr=loca
```

## 2. Which test was running

The ack was sent at 14:42:42.898 (section 4). The running suite is ValidatorSequencerConnectionIntegrationTest, whose environment setup brings up sv1-sv4.

```
zcat log/10212/logs-wall-clock-time-1/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): " | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-150 | awk '$1>="2026-09-23T14:41" && $1<="2026-09-23T14:45"'
```

```
2026-09-23T14:41:23.610Z Test succeeded: 'ValidatorReonboardingIntegrationTest/re-onboard validator'",
2026-09-23T14:41:25.329Z Starting test suite 'ValidatorSequencerConnectionIntegrationTest'...",
2026-09-23T14:44:15.072Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified seq
2026-09-23T14:44:15.079Z Starting test suite 'ExternallySignedPartyOnboardingTest'...",
2026-09-23T14:44:52.430Z Test succeeded: 'ExternallySignedPartyOnboardingTest/a ccsp provider should should be able to onboard a party with externally
```

## 3. BFT ordering topology steps 1 -> 4, sv1 below weak quorum, sv1 blacklisted (family B confirming grep)

On globalSequencerSv1: epoch 89 still has size 1; epoch 90 has size 4 with activationTime 14:42:40.553747Z; 0.3 s later sv1 reports 1 authenticated P2P node, below weak quorum 2; sv1 is blacklisted in epochs 91-93 and back in 94.

```
zcat log/10212/logs-wall-clock-time-1/canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'New epoch (89|9[0-4]) has started|below weak quorum' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/SEQ::(sv[0-9])::[.0-9a-f]+/\1/g' | sed -E 's/ordering topology = OrderingTopology\(\\n  activationTime = ([^,]*),\\n  size = ([0-9]+).*/activationTime=\1 size=\2/; s/, dissemination and ordering.*//' | awk '{k=substr($0,26,26); if(!(k in s)){s[k]=1; print}}'
```

```
2026-09-23T14:42:33.420Z New epoch 89 has started with leaders = List(sv1)and blacklisted nodes = List(); activationTime=2026-09-23T14:42:33.263145Z size=1
2026-09-23T14:42:40.845Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2
2026-09-23T14:42:40.850Z New epoch 90 has started with leaders = List(sv3, sv4, sv1, sv2)and blacklisted nodes = List(); activationTime=2026-09-23T14:42:40.553747Z size=4
2026-09-23T14:43:01.027Z New epoch 91 has started with leaders = List(sv3, sv4, sv2)and blacklisted nodes = List(sv1); activationTime=2026-09-23T14:42:40.553747Z size=4
2026-09-23T14:43:14.881Z New epoch 92 has started with leaders = List(sv4, sv2, sv3)and blacklisted nodes = List(sv1); activationTime=2026-09-23T14:42:40.553747Z size=4
2026-09-23T14:43:28.781Z New epoch 93 has started with leaders = List(sv2, sv3, sv4)and blacklisted nodes = List(sv1); activationTime=2026-09-23T14:42:40.553747Z size=4
2026-09-23T14:43:42.906Z New epoch 94 has started with leaders = List(sv3, sv4, sv1, sv2)and blacklisted nodes = List(); activationTime=2026-09-23T14:42:40.553747Z size=4
```

## 4. The ack end to end (trace b8c53a116411b48871195ba256739a47)

sv1Participant acknowledges clean timestamp 14:42:40.553746Z (1 us before the new topology's activation) at 14:42:42.898. globalSequencerSv1's MempoolModule rejects it (authenticated 1 < dissemination quorum 2), but the gRPC call is not answered: the client cancels at the 120 s deadline and the sequencer sends its response 0.1 s after that.

```
zcat log/10212/logs-wall-clock-time-1/canton_before_shutdown.clog.gz | grep -a 'b8c53a116411b48871195ba256739a47' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",?"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/; s/1220[0-9a-f]{60}/../g; s/psid=global-domain::[^/]*/psid=../; s/ \[c\.d\.c\.[^:]*\.([A-Za-z]+):([a-z]+=[A-Za-z0-9]+)[^]]*\]/ [\1:\2]/' | cut -c1-230
```

```
2026-09-23T14:42:42.897Z Acknowledging clean timestamp: 2026-09-23T14:42:40.553746Z [c.d.c.s.c.PeriodicAcknowledgements:participant=sv1Participant/psid=..
2026-09-23T14:42:42.898Z [acknowledge] requesting 1 connection(s) excluding Set() allowing only Set(sv1, sv4, sv2, sv3) [SequencerConnectionPoolImpl:participant=sv1Participant] DEBUG
2026-09-23T14:42:42.898Z [acknowledge] returning Set(sequencer-connection-SEQ::sv1::..4806-0) [SequencerConnectionPoolImpl:participant=sv1Participant] DEBUG
2026-09-23T14:42:42.898Z Sending request acknowledge-signed/2026-09-23T14:42:40.553746Z to server-SEQ::sv1::..4806-0. [GrpcConnection:participant=sv1Participant] DEBUG
2026-09-23T14:42:42.898Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:40482: received a message AcknowledgeSignedRequest(ByteString) [ApiRequestLogger:sequencer=globalSequ
2026-09-23T14:42:42.899Z Request for member PAR::sv1::122031d70039... to acknowledge timestamp 2026-09-23T14:42:40.553746Z [c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=..
2026-09-23T14:42:42.899Z member PAR::sv1::122031d70039... acknowledging timestamp 2026-09-23T14:42:40.553746Z [c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=globalSequencerSv1/psid=..
2026-09-23T14:42:42.899Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting [c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=..
2026-09-23T14:44:42.897Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:40482: cancelled [ApiRequestLogger:sequencer=globalSequencerSv1] INFO
2026-09-23T14:44:42.899Z Request failed for server-SEQ::sv1::..4806-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999937106s. Name resolution delay 0.000000000 seconds. [closed=[CANCELLED], comm
2026-09-23T14:44:42.899Z Retry has not been configured for GrpcClientGaveUp, giving up. [GrpcConnection:participant=sv1Participant] DEBUG
2026-09-23T14:44:42.901Z Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-SEQ::sv1::..4806-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions de
2026-09-23T14:44:43.000Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:40482: sending response AcknowledgeSignedResponse() [ApiRequestLogger:sequencer=globalSequencerSv1] D
2026-09-23T14:44:43.001Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:40482: succeeded(OK) [ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
```

## 5. How long sv1 lacked P2P quorum

The mempool rejections on globalSequencerSv1 span 3.1 s; the ack at 14:42:42.899 falls inside that window. The stall of the ack itself (120 s) is far longer than the connectivity gap.

```
zcat log/10212/logs-wall-clock-time-1/canton_before_shutdown.clog.gz | grep -a 'P2P connectivity is not ready' | grep -a 'MempoolModule:sequencer=globalSequencerSv1' | sed -E 's/\{"@timestamp":"([^"]+)".*/\1/' | awk 'NR==1{f=$0} {l=$0; n++} END{print n" rejections, first "f", last "l}'
```

```
25 rejections, first 2026-09-23T14:42:41.292Z, last 2026-09-23T14:42:44.420Z
```

## 6. Why the topology stepped 1 -> 4 at once

sv2, sv3 and sv4 start SV onboarding against sv1 within 0.4 s of each other, so their sequencers join the ordering topology in a single change (compare section 3).

```
zcat log/10212/logs-wall-clock-time-1/canton_network_test.clog.gz | grep -a 'HTTP POST /api/sv/v0/onboard/sv/start from' | grep -a 'received request' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name".*//'
```

```
2026-09-23T14:42:21.560Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:47266): received request.
2026-09-23T14:42:21.561Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:47268): received request.
2026-09-23T14:42:21.924Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:32932): received request.
```

## 7. Canton pin

The run's sha pins Canton 3.6.0-snapshot.20260916.20284, one day after the 2026-09-15.22 mirror state against which family B was recorded as unfixed; this occurrence shows the behaviour is still present in that snapshot.

```
git show 5484da7cab:nix/canton-sources.json | grep -m1 version
```

```
  "version": "3.6.0-snapshot.20260916.20284.0.vf27c4824",
```

## Verdict

- Duplicate of 10165 (family B, signature (1): `acknowledge-signed ... DEADLINE_EXCEEDED after 119.99s` +
  `Failed to acknowledge clean timestamp`), confirmed by the family's grep: ordering topology size 1 -> 4 at
  epoch 90, `below weak quorum size 2` 5 ms before it, sv1 blacklisted for epochs 91-93, and the ack's
  `MempoolModule: P2P connectivity is not ready ... rejecting` followed by `cancelled` 120 s later.
- New suite for the family: ValidatorSequencerConnectionIntegrationTest (initDso with sv1-sv4). The ack's clean
  timestamp (14:42:40.553746Z) is 1 us before the new topology's activation time.
- Flake (timing of concurrent SV onboardings), fix location Canton; splice-side mitigations are those listed
  under family B (serialise SV sequencer onboardings or gate on P2P authentication). No fix branch.
- Not verified: the Canton source for why a mempool-rejected ack is not answered until the client cancels (no jar
  inspected); whether sv1 is blacklisted because of its own unauthenticated state or its peers'.
