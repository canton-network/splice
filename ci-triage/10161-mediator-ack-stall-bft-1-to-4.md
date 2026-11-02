# 10161 - globalMediatorSv1 acknowledge-signed hangs 120 s on SEQ::sv1 during the 1 -> 4 BFT onboarding step (run 35206251191)

DUPLICATE of 10094 and 10153 (same mechanism, same log shape). Second occurrence on main / canton 3.6 in two
days. Ref 10161 confirmed by Raymond on 2026-09-17 (the same run's wall-clock-time (1) is 10162).

- Run: https://github.com/canton-network/splice/actions/runs/35206251191, main 22e775d614 (#7363), job
  105152950723 `wall-clock-time (6)`, canton 3.6.0-snapshot.20260910.20260.0.v90621933.
- All 25 tests in 9 suites pass; `checkErrors` flags two WARNs (mediator variant, exactly as in 10094).

## 1. Flagged lines

```
grep -a -B400 'contains problems' log/10161/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line'
```
```
09:52:47.459Z WARN GrpcConnection:mediator=globalMediatorSv1/.../connection=DefaultSequencer-0
  Request failed for server-DefaultSequencer-0. GrpcClientGaveUp: DEADLINE_EXCEEDED ... after 119.999947674s ... remote_addr=localhost/127.0.0.1:5108
  Request: acknowledge-signed/2026-09-17T09:50:44.762302Z
09:52:47.460Z WARN PeriodicAcknowledgements:mediator=globalMediatorSv1  Failed to acknowledge clean timestamp (usually because sequencer is down): ...
```

## 2. Test running: SvOnboardingIntegrationTest (initDso -> 1 -> 4 step)

```
zcat log/10161/logs-wall-clock-time-6/canton_network_test.clog.gz | grep -a -E "Starting '|Test succeeded" | grep -a 'T09:(49|5[0-2])'
```
```
09:49:48.595Z Starting 'SvOnboardingIntegrationTest/fail registration with invalid tokens, succeed with a valid token'
09:52:10.643Z Test succeeded: ... (2 min 22 s)
```

## 3. Epochs on globalSequencerSv1

```
zcat .../canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'New epoch|Authenticated P2P nodes count' | grep -a 'T09:5[0-2]'
```
```
09:50:38.092Z New epoch 31 leaders=[sv1]                 size=1
09:50:45.319Z Authenticated P2P nodes count 1 is currently below weak quorum size 2 ...
09:50:45.334Z New epoch 32 leaders=[sv1,sv2,sv3,sv4]     size=4 weak=2 strong=3
09:50:49.242Z Authenticated P2P nodes count 2 is currently below strong quorum size 3
09:50:49.754Z Authenticated P2P nodes count 3 is now again above strong quorum size 3
09:51:07.289Z New epoch 33 leaders=[sv2,sv3,sv4]         blacklisted=[sv1]
09:51:21.431Z New epoch 34                               blacklisted=[sv1]
09:51:35.341Z New epoch 35                               blacklisted=[sv1]
09:51:48.810Z New epoch 36 leaders=[sv1,sv2,sv3,sv4]     blacklisted=[]
```
Quorum gap 09:50:45.3 - 09:50:49.8 (4.5 s), blacklist 09:51:07.3 - 09:51:48.8 (41.5 s).

## 4. The ack: accepted in the gap, answered 190 ms after the client cancelled

```
zcat .../canton_before_shutdown.clog.gz | grep -a 16d053548e8f6e9a57154c16cc3c85ed | grep -a -v 'mediator=globalMediatorSv1'
```
```
09:50:47.438Z ApiRequestLogger:sequencer=globalSequencerSv1  AcknowledgeSigned ... received a message
09:50:47.438Z BlockSequencer                                  Request for member MED::sv1::12201a13bde8... to acknowledge timestamp 2026-09-17T09:50:44.762302Z
09:50:47.438Z MempoolModule                                   P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting
09:52:47.437Z ApiRequestLogger                                AcknowledgeSigned ... cancelled
09:52:47.629Z ApiRequestLogger                                AcknowledgeSigned ... sending response AcknowledgeSignedResponse()
```

## 5. Verdict

Same as 10153 section 5. The pattern is now stable across both variants of the caller (participant in 10153,
mediator here and in 10094): the ack that lands in the ~4.5 s window between the 1 -> 4 topology activation
and the newcomers' P2P authentication is held for exactly the 120 s client deadline. With two hits on main in
two days (10153 on 09-16, this on 09-17) plus 10094, this is the most frequent live checkErrors item; a
Canton-side fix (do not accept acks the mempool cannot disseminate, or answer them once ordering resumes)
would remove it, and the splice mitigations from 10137 remain the alternative.
