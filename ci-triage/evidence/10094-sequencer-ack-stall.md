# 10094 / 10091 - sequencer acknowledge-signed stall (run 34477382133)

Branch release-line-0.7.5 (backport #7238). Canton runtime 3.5.15. Two independent failed jobs.
Two independent analyses converged on identical evidence. Commands verified against the artifacts.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 34477382133 --repo canton-network/splice -n logs-wall-clock-time-0 -D dl
cd dl
# canton_before_shutdown.clog.gz = node log DURING the run; canton_network_test.clog.gz = test harness
```

## 1. Failed jobs

```
gh run view 34477382133 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
```
```
102871692515  ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)
102871731207  ci / scala_test_wall_clock_time / wall-clock-time (0)
```
Two independent failed jobs. This packet covers wall-clock-time(0) (job 102871731207). The frontend job
is a separate flake (section 7).

## 2. Not a ScalaTest/assertion failure in this shard -> checkErrors WARN scan

```
zcat canton_network_test.clog.gz | grep -aE 'Test failed:|\*\*\* FAILED|RUN ABORTED|Condition never became true' | head
```
```
(zero matches)
```
The tests did not assert-fail; the job fails on checkErrors flagging an un-allowlisted WARN in the node
log (the same failure mode as 10121).

## 3. Canton runtime version

```
zcat canton_before_shutdown.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
```
```
Canton version 3.5.15
```

## 4. The sequencer stopped answering acknowledge-signed for ~120s (WARN 1)

```
zcat canton_before_shutdown.clog.gz | grep -aE '"level":"WARN"' \
  | grep -aE 'Request failed for server-DefaultSequencer-0' | grep -aE 'deadline exceeded after 119' \
  | head -1 | sed -E 's/[0-9a-f]{16,}/<HASH>/g' \
  | grep -aoE '"@timestamp":"[^"]*"|acknowledge-signed/[0-9T:.Z-]+|remote_addr=localhost/127.0.0.1:5108|deadline exceeded after [0-9.]+s|mediator=globalMediatorSv1|connection=DefaultSequencer-0'
```
```
"@timestamp":"2026-09-10T12:59:14.481Z"
deadline exceeded after 119.999933129s
remote_addr=localhost/127.0.0.1:5108
acknowledge-signed/2026-09-10T12:57:12.472124Z
mediator=globalMediatorSv1
connection=DefaultSequencer-0
```
globalMediatorSv1's gRPC connection to DefaultSequencer-0 (localhost:5108) issued acknowledge-signed for
ts 12:57:12.472124Z and gave up with DEADLINE_EXCEEDED after 119.999933129s at 12:59:14.481Z - the
sequencer did not answer that AcknowledgeSigned for ~120s.

## 5. The mediator then logs the ack failure (WARN 2)

```
zcat canton_before_shutdown.clog.gz | grep -aE '"level":"WARN"' \
  | grep -aE 'Failed to acknowledge clean timestamp' | head -1 \
  | grep -aoE '"@timestamp":"[^"]*"|"logger_name":"c.d.c.s.c.PeriodicAcknowledgements:mediator=globalMediatorSv1[^"]*"'
```
```
"@timestamp":"2026-09-10T12:59:14.512Z"
"logger_name":"c.d.c.s.c.PeriodicAcknowledgements:mediator=globalMediatorSv1/psid=global-domain::12207b5fc37d::35-0"
```
At 12:59:14.512Z globalMediatorSv1 logs "Failed to acknowledge clean timestamp (usually because sequencer
is down)". These two globalMediatorSv1 lines are the only WARNs here -> what checkErrors flags.

## 6. All 6 nodes failed the periodic ack at that instant

```
zcat canton_before_shutdown.clog.gz | grep -aE 'Failed to acknowledge clean timestamp' \
  | grep -aoE 'mediator=globalMediatorSv1|participant=[a-z0-9]+Participant' | sort | uniq -c
```
```
1 mediator=globalMediatorSv1
1 participant=aliceParticipant
1 participant=sv1Participant
1 participant=sv2Participant
1 participant=sv3Participant
1 participant=sv4Participant
```
```
zcat canton_before_shutdown.clog.gz | grep -acE 'deadline exceeded after 119\.99'
```
```
2
```
Every node (the mediator + all 5 participants) failed its periodic acknowledge to DefaultSequencer-0 at
~12:59:14, confirming the sequencer, not one client, was unresponsive. Only globalMediatorSv1's two lines
are WARN (participants log INFO), so only they trip checkErrors.

## 6b. Which node is :5108, and the checkErrors verdict (from the job console, not expired)

Port 5108 is globalSequencerSv1's public-api, so the mediator stalled acknowledging to its own co-located
sequencer:

```
zcat canton_before_shutdown.clog.gz | grep -a 'admin-api=5102,ledger-api=5101' | head -1 \
  | grep -aoE '[A-Za-z0-9]*[Ss]equencer[A-Za-z0-9]*:[^;"]*5108[^;"]*'
```
```
globalSequencerSv1:admin-api=5109,public-api=5108
```

The job step console (fetched with `gh api repos/canton-network/splice/actions/jobs/102871731207/logs`)
shows checkErrors' verdict:

```
Total: 726 lines with ignored entries.
Found problems in log/canton_before_shutdown.clog:
  ***...12:59:14.481Z... DEADLINE_EXCEEDED ... after 119.999933129s ... 127.0.0.1:5108 ... acknowledge-signed/...12:57:12.472124Z
  ***...12:59:14.512Z... Failed to acknowledge clean timestamp ...
Total: 2 lines with problems.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.   (build.sbt:2272 / :2304)
##[error]Process completed with exit code 1.
```
Correlated but IGNORED (part of the 726 ignored, did not fail the build): sv4-sequencer http2 "First
received frame was not SETTINGS" WARNs (hex 485454502f = "HTTP/") in the same 12:58-12:59 window.

## 7. Second failed job (separate frontend flake)

Job 102871692515 frontend-wall-clock-time(2): SvFrontendIntegrationTest "SV UIs should NEW UI: Grant,
Update and Revoke Featured App Right" failed 12:53:13 on assertion "0 was not greater than or equal to 1",
failing clue "(check) sv1 can see the new vote from sv2" (screenshot dumped). ZERO DEADLINE_EXCEEDED in
that job - unrelated to the sequencer stall.

## Summary

wall-clock-time(0), job 102871731207, canton 3.5.15. All tests passed; the job fails because checkErrors
flags two WARNs from globalMediatorSv1. globalSequencerSv1's public API (localhost:5108) stopped answering
SequencerService/AcknowledgeSigned: its co-located globalMediatorSv1's acknowledge-signed for ts
2026-09-10T12:57:12.472 hit DEADLINE_EXCEEDED after 119.999933129s at 12:59:14.481, then "Failed to
acknowledge clean timestamp" at 12:59:14.512. All six nodes failed their periodic ack at that instant, so
the sequencer was unresponsive for ~120s mid-run. Root cause to investigate: why globalSequencerSv1's
public API stalled AcknowledgeSigned for ~2 minutes (12:57:12 -> 12:59:14) around record time. The second
failed job (frontend-wall-clock-time(2)) is an unrelated frontend vote-propagation flake.

## 8. Root cause found (2026-09-16): sv1 lost BFT quorum while sv2-4 joined the ordering topology

Prompted by a Canton finding on a different occurrence of "Failed to acknowledge clean timestamp"
(DACH-NY/canton#33616: the sequencer accepts AcknowledgeSigned but does not answer until it has the
traffic state at the ack's sequencing time; there, an upcoming LSU lower bound made that impossible).
Checked whether this run is that variant. It is not: zero SEQUENCING_TIME_NOT_ADMISSIBLE, zero upgrade
announcements, and the suite running was ValidatorIntegrationTest. But the same "accepted, never
answered" shape has a proven cause here.

Artifacts re-downloaded to `log/10094/`; H trims hashes as elsewhere.

### 8a. Not the LSU variant

```
zcat canton_before_shutdown.clog.gz | grep -acE 'SEQUENCING_TIME_NOT_ADMISSIBLE'
zcat canton_before_shutdown.clog.gz | grep -acE 'SynchronizerUpgradeAnnouncement|upgradeTime|SequencerConnectionSuccessor'
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-10T12:5[5-9]' | grep -aoE 'ScalaTest-running-[A-Za-z0-9]+' | sort | uniq -c
```
```
0
0
    954 ScalaTest-running-ValidatorIntegrationTest
```

### 8b. The ack was accepted by globalSequencerSv1 and never answered

```
zcat canton_before_shutdown.clog.gz | grep -aE 'acknowledge-signed/2026-09-10T12:57:12.472124Z|AcknowledgeSigned by grpc' \
  | grep -aE '12:57:1[2-4]' | sed -E 's/.*"@timestamp":"([^"]*)".*"message":"([^"]{0,120}).*"logger_name":"([^"]{0,60}).*/\1 \3 :: \2/'
```
```
2026-09-10T12:57:14.480Z c.d.c.s.c.p.GrpcConnection:mediator=globalMediatorSv1/psid=g :: Sending request acknowledge-signed/2026-09-10T12:57:12.472124Z to server-DefaultSequencer-0.
2026-09-10T12:57:14.481Z c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1 :: Request ...SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:52576: received a message AcknowledgeSignedRequest(ByteString)
```
No "sending response" follows for this request until the client deadline (section 4). The next successful
AcknowledgeSigned response on globalSequencerSv1 is at 12:58:25.195Z (splitwellParticipant's ack).

### 8c. Epoch 26 (12:57:13.551) is the first epoch with sv2, sv3, sv4 as BFT leaders

```
zcat canton_before_shutdown.clog.gz | grep -aE 'sequencer=globalSequencerSv1' | grep -aE 'New epoch (2[4-9]|30) has started' \
  | sed -E 's/.*"@timestamp":"([^"]*)".*"message":"New epoch ([0-9]+) has started with leaders = (List\([^)]*\))and blacklisted nodes = (List\([^)]*\)).*/\1 epoch \2 leaders=\3 blacklisted=\4/' \
  | sed -E "$H" | sed -E 's/SEQ::(sv[0-9])::<HASH>/\1/g'
zcat canton_before_shutdown.clog.gz | grep -aE 'sequencer=globalSequencerSv[234]' | grep -aE 'Creating sequencer factory with BftSequencer' \
  | sed -E 's/.*"@timestamp":"([^"]*)".*sequencer=(globalSequencerSv[0-9]).*/\1 \2/'
```
```
2026-09-10T12:56:58.191Z epoch 24 leaders=List(sv1) blacklisted=List()
2026-09-10T12:57:06.017Z epoch 25 leaders=List(sv1) blacklisted=List()
2026-09-10T12:57:13.551Z epoch 26 leaders=List(sv3, sv4, sv1, sv2) blacklisted=List()
2026-09-10T12:57:36.387Z epoch 27 leaders=List(sv2, sv3, sv4) blacklisted=List(sv1)
2026-09-10T12:57:53.214Z epoch 28 leaders=List(sv3, sv4, sv2) blacklisted=List(sv1)
2026-09-10T12:58:10.891Z epoch 29 leaders=List(sv4, sv2, sv3) blacklisted=List(sv1)
2026-09-10T12:58:24.852Z epoch 30 leaders=List(sv3, sv4, sv1, sv2) blacklisted=List()
2026-09-10T12:57:14.190Z globalSequencerSv3
2026-09-10T12:57:16.610Z globalSequencerSv4
2026-09-10T12:57:17.746Z globalSequencerSv2
```
Until epoch 25 sv1 was the only BFT node (this is the SV onboarding phase of the test environment). At
epoch 26 the ordering topology jumped from 1 to 4 members in one step, while the three new BFT sequencer
instances were only being created (12:57:14-17).

### 8d. sv1 could not disseminate (quorum 2, only itself authenticated), then got blacklisted for 3 epochs

```
zcat canton_before_shutdown.clog.gz | grep -aE "request current time' was not successful" | head -1 \
  | grep -aoE '"@timestamp":"[^"]*"|SEQUENCER_OVERLOADED[^\\]{0,120}'
zcat canton_before_shutdown.clog.gz | grep -aE 'P2P connectivity is not ready' | grep -aoE '"@timestamp":"[^"]*"' | sed -n '1p;$p'
zcat canton_before_shutdown.clog.gz | grep -acE 'P2P connectivity is not ready'
zcat canton_before_shutdown.clog.gz | grep -aE 'currently blacklisted' | grep -aoE '"@timestamp":"[^"]*"' | sed -n '1p;$p'
zcat canton_before_shutdown.clog.gz | grep -acE 'currently blacklisted'
zcat canton_before_shutdown.clog.gz | grep -acE "request current time' was not successful"
```
```
"@timestamp":"2026-09-10T12:57:13.840Z"
SEQUENCER_OVERLOADED(2,b605e49c): P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting
"@timestamp":"2026-09-10T12:57:13.793Z"
"@timestamp":"2026-09-10T12:57:16.095Z"
92
"@timestamp":"2026-09-10T12:57:37.103Z"
"@timestamp":"2026-09-10T12:58:23.822Z"
608
143
```
Timeline on globalSequencerSv1:
- 12:57:13.55 epoch 26 starts with 4 leaders; 12:57:13.79-16.10 its own submissions (including the
  sequencer's periodic "request current time") are refused 92 times with "P2P connectivity is not ready
  (authenticated = 1 < dissemination quorum = 2)": the new peers exist in the topology but have not
  authenticated yet.
- 12:57:14.48 the mediator's AcknowledgeSigned arrives. Answering it needs the sequencer to have
  progressed past the ack's sequencing time, which needs ordering, which sv1 cannot get.
- 12:57:36 epoch 27: sv1 is blacklisted by the leader-selection policy for missing its epoch-26 slots;
  it stays blacklisted through epochs 28 and 29 (608 "Mempool received client request but this node is
  currently blacklisted" refusals, 12:57:37-12:58:23; 143 "request current time" retries 12:57:13-12:58:23).
- 12:58:24.85 epoch 30: sv1 is a leader again; 12:58:25.20 first AcknowledgeSigned answered again.
- 12:59:14.48 the mediator's 120 s client deadline expires on the ack from 12:57:14; the RPC is
  cancelled server-side and the two WARNs of sections 4 and 5 are logged. The pending ack was never
  answered even after recovery.

### 8e. Verdict

Root cause: a 1 -> 4 step of the BFT ordering topology during SV onboarding, with the three new
sequencers created 0.6-4.2 s after the epoch that already listed them as leaders. sv1, the only sequencer
the mediator was connected to, was without dissemination quorum for ~3 s and then blacklisted for ~48 s,
so it could not answer acks for ~70 s, and the ack that arrived in that window was never answered at all.
Same family as 10048 and 10137 (BFT onboarding membership steps with not-yet-ready newcomers), a milder
outcome (self-healed at epoch 30 instead of a permanent wedge).

canton#33616 addresses the LSU lower-bound variant of "ack accepted but not answered". If that fix also
stops AcknowledgeSigned from blocking on traffic-state availability in general, it removes the WARN here
too, but the underlying quorum loss during onboarding remains and belongs with the 10048/10137 family.
Splice-side mitigations are the ones already listed in 10137: serialize SV sequencer onboardings, or
release onboarding only when the newcomer is P2P-authenticated.
