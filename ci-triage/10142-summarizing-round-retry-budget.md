# 10142 - SummarizingMiningRoundTrigger ERROR after retry budget exhausted while the test paused verdict ingestion (run 35072334729)

Post-merge CI on main, sha 0c43730f70, 2026-09-16T08:10Z. Canton runtime 3.6.0-snapshot.20260910.
One failed job. All 12 tests in the shard passed; the job fails in sbt `checkErrors` on ONE ERROR line.
Commands verified against the downloaded artifacts.

## Categorization

- Test(s) affected: `TrafficBasedRewardsTimeBasedIntegrationTest` ("CIP-104 reward accounting pipeline works"),
  shard `ci / scala_test_sim_time / simtime (3)`. The test itself PASSED.
- Failure type: checkErrors ERROR in `log/canton_network_test.clog` (splice app log, not a Canton node log).
- Component: SV app, `SummarizingMiningRoundTrigger` (apps/sv) + generic `TaskbasedTrigger` (apps/common);
  Scan reward-accounting pipeline (`RewardComputationTrigger`, verdict ingestion).
- Flake vs real: flake in the sense of "no product misbehaviour"; deterministic mechanism (test-controlled
  ingestion pause vs. a bounded retry budget) whose outcome depends on wall-clock test duration.
- Duplicate of 10121: YES (same suite, SV, trigger, round, message, code path). See "Duplicates / related".

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35072334729 --repo canton-network/splice -n logs-simtime-3 -D dl
cd dl
# canton_network_test.clog.gz = test harness + splice app log (the flagged file)
# canton-simtime_before_shutdown.clog.gz = Canton node log
# job-104716731789.log = gh api repos/canton-network/splice/actions/jobs/104716731789/logs
```

## 1. Failed job and the checkErrors verdict

```
grep -aE 'Found problems|Total: [0-9]+ lines|\[error\] \(checkErrors\)|Process completed with exit code' \
  job-104716731789.log | sed -E 's/^[0-9T:.Z-]+ //'
```
```
Total: 575 lines with ignored entries.
Total: 1998 lines with stack traces.
Total: 0 lines with stack traces.
Total: 36 lines with ignored entries.
Found problems in log/canton_network_test.clog:
Total: 1 lines with problems.
[error] (checkErrors) log/canton_network_test.clog contains problems.
##[error]Process completed with exit code 1.
```
Job 104716731789 `ci / scala_test_sim_time / simtime (3)`. Exactly one problem line, in the splice app log.

## 2. Not a ScalaTest/assertion failure

```
grep -aE 'Tests: succeeded [0-9]|All tests passed' job-104716731789.log | sed -E 's/^[0-9T:.Z-]+ //'
zcat canton_network_test.clog.gz | grep -acE 'Test failed:|\*\*\* FAILED|RUN ABORTED|Condition never became true'
```
```
[info] Tests: succeeded 12, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
0
```

## 3. Canton runtime version

```
zcat canton-simtime_before_shutdown.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
```
```
Canton version 3.6.0-snapshot.20260910.20260.0.v90621933
```
(Canton is not involved in the failure; the flagged line is a splice app log line.)

## 4. The flagged line

```
zcat canton_network_test.clog.gz | grep -a 'Skipping processing of' \
  | grep -aoE '"@timestamp":"[^"]*"|"logger_name":"[^"]*"|"thread_name":"[^"]*"|"level":"[^"]*"'
```
```
"@timestamp":"2026-09-16T08:25:03.902Z"
"logger_name":"o.l.s.s.a.c.SummarizingMiningRoundTrigger:TrafficBasedRewardsTimeBasedIntegrationTest/config=82fbcfad/SV=sv1"
"thread_name":"TrafficBasedRewardsTimeBasedIntegrationTest-82fbcfad-env-ec-876"
"level":"ERROR"
```
```
zcat canton_network_test.clog.gz | grep -a 'Skipping processing of' | sed -E 's/[0-9a-f]{16,}/<HASH>/g' \
  | grep -aoE 'Skipping processing of|templateId = [^,]*|contractId = <HASH>|due to unexpected failure|"stack_trace":"[^\\]*\\n\\tat [^\\]*\\n\\tat [^\\]*\\n\\tat [^\\]*'
```
```
Skipping processing of
contractId = <HASH>
templateId = 8fe7573f5535...:Splice.Round:SummarizingMiningRound
due to unexpected failure
"stack_trace":"io.grpc.StatusRuntimeException: FAILED_PRECONDITION: For round 3: our own Scan has not yet computed the reward accounting totals.
	at io.grpc.Status.asRuntimeException(Status.java:524)
	at org.lfdecentralizedtrust.splice.sv.automation.confirmation.SummarizingMiningRoundTrigger.totalsUnavailable$1(SummarizingMiningRoundTrigger.scala:234)
	at org.lfdecentralizedtrust.splice.sv.automation.confirmation.SummarizingMiningRoundTrigger.$anonfun$fetchRewardAccountingTotals$4(SummarizingMiningRoundTrigger.scala:260)
```
Level ERROR, logger `SummarizingMiningRoundTrigger`, SV=sv1, suite `TrafficBasedRewardsTimeBasedIntegrationTest`
(config 82fbcfad), task = `SummarizingMiningRound` for round 3, exception text identical to 10121:
"For round 3: our own Scan has not yet computed the reward accounting totals." thrown at
SummarizingMiningRoundTrigger.scala:234 from the `RewardAccountingActivityTotalsUndetermined` arm (:260).

## 5. The ERROR is the give-up after 35 retries on that one task (~114 s)

```
zcat canton_network_test.clog.gz | grep -a 'SummarizingMiningRoundTrigger:TrafficBasedRewardsTimeBasedIntegrationTest' \
  | grep -a '003e6a9995ed262fc04f56dba2a1170e48fdf3780e603951f25f89f48012806ee9ca' \
  | grep -aE '"message":"(Processing|Completed|Skipping)' | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,40}' | paste - -
```
```
"@timestamp":"2026-09-16T08:23:09.993Z"	"message":"Processing\nTask(\n  summarizingRound =
"@timestamp":"2026-09-16T08:25:03.902Z"	"message":"Skipping processing of \nTask(\n  summar
"@timestamp":"2026-09-16T08:25:04.920Z"	"message":"Processing\nTask(\n  summarizingRound =
```
```
zcat canton_network_test.clog.gz | grep -a 'SummarizingMiningRoundTrigger:TrafficBasedRewardsTimeBasedIntegrationTest' \
  | grep -ac 'failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: For round 3:'
zcat canton_network_test.clog.gz | grep -a 'SummarizingMiningRoundTrigger:TrafficBasedRewardsTimeBasedIntegrationTest' \
  | grep -aE 'number of (0|1|34) failures|Giving up' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,150}' | paste - - | cut -c1-230 | head -6
```
```
39
"@timestamp":"2026-09-16T08:22:45.564Z"	"message":"The operation 'processTaskWithRetry' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a num
"@timestamp":"2026-09-16T08:22:58.864Z"	"message":"The operation 'processTaskWithRetry' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a num
"@timestamp":"2026-09-16T08:23:09.999Z"	"message":"The operation 'processTaskWithRetry' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a num
"@timestamp":"2026-09-16T08:23:10.206Z"	"message":"The operation 'processTaskWithRetry' has failed with an exception. Retrying after a number of 1 failures, and after 255 milliseconds. 
"@timestamp":"2026-09-16T08:25:00.834Z"	"message":"The operation 'processTaskWithRetry' has failed with an exception. Retrying after a number of 34 failures, and after 3062 milliseconds. 
"@timestamp":"2026-09-16T08:25:03.900Z"	"message":"The operation 'processTaskWithRetry' has failed with an exception. Total maximum number of retries 35 exceeded. Giving up. 
```
Round 3's task was first processed at 08:23:09.993 and failed at 08:23:09.999 with the retryable
FAILED_PRECONDITION. The retry loop (`RetryFor.Automation`: maxRetries=35, initialDelay 200ms, maxDelay 5s,
see section 10) retried 35 times with backoff, gave up at 08:25:03.900, and 2 ms later the generic
`Failure(ex)` branch of `TaskbasedTrigger` logged the ERROR. Rounds 0 and 2 (08:22:45, 08:22:58) hit the same
FAILED_PRECONDITION once each and succeeded on the first retry; round 3 is the only one whose budget ran out.

## 6. It self-resolved on the very next poll

```
zcat canton_network_test.clog.gz | grep -a 'SummarizingMiningRoundTrigger:TrafficBasedRewardsTimeBasedIntegrationTest' \
  | grep -aE 'Completed processing|Skipping processing' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,100}|"level":"[^"]*"' | paste - - - | cut -c1-200
```
```
"@timestamp":"2026-09-16T08:22:46.160Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 0	"level":"INFO"
"@timestamp":"2026-09-16T08:22:53.811Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 1	"level":"INFO"
"@timestamp":"2026-09-16T08:22:59.433Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 2	"level":"INFO"
"@timestamp":"2026-09-16T08:25:03.902Z"	"message":"Skipping processing of \nTask(\n  summarizingRound = AssignedContract(\n    contract = Contract(\n      contractId = <HA	"level":"ERROR"
"@timestamp":"2026-09-16T08:25:08.929Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 3	"level":"INFO"
"@timestamp":"2026-09-16T08:25:08.943Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 5	"level":"INFO"
"@timestamp":"2026-09-16T08:25:11.191Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 4	"level":"INFO"
"@timestamp":"2026-09-16T08:25:11.202Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 7	"level":"INFO"
"@timestamp":"2026-09-16T08:25:11.217Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 8	"level":"INFO"
"@timestamp":"2026-09-16T08:25:12.775Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 6	"level":"INFO"
"@timestamp":"2026-09-16T08:25:13.001Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 9	"level":"INFO"
"@timestamp":"2026-09-16T08:25:13.005Z"	"message":"Completed processing with outcome: created confirmation for summarizing mining round 10	"level":"INFO"
```
The same contract id (section 5) was re-picked at 08:25:04.920 (trigger polling interval 1 s) and succeeded at
08:25:08.929, 5 s after the ERROR. Rounds 4..10, which had queued up behind round 3, all completed by 08:25:13.

## 7. Scan side: the totals for round 3 were computed at 08:25:07, not before

```
zcat canton_network_test.clog.gz | grep -a 'RewardComputationTrigger:TrafficBasedRewardsTimeBasedIntegrationTest' \
  | grep -aE 'Completed processing' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,75}' | paste - - | cut -c1-140
```
```
"@timestamp":"2026-09-16T08:22:45.645Z"	"message":"Completed processing with outcome: Computed rewards for round 0: 0 active parties,
"@timestamp":"2026-09-16T08:22:52.894Z"	"message":"Completed processing with outcome: Computed rewards for round 1: 0 active parties,
"@timestamp":"2026-09-16T08:22:58.918Z"	"message":"Completed processing with outcome: Computed rewards for round 2: 0 active parties,
"@timestamp":"2026-09-16T08:25:07.265Z"	"message":"Completed processing with outcome: Computed rewards for round 4: 0 active parties,
"@timestamp":"2026-09-16T08:25:07.266Z"	"message":"Completed processing with outcome: Computed rewards for round 3: 0 active parties,
"@timestamp":"2026-09-16T08:25:07.328Z"	"message":"Completed processing with outcome: Computed rewards for round 6: 2 active parties,
"@timestamp":"2026-09-16T08:25:07.328Z"	"message":"Completed processing with outcome: Computed rewards for round 7: 2 active parties,
"@timestamp":"2026-09-16T08:25:07.328Z"	"message":"Completed processing with outcome: Computed rewards for round 8: 0 active parties,
"@timestamp":"2026-09-16T08:25:07.328Z"	"message":"Completed processing with outcome: Computed rewards for round 5: 1 active parties,
"@timestamp":"2026-09-16T08:25:07.351Z"	"message":"Completed processing with outcome: Computed rewards for round 9: 2 active parties,
"@timestamp":"2026-09-16T08:25:07.351Z"	"message":"Completed processing with outcome: Computed rewards for round 10: 2 active parties
```
```
zcat canton_network_test.clog.gz | grep -a 'reward-accounting-process/rounds/3/activity-totals' | grep -a 'config=82fbcfad' \
  | grep -ac .
zcat canton_network_test.clog.gz | grep -a 'reward-accounting-process/rounds/3/activity-totals' | grep -a 'config=82fbcfad' \
  | grep -aoE '"@timestamp":"[^"]*"' | sed -n '1p;$p'
```
```
291
"@timestamp":"2026-09-16T08:23:09.995Z"
"@timestamp":"2026-09-16T08:25:07.570Z"
```
sv1Scan computed rounds 0, 1, 2 within a second of each round's close, then nothing for two minutes, then
rounds 3..10 all at 08:25:07.2. sv1 polled `GET /v0/internal/reward-accounting-process/rounds/3/activity-totals`
291 times (request + response log lines) between 08:23:09.995 and 08:25:07.570, getting `Undetermined` until
the totals appeared. The SV gave up at 08:25:03.900, 3.4 s before the Scan computed the totals.

## 8. Why the Scan could not compute: the TEST paused sv1Scan's verdict ingestion for 123 s

```
zcat canton_network_test.clog.gz | grep -aE '"logger_name":"[^"]*TrafficBasedRewardsTimeBasedIntegrationTest"' \
  | grep -avE '"level":"(DEBUG|TRACE)"' \
  | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1]>="2026-09-16T08:22:55" && a[1]<="2026-09-16T08:25:10") print}' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,90}' | paste - - | cut -c1-160
```
```
"@timestamp":"2026-09-16T08:22:57.305Z"	"message":"advancing time by PT10M10S to 1970-01-01T00:30:30Z
"@timestamp":"2026-09-16T08:23:02.523Z"	"message":"Pausing verdict ingestion for sv1Scan
"@timestamp":"2026-09-16T08:23:07.952Z"	"message":"advancing time by PT10M10S to 1970-01-01T00:40:40Z
"@timestamp":"2026-09-16T08:23:15.235Z"	"message":"advancing time by PT10M10S to 1970-01-01T00:50:50Z
"@timestamp":"2026-09-16T08:23:26.305Z"	"message":"advancing time by PT10M10S to 1970-01-01T01:01:00Z
"@timestamp":"2026-09-16T08:23:48.553Z"	"message":"advancing time by PT10M10S to 1970-01-01T01:11:10Z
"@timestamp":"2026-09-16T08:24:10.138Z"	"message":"advancing time by PT6M to 1970-01-01T01:17:10Z
"@timestamp":"2026-09-16T08:24:14.709Z"	"message":"advancing time by PT4M10S to 1970-01-01T01:21:20Z
"@timestamp":"2026-09-16T08:24:18.836Z"	"message":"advancing time by PT10M10S to 1970-01-01T01:31:30Z
"@timestamp":"2026-09-16T08:24:25.414Z"	"message":"Suppressed ERROR: Failed clue: Alice sees the allocation request
"@timestamp":"2026-09-16T08:24:33.111Z"	"message":"advancing time by PT10M10S to 1970-01-01T01:41:40Z
"@timestamp":"2026-09-16T08:24:55.280Z"	"message":"advancing time by PT10M10S to 1970-01-01T01:51:50Z
"@timestamp":"2026-09-16T08:25:03.014Z"	"message":"Suppressed ERROR: Failed clue: Alice sees the allocation request
"@timestamp":"2026-09-16T08:25:05.967Z"	"message":"Resuming verdict ingestion for sv1Scan
"@timestamp":"2026-09-16T08:25:07.623Z"	"message":"Round 6 minting: alice__wallet__user-82fbcfad=32353.4694000000, splitwell__provider-82fbcfad=1591
```
```
zcat canton_network_test.clog.gz | grep -a 'ScanVerdictIngestionService:TrafficBasedRewardsTimeBasedIntegrationTest' \
  | grep -aE 'Pausing|Resuming|Inserted' \
  | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1]>="2026-09-16T08:22:55" && a[1]<="2026-09-16T08:25:15") print}' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,60}' | paste - - | grep -B1 -A1 -E 'Pausing|Resuming'
```
```
"@timestamp":"2026-09-16T08:23:00.647Z"	"message":"Inserted 1 verdicts, 1 traffic summaries, 0 app activity rec
"@timestamp":"2026-09-16T08:23:02.524Z"	"message":"Pausing verdict ingestion.
"@timestamp":"2026-09-16T08:25:05.968Z"	"message":"Resuming verdict ingestion.
"@timestamp":"2026-09-16T08:25:06.023Z"	"message":"Inserted 1 verdicts, 1 traffic summaries, 0 app activity rec
```
```
zcat canton_network_test.clog.gz | grep -a 'AdvanceOpenMiningRoundTrigger:TrafficBasedRewardsTimeBasedIntegrationTest' \
  | grep -a 'archived round 3' | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,90}' | paste - -
```
```
"@timestamp":"2026-09-16T08:23:09.640Z"	"message":"Completed processing with outcome: successfully advanced the rounds and archived round 3
```
The test's step "Pausing verdict ingestion for sv1Scan" at 08:23:02.523 stopped sv1Scan's verdict stream
(no `Inserted ...` between 08:23:00.647 and 08:25:06.023). Round 3 was archived (its `SummarizingMiningRound`
created) at 08:23:09.640, 7 s INTO the pause, so its totals could not be produced until the resume at
08:25:05.968. Everything the SV saw follows from that: 39 retryable failures, give-up at 08:25:03.900,
success at 08:25:08.929.

This pause is by design of the test (apps/app/src/test/scala/.../TrafficBasedRewardsTimeBasedIntegrationTest.scala
at 0c43730f70):

```
git show 0c43730f70:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsTimeBasedIntegrationTest.scala \
  | sed -n '161,163p;212,212p'
```
```
    // Settlements are performed with verdict ingestion paused to test
    // catch-up ingestion. The initial 3 bootstrap round advances are done
    // below with verdict ingestion active.
        pauseScanVerdictIngestionWithin(sv1ScanBackend) {
```
Inside that block the test performs settlements and eight sim-time advances (rounds 4..11 open, 3..10 close),
with `eventually` loops ("Suppressed ERROR: Failed clue: Alice sees the allocation request" x2). The block's
wall-clock duration is therefore variable; here it was 123.4 s.

## 9. Why a paused verdict stream makes the Scan answer Undetermined (code at 0c43730f70)

```
git show 0c43730f70:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/automation/RewardComputationTrigger.scala | sed -n '77,84p'
```
```
        earliestCompleteO <- appActivityStore.earliestRoundWithCompleteAppActivity()
        latestCompleteO <- appActivityStore.latestRoundWithCompleteAppActivity()
        eligible = (earliestCompleteO, latestCompleteO) match {
          case (Some(earliest), Some(latest)) =>
            afterComputedFilter.filter(r => r >= earliest && r <= latest)
          case _ => Seq.empty[Long]
        }
      } yield eligible.map(RewardComputationTrigger.Task(_))
```
```
git show 0c43730f70:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/db/DbAppActivityRecordStore.scala \
  | sed -n '229,230p;377,380p;452,454p'
```
```
      sql"""select m.last_archived_round
            from #${Tables.activityRecordMeta} m
      _ <- (ensureResult, lastArchivedRoundO) match {
        // We already have meta row, so do the update in place.
        case (Resume, Some(round)) => updateLastArchivedRoundDBIO(round)
        case _ => DBIO.successful(0)
  private def updateLastArchivedRoundDBIO(round: Long) =
    sql"""update #${Tables.activityRecordMeta}
          set last_archived_round = $round,
```
```
git show 0c43730f70:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/http/HttpScanHandler.scala | sed -n '3012,3016p'
git show 0c43730f70:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/db/DbAppActivityRecordStore.scala | sed -n '202,205p' | tr -d '\200-\377'
```
```
        case None =>
          appActivityStore.ingestionStatusForRound(roundNumber).map {
            case RoundIngestionStatus.CannotProvide => cannotProvide
            case RoundIngestionStatus.Undetermined => undetermined
          }
      case Some(_) =>
        // Meta row present but round is beyond our ingested boundary 
        // ingestion is still catching up; retry.
        RoundIngestionStatus.Undetermined
```
`RewardComputationTrigger` only computes round N once `latestRoundWithCompleteAppActivity`
(`activity_record_meta.last_archived_round`) >= N. That column is advanced only inside the verdict-ingestion
DB transaction (`insertAppActivityRecords...` -> `updateLastArchivedRoundDBIO`, called from
DbScanVerdictStore.scala:577). With verdict ingestion paused, `last_archived_round` stays at 2, round 3 is
"beyond our ingested boundary", and `getRewardAccountingActivityTotals(3)` answers `Undetermined` until
ingestion resumes and catches up. That is what happened: resume 08:25:05.968, catch-up `Inserted` 08:25:06.023,
rounds 3..10 computed 08:25:07.2.

## 10. Why the SV logs ERROR, and whether 2fb77e0be2 covers it (code at 0c43730f70)

```
git show 0c43730f70:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/confirmation/SummarizingMiningRoundTrigger.scala | sed -n '231,235p;254,262p'
```
```
  )(implicit tc: TraceContext): Future[definitions.RewardAccountingActivityTotalsOk] = {
    def totalsUnavailable(reason: String): Nothing =
      throw Status.FAILED_PRECONDITION
        .withDescription(s"For round $round: $reason")
        .asRuntimeException()
      ownScan <- scanConnectionF()
      response <- ownScan.getRewardAccountingActivityTotals(round)
      totals <- response match {
        case RewardAccountingActivityTotalsOk(ok) =>
          Future.successful(ok)
        case RewardAccountingActivityTotalsUndetermined(_) =>
          totalsUnavailable("our own Scan has not yet computed the reward accounting totals.")
        case RewardAccountingActivityTotalsCannotProvide(_) => bftReadTotals
      }
```
```
git show 0c43730f70:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/TaskbasedTrigger.scala | sed -n '52p;233p;243,246p'
git grep -n -A4 'val Automation' 0c43730f70 -- apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryFor.scala | sed -E 's/^[^ ]*[:-][0-9]+[:-]//'
```
```
  protected val taskRetry: RetryFor = RetryFor.Automation
          case Failure(ex) =>
              logger.error(
                show"Skipping processing of \n$task\ndue to unexpected failure",
                ex,
              )
  val Automation: RetryFor = RetryFor(
    maxRetries = 35,
    initialDelay = 200.millis,
    maxDelay = 5.seconds,
    resetRetriesAfter = None,
```
Path: `Undetermined` -> `totalsUnavailable` throws `FAILED_PRECONDITION` (:234, called from :260) ->
`processTaskWithRetry` treats it as retryable and retries under `RetryFor.Automation` (35 retries,
200 ms .. 5 s backoff; observed budget 08:23:09.999 -> 08:25:03.900 = 113.9 s) -> on exhaustion the generic
`case Failure(ex)` in TaskbasedTrigger.scala:233-246 logs at ERROR. The pause lasted 123.4 s > 113.9 s, so the
ERROR is the arithmetic consequence of the test's pause outlasting the retry budget by ~9.5 s.

The fix on branch `ray/fix-summarizing-round-log-noise`:

```
git show 2fb77e0be2 --stat --format='%h %s' | head -3
git show 2fb77e0be2 | grep -E '^[+-] ' | grep -vE '^\+\+\+|^---'
```
```
2fb77e0be2 [ci] SummarizingMiningRoundTrigger: no-op when own Scan totals not yet computed
 .../automation/confirmation/SummarizingMiningRoundTrigger.scala  | 9 ++++++---
+  TaskNoop,
+  private object OwnScanTotalsNotYetComputed extends RuntimeException
-    for {
+    (for {
-    } yield taskOutcome
+    } yield taskOutcome).recover { case OwnScanTotalsNotYetComputed => TaskNoop }
-          totalsUnavailable("our own Scan has not yet computed the reward accounting totals.")
+          Future.failed(OwnScanTotalsNotYetComputed)
```
Same code path, same arm. With the fix, the `Undetermined` arm no longer throws `FAILED_PRECONDITION`; it fails
the future with the private marker, which `completeTask` recovers to `TaskNoop` BEFORE `processTaskWithRetry`
sees anything. So: no retry loop, no budget to exhaust, no `Failure(ex)` branch, and `TaskNoop` is logged at
INFO (TaskbasedTrigger.scala:202). The trigger simply re-polls every second and succeeds when the Scan has the
totals (here 08:25:07+). The fix would have suppressed this exact ERROR line and also the 39 retry INFO lines.
The `CannotProvide` -> `bftReadTotals` arm is untouched by the fix and did not fire here.

## 11. Related but ignored context: 36 "Scan URLs disagreed with consensus" WARNs

```
zcat canton_network_test.clog.gz | grep -a 'Scan URLs disagreed with consensus' | grep -aoE '"@timestamp":"[^"]*"' | sed -n '1p;$p'
zcat canton_network_test.clog.gz | grep -a 'Scan URLs disagreed with consensus' \
  | grep -aoE '"logger_name":"o\.l\.s\.s\.a\.a\.c\.BftScanConnection:[A-Za-z]+/config=[a-f0-9]+/SV=sv[0-9]|"span-name":"[^"]*"' | sort | uniq -c
```
```
"@timestamp":"2026-09-16T08:33:55.706Z"
"@timestamp":"2026-09-16T08:34:15.307Z"
     12 "logger_name":"o.l.s.s.a.a.c.BftScanConnection:TimeBasedTestNetPreviewIntegrationTest/config=a3c1edba/SV=sv2
     12 "logger_name":"o.l.s.s.a.a.c.BftScanConnection:TimeBasedTestNetPreviewIntegrationTest/config=a3c1edba/SV=sv3
     12 "logger_name":"o.l.s.s.a.a.c.BftScanConnection:TimeBasedTestNetPreviewIntegrationTest/config=a3c1edba/SV=sv4
     18 "span-name":"CalculateRewardsTrigger"
     18 "span-name":"SummarizingMiningRoundTrigger"
```
These are the "Total: 36 lines with ignored entries" from section 1 (ignore pattern
project/ignore-patterns/canton_network_test_log.ignore.txt:99). They come from a different suite
(TimeBasedTestNetPreviewIntegrationTest, config a3c1edba, 08:33-08:34), are the BFT-read path of the same two
reward triggers, and did not contribute to the failure.

## Root cause / hypothesis

Proven from the artifacts:
- The only checkErrors problem is one ERROR from `SummarizingMiningRoundTrigger` (sv1, round 3) at
  08:25:03.902, emitted by `TaskbasedTrigger`'s generic `Failure(ex)` branch after `RetryFor.Automation`
  exhausted 35 retries (113.9 s) on `FAILED_PRECONDITION: our own Scan has not yet computed the reward
  accounting totals`.
- sv1Scan could not compute round 3's totals because the test paused sv1Scan's verdict ingestion from
  08:23:02.523 to 08:25:05.968 (123.4 s) and round 3 closed 7 s into that pause; the totals appeared 1.3 s after
  resume (08:25:07.266) and the SV succeeded on its next poll (08:25:08.929). The test passed.

Inferred (from code at 0c43730f70, consistent with every timestamp above):
- `last_archived_round` is only advanced inside the verdict-ingestion transaction, so a paused stream freezes
  `latestRoundWithCompleteAppActivity` at 2 and the handler's `ingestionStatusForRound(3)` returns
  `Undetermined`.
- Flakiness = wall-clock length of the `pauseScanVerdictIngestionWithin` block (settlements, eight round
  advances, `eventually` retries) vs. the fixed ~114 s retry budget. Runs where the block finishes under ~114 s
  produce only INFO retry lines; runs over it produce this ERROR. Here it was over by ~9.5 s.

## Duplicates / related

- 10121 (run 34612379425, simtime(3)): DUPLICATE, yes. Same suite (TrafficBasedRewardsTimeBasedIntegrationTest),
  same SV (sv1), same trigger, same round (3), same exception text, same stack (:234 <- :260), same ERROR site
  (TaskbasedTrigger.scala:243), same "tests all pass, checkErrors flags one line" shape. This packet adds the
  missing causal link for both: the ERROR requires the 35-retry budget to run out, and it runs out because the
  test itself holds sv1Scan's verdict ingestion paused for longer than that budget.
- 10094 / 10091, 10084: unrelated (Canton sequencer / indexer WARNs in the node log).
- The 36 ignored "Scan URLs disagreed" WARNs (section 11) are a different suite and already allowlisted.

## Suggested next step / owner

- Land the existing fix on `ray/fix-summarizing-round-log-noise` (2fb77e0be2). It covers this exact line: the
  `Undetermined` arm becomes `TaskNoop` (INFO) instead of a retried `FAILED_PRECONDITION`, so no ERROR regardless
  of how long the test keeps ingestion paused. Compile has not yet been verified in-sandbox (disk-limited).
- Alternative or complement on the test side: shorten or split the `pauseScanVerdictIngestionWithin` block, or
  pause sv1's `SummarizingMiningRoundTrigger` for its duration. The product fix is preferable; on a real network
  a Scan behind on verdict ingestion for >2 min is not an SV error condition either.
- Do NOT add an ignore pattern for "Skipping processing of ... SummarizingMiningRound": that line is the generic
  unexpected-failure sink for the trigger and would hide real failures.
- Owner: splice SV automation (Raymond has the fix branch).

## Summary

simtime(3), job 104716731789, canton 3.6.0-snapshot.20260910. All 12 tests passed; checkErrors flags one ERROR
in canton_network_test.clog: sv1's SummarizingMiningRoundTrigger "Skipping processing of Task(summarizingRound
= ... round 3) due to unexpected failure" at 08:25:03.902, with FAILED_PRECONDITION "our own Scan has not yet
computed the reward accounting totals". TrafficBasedRewardsTimeBasedIntegrationTest paused sv1Scan's verdict
ingestion 08:23:02.523 -> 08:25:05.968 (123.4 s); round 3 closed at 08:23:09.640 inside the pause, so the Scan
answered Undetermined for the whole window, the SV retried 35 times (113.9 s) and gave up 2 s before the resume,
then succeeded on the next poll at 08:25:08.929. Duplicate of 10121; the fix on
`ray/fix-summarizing-round-log-noise` (2fb77e0be2) removes this exact code path.
