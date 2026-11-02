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

## Re-triage 2026-09-22

Goal: close the open end of the 09-10 verdict (which fix, where, and whether it recurs). The job log and the
artifact were still downloadable and were re-read; the mechanism is now confirmed against the Canton bytecode and
source, and the WARN has not recurred in twelve days of main.

### 1. Where the WARN is decided: Canton retry utility, not the indexer

Source at the public canton mirror, state 2026-09-15.22 (`log/canton-mirror`, commit c548c9ba1):
```
git show HEAD:community/ledger/ledger-api-core/src/main/scala/com/digitalasset/canton/platform/indexer/IndexerState.scala | sed -n '246,286p'
git show HEAD:community/base/src/main/scala/com/digitalasset/canton/util/retry/Policy.scala | sed -n '239,245p;362p'
```
```
  def ensureNoProcessingForSynchronizer(synchronizerId: SynchronizerId): Future[Unit] =
    ...
        retry.Pause(logger = logger, operationName = s"Ensure no processing for synchronizer $synchronizerId",
                    maxRetries = Int.MaxValue, delay = 200.millis, hasSynchronizeWithClosing = HasSynchronizeWithClosing.NeverClosing)
          .applyFut(
            ... if (recoveringQueue.uncommittedQueueSnapshot ... exists { case u: SynchronizerUpdate => u.synchronizerId == synchronizerId ... })
                  Future.failed(new Exception(s"Still uncommitted activity for synchronizer $synchronizerId, waiting..."))
                else Future.unit,
            new retry.ExceptionRetryPolicy {
              override protected def determineExceptionErrorKind(exception: Throwable, logger: TracedLogger)(...) =
                if (exception.getMessage.contains("Still uncommitted")) retry.ErrorKind.NoSuccessErrorKind
                else retry.ErrorKind.FatalErrorKind
            })
Policy.scala:239   val level = retryLogLevel.getOrElse {
Policy.scala:242     retryable.retryLogLevel(outcome).getOrElse {
Policy.scala:243       if (totalMaxRetries == retry.Forever && totalRetries >= complainAfterRetries) Level.WARN
Policy.scala:245       else Level.INFO
Policy.scala:362   private[retry] val complainAfterRetries: Int = 10
```
So the level is, in order: the `retryLogLevel` passed to the `Pause` (not passed here), the policy's per-exception
`retryLogLevel` (the anonymous policy does not override it), and otherwise WARN once a Forever retry has retried
ten times. Ten retries at 200 ms is the 2.004 s INFO-to-WARN transition measured on 09-10.

Confirmed in the jars that ran and run now (the 20260909.20251 snapshot of the failing run is not cached; its
neighbours 20260910.20260 and the current main pin 20260916.20284 are, and the bytecode of this path is identical
in both):
```
J=log/canton-jars/canton-open-source-3.6.0-snapshot.20260916.20284.0.vf27c4824/lib/canton-open-source-*.jar
javap -p -c -constants -cp $J 'com.digitalasset.canton.util.retry.RetryWithDelay$' | grep -A14 'static {}' | grep -E 'bipush|putstatic'
javap -p -c -cp $J com.digitalasset.canton.util.retry.RetryWithDelay | grep -A14 'anonfun$retryWithDelay$7' | grep -E 'Forever|complainAfterRetries|Level\.(WARN|INFO)'
javap -p -c -cp $J 'com.digitalasset.canton.platform.indexer.IndexerState$$anon$2' | grep -E 'retryLogLevel|ldc'
```
```
      10: bipush        10
      12: putstatic     #19                 // Field complainAfterRetries:I
       7: invokevirtual #416                // Method com/digitalasset/canton/util/retry/package$.Forever:()I
      17: invokevirtual #424                // Method com/digitalasset/canton/util/retry/RetryWithDelay$.complainAfterRetries:()I
      23: getstatic     #430                // Field org/slf4j/event/Level.WARN:Lorg/slf4j/event/Level;
      27: getstatic     #433                // Field org/slf4j/event/Level.INFO:Lorg/slf4j/event/Level;
  public scala.Option<org.slf4j.event.Level> retryLogLevel(java.lang.Throwable);   -> ExceptionRetryPolicy.retryLogLevel$ (interface default, None)
       4: ldc           #71                 // String Still uncommitted
```
The per-exception `retryLogLevel` hook on `ExceptionRetryPolicy` exists in 3.5.17 as well (4 references in the
interface), so the fix below applies to every supported line.

### 2. No configuration reaches it

```
python3: scan the 259 participant/indexer/ledger-api config classes of the 20260916 jar for HOCON-style keys containing retry, complain, log-level, indexer, ensure, uncommitted, drain, reconnect
```
```
candidate keys: indexer, retry            (bare words only, no key for a retry log level or threshold)
classes referencing complainAfterRetries: RetryWithDelay$, RetryWithDelay   (private constant)
```
There is no splice-side setting for this; only a Canton code change or an ignore pattern can silence it.

### 3. What the drain waited on: the indexer's in-memory ledger end lagged 3.6 s behind the DB write

```
C=log/10084/logs-wall-clock-time-8/canton_before_shutdown.clog.gz
zcat $C | grep -a -E 'Ensure no processing for synchronizer|Computing starting points' | python3 drains.py   # pairs first retry with the next "Computing starting points" per participant
zcat $C | grep -a 'participant=sv3Participant' | awk -F'"' '$4>="2026-09-10T09:35:05.08" && $4<"2026-09-10T09:35:08.94"' | grep -a -E 'ParallelIndexerSubscription|InMemoryStateUpdater|Failed to process submission|Disconnect' | <ts logger message>
```
```
reconnect drains that needed at least one 200 ms retry: 1   (of 57 connects in the shard)
  2026-09-10T09:35:05.124 sv3Participant    3.81s WARN_lines=18
09:35:04.986  TransactionProcessor            Phase 1 completed: Submitting 3 envelopes ... (sv3's own submission)
09:35:05.090  ParallelIndexerSubscription     Storing at offset=605 SequencerIndexMoved(... sequencerTimestamp = 2026-09-10T09:35:04.100152Z)
09:35:05.091  SynchronizerConnectionsManager  Disconnecting from Synchronizer 'global'   (DistributedDomainIntegrationTest "SV onboarding on distributed domain", started 09:34:43.883)
09:35:05.092  TransactionProcessor            Failed to process submission due to shutdown
09:35:05.124  IndexerState                    first "Still uncommitted activity" retry (INFO)
09:35:05.153  ParallelIndexerSubscription     Post Processing end updated in IndexDB, updateOffset -> 605
09:35:07.128  IndexerState                    first WARN (retry 10)
09:35:08.787  InMemoryStateUpdater            Updated ledger end properties: LedgerEnd(lastOffset = 605, ...)
09:35:08.931  SyncEphemeralStateFactoryImpl   Computing starting points ...   (drain done, 3.81 s)
```
The uncommitted update was offset 605, a `SequencerIndexMoved` written to the IndexDB at 09:35:05.153; the
indexer's in-memory ledger end, which is what empties `uncommittedQueueSnapshot`, only advanced at 09:35:08.787.
The drain therefore waited on the indexer's own commit pipeline, not on the synchronizer, and the disconnect at
.091 returned without waiting for it because the indexer is asynchronous by design; `ensureNoProcessingForSynchronizer`
on the reconnect path is the mechanism that makes that safe, so the wait itself is correct behaviour. Why the
in-memory update took 3.6 s is not visible in the log (no indexer error, no DB retry on sv3Participant in that
window); it coincides with SV onboarding load on the shared Postgres.

### 4. Recurrence: one occurrence in twelve days of main

```
gh run list --repo canton-network/splice --branch main --created '2026-09-10..2026-09-15' ... and '>=2026-09-15' ... | select(.conclusion=="failure")
for each failed job: gh api repos/.../jobs/<id>/logs | grep -a '^\S*Z \*\*\*"@timestamp"' | grep -a -v 'ignore this line' | grep -a -c IndexerState
```
```
failed main runs 2026-09-10..09-15: 26 (control run 34458892258 present)   09-15..09-21: 33
runs with flagged IndexerState lines: 1  ->  34458892258 job 102812031882 IndexerState_flagged=18 (the original)
```
Local artifacts of 20 shards on 3.5.16 to 3.6.0-snapshot.20260916 (`log/*/logs-*/canton*.clog.gz`): IndexerState
INFO lines in every one (3 to 275 per shard), WARN lines in none. The drain crosses 2 s rarely.

### Verdict (replaces the 09-10 resolution)

- Mechanism confirmed against source and bytecode: `IndexerState.ensureNoProcessingForSynchronizer` retries at
  200 ms forever with no log level set, and `RetryWithDelay` promotes Forever retries to WARN after ten attempts,
  a global constant of 10. Same in the current main pin.
- Not a functional problem. The reconnect-path wait is correct; the WARN is the generic retry utility complaining
  about an expected wait that outlasts 2 s under load. Rare: 1 in 59 failed main runs in twelve days, 0 in 20 local
  shards, and the only slow drain in its own shard.
- Fix, Canton, one line, two equally valid sites in `IndexerState.scala`: pass `retryLogLevel = Some(Level.INFO)`
  to the `Pause` (line 251 at mirror state 2026-09-15.22), or override `retryLogLevel(t: Throwable)` in the anonymous
  policy to return `Some(Level.INFO)` when the message contains "Still uncommitted" (the hook exists in 3.5 and 3.6).
  The second keeps WARN for any unexpected exception, but unexpected exceptions are `FatalErrorKind` and stop the
  retry anyway, so the first is simpler and sufficient. A per-operation threshold instead of the global
  `complainAfterRetries = 10` would be the general fix and a larger change.
- Nothing splice-side: no configuration reaches this level. The 09-10 rejection of an ignore pattern stands as
  the owner's decision; for the record, the argument for one is now complete (a real indexer stall would surface as
  the reconnect never completing and the test timing out, so this WARN carries no signal the failure would not), and
  the argument against is that it also hides the first symptom of a slow indexer in production logs.
- Not verified: the exact 20260909.20251 jar (neighbours checked); why the in-memory ledger end lagged 3.6 s.
