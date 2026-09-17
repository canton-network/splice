# 10153 - splitwellParticipant acknowledge-signed hangs 120 s on SEQ::sv1 during the 1 -> 4 BFT onboarding step (run 35113435367)

DUPLICATE of 10094 (run 34477382133, canton 3.5.15) and same family as 10048 / 10137. This is the first
occurrence recorded on main with canton 3.6.

- Run: https://github.com/canton-network/splice/actions/runs/35113435367, main f3adbc39e1 ("[ci] Ignore INTERNAL
  from GetConnectedSynchronizers mid-reconnect"), job 104853130622 `wall-clock-time (0)`.
- Runtime canton: 3.6.0-snapshot.20260910.20260.0.v90621933 (`nix/canton-sources.json` at f3adbc39e1).
- All 36 tests in 8 suites passed; `checkErrors` fails `log/canton_before_shutdown.clog` on two WARN lines.
- Component: Canton BFT ordering (sequencer onboarding membership step); splice test `ValidatorIntegrationTest`
  "validator apps connect to all DSO sequencers" is where the 1 -> 4 step happens.

## 1. The two flagged lines (job console)

```
grep -a -B300 'contains problems' log/10153/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line'
```
```
15:35:20.404Z WARN c.d.c.s.c.p.GrpcConnection:participant=splitwellParticipant/.../connection=SEQ::sv1::1220c626d7..-0
  Request failed for server-SEQ::sv1::...-0. GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after
  119.999920909s ... remote_addr=localhost/127.0.0.1:5108  Request: acknowledge-signed/2026-09-16T15:33:16.033203Z
15:35:20.409Z WARN c.d.c.s.c.PeriodicAcknowledgements:participant=splitwellParticipant/psid=global-domain::1220f7f29c5d::36-0
  Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(... same ...))
```

Everything else printed by check-logs.sh carries the `(ignore this line in check-sbt-output.sh)` suffix
(PERMISSION_DENIED `Could not resolve is_deactivated status` lines, ignored by `canton_log.ignore.txt:29`).

## 2. Which test was running

```
zcat log/10153/logs-wall-clock-time-0/canton_network_test.clog.gz | grep -a -E "Starting '|Test succeeded" | grep -a 'T15:3[2-5]'
```
```
15:32:15.101Z Starting 'ValidatorIntegrationTest/validator apps connect to all DSO sequencers'...
15:35:00.648Z Test succeeded: 'ValidatorIntegrationTest/validator apps connect to all DSO sequencers'   (2 min 45 s)
```

`ValidatorIntegrationTest.scala:158-181`: `initDso()` (onboards sv1..sv4, i.e. the 1 -> 4 sequencer step), then
`aliceValidatorBackend.startSync()` and an `eventually(1.minute)` for 4 sequencer connections.

## 3. The ack reached sv1 and was answered 155 ms after the client gave up

```
zcat log/10153/logs-wall-clock-time-0/canton_before_shutdown.clog.gz | grep -a d575687ba36abea69c08f0418c44084e | grep -a -v splitwellParticipant
```
```
15:33:20.401Z DEBUG ApiRequestLogger:sequencer=globalSequencerSv1  AcknowledgeSigned by grpc:/127.0.0.1:35470: received a message
15:33:20.402Z DEBUG BlockSequencer:sequencer=globalSequencerSv1    Request for member PAR::splitwellValidator::122085431fd4... to acknowledge timestamp 2026-09-16T15:33:16.033203Z
15:33:20.402Z DEBUG BftBlockOrderer:sequencer=globalSequencerSv1   member PAR::splitwellValidator::... acknowledging timestamp 2026-09-16T15:33:16.033203Z
15:33:20.403Z INFO  MempoolModule:sequencer=globalSequencerSv1     P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting
15:35:20.400Z INFO  ApiRequestLogger:sequencer=globalSequencerSv1  AcknowledgeSigned by grpc:/127.0.0.1:35470: cancelled
15:35:20.555Z DEBUG ApiRequestLogger:sequencer=globalSequencerSv1  AcknowledgeSigned by grpc:/127.0.0.1:35470: sending response AcknowledgeSignedResponse()
```

Unlike 10094 ("accepted and never answered"), here the response was produced exactly 120.15 s after arrival, i.e.
right after the client's 120 s deadline cancelled the call. The mempool rejection at 15:33:20.403 is the
same-tid event that the ack request triggered.

## 4. Why sv1 could not order anything: 1 -> 4 step with only itself authenticated, then blacklisted 3 epochs

```
zcat ... canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'New epoch [0-9]+ has started|Authenticated P2P nodes count|blacklisted, rejecting' | grep -a 'T15:3[3-4]' | uniq -c -s 24
```
```
15:33:10.135Z New epoch 27 leaders = [sv1]                       blacklisted = []     size=1 weak=1 strong=1
15:33:18.005Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2 ...
15:33:18.021Z New epoch 28 leaders = [sv1, sv2, sv3, sv4]         blacklisted = []     size=4 weak=2 strong=3   activation=15:33:17.704506Z
15:33:18.084Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting   (x14 until 15:33:20.4, incl. the ack)
15:33:22.795Z Authenticated P2P nodes count 2 is currently below strong quorum size 3 ...
15:33:24.443Z Authenticated P2P nodes count 3 is now again above strong quorum size 3
15:33:39.957Z New epoch 29 leaders = [sv4, sv2, sv3]              blacklisted = [sv1]
15:33:42.166Z Mempool received client request but this node is currently blacklisted, rejecting   (x60)
15:33:53.846Z New epoch 30 leaders = [sv2, sv3, sv4]              blacklisted = [sv1]   (x48 rejects)
15:34:08.157Z New epoch 31 leaders = [sv3, sv4, sv2]              blacklisted = [sv1]   (x46 rejects)
15:34:22.511Z New epoch 32 leaders = [sv1, sv2, sv3, sv4]         blacklisted = []
```

Sequence: epoch 28 activates the 4-node ordering topology at 15:33:17.7 while sv2-4 are still initialising
(`InitializeSequencerFromOnboardingStateV2` on globalSequencerSv4 at 15:33:18.445). sv1 is without
dissemination quorum for ~4.5 s (15:33:18.0 - 15:33:22.8) and cannot make progress in epoch 28, so the
other three blacklist it for epochs 29-31 (15:33:39.96 - 15:34:22.51, 42.5 s). The five other participants
hit the blacklist window too, but as `Failed to acknowledge clean timestamp as sequencer was not available`
at INFO (sv3 15:34:27.5, sv2 15:34:28.7, sv4 15:34:30.4, alice 15:34:33.1, sv1 15:34:34.4), which is not
flagged. Only splitwell's ack, accepted at 15:33:20.4 in the quorum-loss gap, hangs and produces WARNs.

## 5. Verdict

Same root cause as 10094 section 8 (a 1 -> 4 BFT topology step applied while the newcomers are not yet
P2P-authenticated; sv1 loses quorum, is blacklisted, and an ack accepted in that window is not answered
within the client deadline). 10094 was canton 3.5.15 on release-line-0.7.5; this is canton 3.6 on main, so
the family (10048, 10094, 10137, now 10153) is live on both lines. Splice-side mitigations are the ones in
the 10137 packet: serialise SV sequencer onboardings, or release onboarding only when the newcomer is
P2P-authenticated. No ignore-pattern change proposed.
