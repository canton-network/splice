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
