# 10084 - IndexerState reconnect-wait WARNs (run 34458892258)

Post-merge main run (head 673c017d95). Canton 3.6.0-snapshot.20260909.20251.0.v0a9e6e25.
All tests pass; the job fails only because checkErrors flags 18 reconnect-drain WARNs. Not a functional
failure - a canton retry log-level threshold artifact under disconnect/reconnect load. Commands verified
against the artifact, streamed with zcat.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 34458892258 --repo canton-network/splice -n logs-wall-clock-time-8 -D wct8
cd wct8
# canton_before_shutdown.clog.gz = node log DURING the run; canton_network_test.clog.gz = test harness
```

## 1. Failed job

```
gh run view 34458892258 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
```
```
102812031882  ci / scala_test_wall_clock_time / wall-clock-time (8)
```

## 2. Canton version

```
zcat canton_before_shutdown.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
```
```
Canton version 3.6.0-snapshot.20260909.20251.0.v0a9e6e25
```

## 3. Tests all passed - not an assertion failure

```
zcat canton_network_test.clog.gz | grep -acE 'Test failed:|RUN ABORTED|SuiteAborted'
```
```
0
```

## 4. checkErrors is the only failure: 18 lines with problems

Job step console (`gh api repos/canton-network/splice/actions/jobs/102812031882/logs`):
```
Total: 18 lines with problems.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
##[error]Process completed with exit code 1.
```

## 5. The 18 flagged WARNs are all IndexerState reconnect-drain from sv3Participant

```
zcat canton_before_shutdown.clog.gz | grep -aE '"level":"WARN"' | grep -aE 'c\.d\.c\.p\.i\.IndexerState' \
  | grep -aoE '"logger_name":"[^"]*"' | sort | uniq -c
```
```
     18 "logger_name":"c.d.c.p.i.IndexerState:participant=sv3Participant"
```
```
zcat canton_before_shutdown.clog.gz | grep -aE '"level":"WARN"' | grep -aE 'c\.d\.c\.p\.i\.IndexerState' \
  | grep -aoE 'has failed with an exception. Retrying after 0.2s|Now retrying operation' | sort | uniq -c
```
```
      9 Now retrying operation
      9 has failed with an exception. Retrying after 0.2s
```
```
zcat canton_before_shutdown.clog.gz | grep -aE '"level":"WARN"' | grep -aE 'c\.d\.c\.p\.i\.IndexerState' \
  | grep -a 'Ensure no processing' | head -1 | sed -E 's/[0-9a-f]{20,}/<HASH>/g' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,140}|"logger_name":"[^"]*"'
```
```
"@timestamp":"2026-09-10T09:35:07.128Z"
"message":"The operation 'Ensure no processing for synchronizer global-domain::122020ff8755...' has failed with an exception. Retrying after 0.2s. 
"logger_name":"c.d.c.p.i.IndexerState:participant=sv3Participant"
```
9 WARN pairs (the "has failed ... Retrying" + its matching "Now retrying operation") = 18 flagged lines,
all from sv3Participant's IndexerState on the synchronizer-connect path.

## 6. It is a log-level threshold artifact: INFO for 2s, then WARN, then succeeds

sv3Participant disconnected first:
```
zcat canton_before_shutdown.clog.gz | grep -aE 'sv3Participant' | grep -aE 'Disconnected from synchroni' | grep -a '09:35:05' | head -1 | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,80}'
```
```
"@timestamp":"2026-09-10T09:35:05.091Z"
"message":"Refreshing state of connected-synchronizer from Ok() to Failed(Disconnected from synchroni
```

The connect path's `ensureNoProcessingForSynchronizer` then polls every 0.2s at INFO, and canton's retry
util escalates INFO -> WARN after ~2s (10 retries x 200ms):
```
zcat canton_before_shutdown.clog.gz | grep -aE 'c\.d\.c\.p\.i\.IndexerState:participant=sv3Participant' \
  | grep -a 'Ensure no processing' | grep -aoE '"@timestamp":"[^"]*"[^}]*"level":"[A-Z]+"' \
  | grep -aoE '"@timestamp":"[^"]*"|"level":"[A-Z]+"' | paste - - | head -6
```
```
"@timestamp":"2026-09-10T09:35:05.124Z"   "level":"INFO"
"@timestamp":"2026-09-10T09:35:05.324Z"   "level":"INFO"
"@timestamp":"2026-09-10T09:35:05.325Z"   "level":"INFO"
"@timestamp":"2026-09-10T09:35:05.525Z"   "level":"INFO"
"@timestamp":"2026-09-10T09:35:05.525Z"   "level":"INFO"
"@timestamp":"2026-09-10T09:35:05.725Z"   "level":"INFO"
```
First INFO 09:35:05.124, first WARN 09:35:07.128 = 2.004s. The drain then succeeds and the connect path
proceeds to "Computing starting points":
```
zcat canton_before_shutdown.clog.gz | grep -aE 'sv3Participant' | grep -a 'Computing starting points' | grep -a '09:35:08' | head -1 | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,60}'
```
```
"@timestamp":"2026-09-10T09:35:08.931Z"
"message":"Computing starting points for global-domain::122020ff8755...::35-0 wit
```
So the operation drained uncommitted activity in ~3.8s (05.124 -> 08.931) and succeeded - there is no
functional failure, only ~2s of WARN-level retry logging that checkErrors treats as problems.

## Mechanism (from the binary that ran; verify against the jar per reference-canton-vendored-not-what-runs)

- `IndexerState.ensureNoProcessingForSynchronizer` is called on every synchronizer connect (via
  `SyncEphemeralStateFactory.createFromPersistent`), retrying with `retry.Pause(maxRetries = Forever,
  delay = 200.millis)`, `retryLogLevel` left at default `None`.
- Canton's `RetryWithDelay` logs WARN once `totalRetries >= complainAfterRetries` for a `Forever` retry,
  and `complainAfterRetries = 10` (a global default, not tuned per op). 10 x 200ms = 2.0s -> the 2.004s
  observed INFO->WARN transition.
- The underlying transient is "Still uncommitted activity for synchronizer ..., waiting..."; under
  DistributedDomainIntegrationTest reconnect churn one drain took ~3.8s, crossing the 2s WARN threshold.

## Resolution

Not a functional bug; prod is not faster (a 3.8s drain WARNs there identically). A log-ignore workaround
was rejected (do not re-propose). Two candidate real fixes, neither started:
(a) canton passes `retryLogLevel = Some(INFO)` at that Pause call site (or a per-op threshold);
(b) find out why disconnect returns before the indexer drains, so the connect path does not wait on
    uncommitted activity in the first place.
Until one lands, main CI stays exposed in any shard where a participant reconnects under load.
