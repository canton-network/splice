# 10176 - SvInitializationIntegrationTest teardown: pausing sv2Scan's AcsSnapshotTrigger times out after 5 s on a 10.7 s snapshot commit, the environment is never closed, and every later suite dies on the bound Prometheus port (run 35379257952)

New mechanism in the H3 family (trigger pause timeout at `TriggerTestUtil.scala:93`), with the teardown-leak cascade
last seen in July (SvOnboardingViaNonFoundingSvIntegrationTest, run 28921009132). `UpdateHistorySanityCheckPlugin
.beforeEnvironmentDestroyed` pauses the `AcsSnapshotTrigger` of every initialized scan with the 5 s default patience.
sv2Scan's trigger was inside an `UpdateIncrementalSnapshotTask` whose three SQL statements finished in 1 ms
(18:30:46.900 to 18:30:46.901) but whose transaction did not complete until 18:30:57.568, 10.67 s later. The
pause requested at 18:30:47.179 timed out at 18:30:52.182. `EnvironmentSetup.manualDestroyEnvironment` (vendored
canton, compiled into splice) runs the plugin hook outside its `try`, so `environment.close()` was skipped, the
apps of the old environment kept running (sv2Scan logs continue past 18:31:13) and kept port 25000, and the next
12 tests plus 2 whole suites failed at `Creating fixture` with `Could not create Prometheus HTTP server` / `Address
already in use`. Postgres commit latency on this runner was an outlier for the whole shard: p99 350 ms and six
commits over 1 s, against a 1 ms median and a 0.15 to 1.7 s maximum in three other shards.

- Run: https://github.com/canton-network/splice/actions/runs/35379257952, main 18f490ae5a ("[ci] Escape the
  parentheses in the ignore pattern for the LSU-source connection WARN so it matches", the 10174 fix), job
  105711633730 `ci / scala_test_wall_clock_time / wall-clock-time (5)`, runner `self-hosted-k8s-large-999lg-runner-jqm4d`.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824 (`git show 18f490ae5a:nix/canton-sources.json`).
- Component: test framework (splice test plugin patience; vendored canton `EnvironmentSetup` teardown order) plus
  runner infrastructure (Postgres commit latency).
- Only failed job of the run, so the ref maps to it without inference.

## 1. Classification: 13 tests failed, 2 suites aborted, no checkErrors involvement

```
gh api repos/canton-network/splice/actions/jobs/105711633730/logs > log/10176/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10176/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|ABORTED|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info] *** 13 TESTS FAILED ***
[info] *** 2 SUITES ABORTED ***
[info] - Amulet expiry ignores parties with no vetted amulet version *** FAILED ***
[info] - Cancel a DvP and its allocations *** FAILED ***
[info] - Reject an allocation request *** FAILED ***
[info] - SV apps can start one by one *** FAILED ***
[info] - SV automation reconcile amulet config change to domain parameter *** FAILED ***
[info] - SV can onboard when ACS includes splice-wallet contracts *** FAILED ***
[info] - Settle a DvP using allocations *** FAILED ***
[info] - The DSO is bootstrapped correctly *** FAILED ***
[info] - Withdraw an allocation *** FAILED ***
[info] - should be able to onboard a party with externally signed topology transactions *** FAILED ***
[info] - should be able to onboard an external party using the test python script *** FAILED ***
[info] - validator should bypass the proxy for hosts matched by http.nonProxyHosts *** FAILED ***
[info] - validator should start and tap, using http forward proxy *** FAILED ***
[info] Run completed in 7 minutes, 15 seconds.
[info] Tests: succeeded 3, failed 13, canceled 0, ignored 0, pending 0
[info] org.lfdecentralizedtrust.splice.integration.tests.BatchedFeaturedAppActivityMarkerIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTransferIntegrationTest *** ABORTED ***
```

Shard suites: BatchedFeaturedAppActivityMarkerIntegrationTest ExpiryWithNoVettedAmuletVersionIntegrationTest
ExternallySignedPartyOnboardingTest SvInitializationIntegrationTest SvOnboardingVettingIntegrationTest
SvReconcileSynchronizerConfigIntegrationTest TokenStandardAllocationIntegrationTest TokenStandardTransferIntegrationTest
ValidatorProxyIntegrationTest.

## 2. Two failure shapes: one pause timeout in teardown, then twelve identical fixture failures

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10176/job.log | grep -a -A14 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -a -E 'FAILED|Waited|Cause|at org.lfdecentralizedtrust|EnvironmentSetup' | sort | uniq -c | sort -rn | head -8
```
```
     12 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:132)
     12 [info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
      1 [info] - SV apps can start one by one *** FAILED ***
      1 [info]   A timeout occurred waiting for a future to complete. Waited 5 seconds. (TriggerTestUtil.scala:93)
```

The first failure's stack (teardown of the passing test body):
```
sed -E 's/\x1b\[[0-9;]*m//g' log/10176/job.log | grep -a -m1 -A26 'SV apps can start one by one \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | grep -a -E 'Waited|TriggerTestUtil|UpdateHistorySanityCheckPlugin|EnvironmentSetup|SvInitializationIntegrationTest.scala'
```
```
[info]   A timeout occurred waiting for a future to complete. Waited 5 seconds. (TriggerTestUtil.scala:93)
[info]   at org.lfdecentralizedtrust.splice.util.TriggerTestUtil$.$anonfun$setTriggersWithin$1(TriggerTestUtil.scala:93)
[info]   at org.lfdecentralizedtrust.splice.util.TriggerTestUtil$.$anonfun$setTriggersWithin$1$adapted(TriggerTestUtil.scala:93)
[info]   at org.lfdecentralizedtrust.splice.util.TriggerTestUtil$.setTriggersWithin(TriggerTestUtil.scala:93)
[info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.$anonfun$beforeEnvironmentDestroyed$1(UpdateHistorySanityCheckPlugin.scala:63)
[info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed(UpdateHistorySanityCheckPlugin.scala:49)
[info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed(UpdateHistorySanityCheckPlugin.scala:30)
[info]   at com.digitalasset.canton.integration.EnvironmentSetup.$anonfun$manualDestroyEnvironment$1(EnvironmentSetup.scala:282)
[info]   at com.digitalasset.canton.integration.EnvironmentSetup.manualDestroyEnvironment(EnvironmentSetup.scala:282)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SvInitializationIntegrationTest.manualDestroyEnvironment(SvInitializationIntegrationTest.scala:24)
[info]   at com.digitalasset.canton.integration.EnvironmentSetup.$anonfun$destroyEnvironment$1(EnvironmentSetup.scala:300)
```

The second shape, cause chain:
```
sed -E 's/\x1b\[[0-9;]*m//g' log/10176/job.log | grep -a -m1 -A40 'The DSO is bootstrapped correctly \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | grep -a -E 'Cause|PrometheusHttpServer|MetricsRegistry|SpliceEnvironment\.<init>|HTTPServer'
```
```
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
[info]   at io.opentelemetry.exporter.prometheus.PrometheusHttpServer.<init>(PrometheusHttpServer.java:109)
[info]   at io.opentelemetry.exporter.prometheus.PrometheusHttpServerBuilder.build(PrometheusHttpServerBuilder.java:186)
[info]   at com.digitalasset.canton.metrics.MetricsRegistry$.$anonfun$registerReporters$1(MetricsRegistry.scala:300)
[info]   at org.lfdecentralizedtrust.splice.environment.SpliceEnvironment.<init>(SpliceEnvironment.scala:35)
[info]   Cause: java.net.BindException: Address already in use
[info]   at io.prometheus.metrics.exporter.httpserver.HTTPServer$Builder.buildAndStart(HTTPServer.java:306)
```

The port is the test topology's single Prometheus reporter:
```
git show 18f490ae5a:apps/app/src/test/resources/simple-topology-1sv.conf | sed -n '7,16p'
```
```
canton.monitoring {
  metrics {
    jvm-metrics.enabled = no
    reporters = [{
      type = prometheus
      address = "0.0.0.0"
      port = 25000
    }]
  }
}
```

## 3. Suite timeline: the sanity check pause runs 18:30:47.179 to 18:30:52.182, then nothing is torn down

```
T=log/10176/logs-wall-clock-time-5/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Checking update histories|Pausing triggers for block|Resuming triggers after block" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-150 | sed -n '13,24p'
```
```
2026-09-18T18:28:53.970Z Starting 'SvInitializationIntegrationTest/SV apps can start one by one'...",
2026-09-18T18:30:47.179Z Checking update histories for List(sv2Scan, sv3Scan, sv1Scan, sv4Scan)",
2026-09-18T18:30:47.179Z Pausing triggers for block: List(org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTrigger@1f9ed4ff, org.lfdecentralizedtrust.splice.scan.a
2026-09-18T18:30:52.182Z Resuming triggers after block: List(org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTrigger@1f9ed4ff, org.lfdecentralizedtrust.splice.scan.a
2026-09-18T18:30:52.186Z Test failed: 'SvInitializationIntegrationTest/SV apps can start one by one', message: A timeout occurred waiting for a future to complete. Waited 5 seconds., location: SeeStac
2026-09-18T18:30:52.191Z Starting 'SvInitializationIntegrationTest/The DSO is bootstrapped correctly'...",
2026-09-18T18:30:52.203Z Test failed: 'SvInitializationIntegrationTest/The DSO is bootstrapped correctly', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:132), l
2026-09-18T18:30:52.242Z Starting test suite 'TokenStandardAllocationIntegrationTest'...",
2026-09-18T18:30:52.243Z Starting 'TokenStandardAllocationIntegrationTest/Settle a DvP using allocations'...",
2026-09-18T18:30:52.422Z Test failed: 'TokenStandardAllocationIntegrationTest/Settle a DvP using allocations', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:132
```

The same plugin ran three times earlier in the suite and paused the same four triggers in 0.09 to 0.63 s
(18:26:58.360 to .988, 18:27:36.607 to .698, 18:28:42.543 to .871), so the plugin logic is not the problem; the wait is.

## 4. Which trigger did not pause: sv2Scan, first in the list, mid-task

`PollingTrigger.pause()` sets the paused flag and returns the running task's completion promise; the plugin pauses the
scans in list order and blocks on each with `futureValue`:

```
git show 18f490ae5a:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/PollingTrigger.scala | sed -n '244,256p'
```
```
  override def pause(): Future[Unit] = {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      logger.debug("Pausing trigger.")
      blocking {
        mutex.exclusive {
          pausedVar = true
          runningTaskFinishedVar.fold(Future.unit)(_.future.map { _ =>
            logger.debug("Trigger completely paused.")
          })
        }
      }
    }
  }
```

Only sv2Scan got the pause request; the other three were never reached, and sv2Scan's "completely paused" arrives
5.4 s after the plugin gave up:
```
zcat $T | grep -a AcsSnapshotTrigger | grep -a -E 'Pausing trigger|completely paused|Resuming trigger' | grep -a -E 'T18:30:(4[7-9]|5)' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"([^"]*)".*scan=([a-z0-9]+)[^"]*".*/\1 \3 \2/'
```
```
2026-09-18T18:30:47.179Z sv2Scan Pausing trigger.
2026-09-18T18:30:52.182Z sv2Scan Resuming trigger.
2026-09-18T18:30:52.182Z sv3Scan Resuming trigger.
2026-09-18T18:30:52.182Z sv1Scan Resuming trigger.
2026-09-18T18:30:52.182Z sv4Scan Resuming trigger.
2026-09-18T18:30:57.568Z sv2Scan Trigger completely paused.
```

## 5. What the running task was waiting on: a 10.67 s transaction completion after 1 ms of SQL

```
zcat $T | grep -a 'scan=sv2Scan' | grep -a -E 'AcsSnapshotTrigger|AcsSnapshotStore' | grep -a -E 'T18:30:(4[6-9]|5[0-7])' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,110}).*"level":"([A-Z]+)".*/\1 \3 \2/; s/\\n/ /g; s/  +/ /g'
```
```
2026-09-18T18:30:46.900Z INFO Processing UpdateIncrementalSnapshotTask( snapshot = IncrementalAcsSnapshot( snapshotId = 12, historyId = 12, tableName = acs_incre
2026-09-18T18:30:46.900Z DEBUG Updating incremental snapshot 12 from 2026-09-18T18:30:40.196296Z to 2026-09-18T18:30:41.196296Z
2026-09-18T18:30:46.901Z INFO Updated incremental snapshot 12 from 2026-09-18T18:30:40.196296Z to 2026-09-18T18:30:41.196296Z. ACS: +3, -3.
2026-09-18T18:30:47.179Z DEBUG Pausing trigger.
2026-09-18T18:30:52.182Z DEBUG Resuming trigger.
2026-09-18T18:30:57.568Z INFO Completed processing with outcome: Updated incremental snapshot to 2026-09-18T18:30:41.196296Z
```

The "Updated" line is logged inside the transactional DBIO after its three statements and before the commit, so the
10.67 s sit between the last statement and the completion of `storage.queryAndUpdate(statement.transactionally, ...)`:
```
git show 18f490ae5a:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/AcsSnapshotStore.scala | sed -n '772,782p'
```
```
    } yield {
      logger.info(
        s"Updated incremental snapshot ${snapshot.snapshotId} from ${snapshot.recordTime} to $targetRecordTime. ACS: +$insertedRows, -$deletedRows."
      )
      ()
    }
    storage.queryAndUpdate(statement.transactionally, "updateIncrementalSnapshot")
  }
```

sv2Scan itself was alive throughout (HTTP requests answered, scan-list refresh every second, a ledger API call at
18:30:56.6), so this is not a JVM pause or a starved execution context; the other three scans were caught up and only
logged "would move past the last ingested record time" waits in that window (10, 11 and 10 lines), i.e. nobody else
committed a snapshot step during those 10 s:
```
zcat $T | grep -a 'Completed processing with outcome: Updated incremental snapshot' | grep -a -E '"@timestamp":"2026-09-18T18:30:(4[4-9]|5[0-8])' | sed -E 's/^\{"@timestamp":"2026-09-18T18:30:([0-9]{2})[^"]*".*scan=([a-z0-9]+)[^"]*".*/\1 \2/' | sort | uniq -c
```
```
      1 45 sv1
      1 45 sv4
      1 46 sv3
      1 57 sv2
```

## 6. Commit latency was an outlier for the whole shard, not just this transaction

Gap between the store's "Updated ..." line and the trigger's "Completed processing" line for every incremental
snapshot step (median is 1 ms everywhere):
```
for T in log/10176/logs-wall-clock-time-5 log/10010/logs-wall-clock-time-1 log/10153/logs-wall-clock-time-0 log/10154/logs-simtime-0; do zcat $T/canton_network_test.clog.gz | grep -a -E 'Updated incremental snapshot [0-9]+ from|Completed processing with outcome: Updated incremental snapshot to' | python3 gaps.py; done
# gaps.py pairs (scan, target record time) between the two lines and prints count, median, p99, max, >1s, >5s
```
```
10176 wall-clock-time (5):  commits=1406 median=0.001s p99=0.35s max=10.67s over1s=6 over5s=1
10010 wall-clock-time (1):  commits=1598 median=0.001s p99=0.02s max=1.66s over1s=2 over5s=0
10153 wall-clock-time (0):  commits=1731 median=0.002s p99=0.03s max=0.15s over1s=0 over5s=0
10154 simtime (0):          commits=75174 median=0.001s p99=0.01s max=0.13s over1s=0 over5s=0
```
The six slow commits in this shard, all before the failure:
```
2026-09-18T18:28:05.380Z -> 2026-09-18T18:28:08.087Z sv3 gap=2.71s
2026-09-18T18:28:17.076Z -> 2026-09-18T18:28:19.615Z sv4 gap=2.54s
2026-09-18T18:29:54.387Z -> 2026-09-18T18:29:55.435Z sv1 gap=1.05s
2026-09-18T18:30:42.166Z -> 2026-09-18T18:30:43.941Z sv2 gap=1.77s
2026-09-18T18:30:46.901Z -> 2026-09-18T18:30:57.568Z sv2 gap=10.67s
2026-09-18T18:31:09.312Z -> 2026-09-18T18:31:10.519Z sv4 gap=1.21s
```
The shard runs on `postgres:18` (`postgres_init_args: -c max_connections=16000`) on the same self-hosted k8s
runner as the JVM. No Postgres log is in the artifact, so whether the 10.7 s was a WAL fsync stall, a checkpoint or
node contention cannot be read from here; the canton nodes on the same Postgres show no WARN in the window.

## 7. Why one slow commit took twelve tests and two suites with it

The vendored test framework (compiled into splice as `canton-community-integration-testing`) calls the plugin hook
outside the `try` that closes the environment:
```
git show 18f490ae5a:canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala | sed -n '280,289p'
```
```
  protected def manualDestroyEnvironment(environment: BaseTestConsoleEnvironment[C, E]): Unit = {
    val config = environment.actualConfig
    plugins.foreach(_.beforeEnvironmentDestroyed(environment))
    try {
      environment.close()
    } finally {
      envDef.teardown(())
      plugins.foreach(_.afterEnvironmentDestroyed(config))
    }
  }
```
Canton main still has the same order (mirror state 2026-09-15.22, `community/integration-testing/.../EnvironmentSetup.scala:196-205`).
Evidence that the old environment was never closed: sv2Scan keeps logging snapshot steps for the same
`config=71221802` environment long after the failure:
```
zcat $T | grep -a 'scan=sv2Scan' | grep -a 'Completed processing with outcome: Updated incremental' | grep -a -E 'T18:31:1[0-3]' | sed -E 's/^\{"@timestamp":"([^"]+)".*(config=[0-9]+).*/\1 \2/' | head -3
```
```
2026-09-18T18:31:11.010Z config=71221802
2026-09-18T18:31:13.185Z config=71221802
2026-09-18T18:31:13.243Z config=71221802
```
The two suites after the last isolated-environment suite (TokenStandardTransferIntegrationTest,
BatchedFeaturedAppActivityMarkerIntegrationTest, shared environments) abort in their `beforeAll` for the same reason
and report zero tests.

## 8. The patience that fired

```
git show 18f490ae5a:canton/community/testing/src/main/scala/com/digitalasset/canton/BaseTest.scala | sed -n '67,73p'
git show 18f490ae5a:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/TriggerTestUtil.scala | sed -n '82,93p'
```
```
trait ScalaFuturesWithPatience extends ScalaFutures {
  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))
}
object TriggerTestUtil extends ScalaFuturesWithPatience with LazyLogging {
  def setTriggersWithin[T](
      triggersToPauseAtStart: Seq[Trigger] = Seq.empty,
      triggersToResumeAtStart: Seq[Trigger] = Seq.empty,
  )(codeBlock: => T): T = {
    try {
      logger.info(s"Pausing triggers for block: $triggersToPauseAtStart")
      logger.info(s"Resuming triggers for block: $triggersToResumeAtStart")
      triggersToPauseAtStart.foreach(_.pause().futureValue)
```

## Verdict

- Family H3 (trigger pause timeout at `TriggerTestUtil.scala:93`) with a new cause: not a retry loop inside the
  task (10175) but a single slow Postgres transaction completion (10.67 s) under an `UpdateIncrementalSnapshotTask`.
  Cascade shape is the July teardown leak (run 28921009132): a throwing `beforeEnvironmentDestroyed` skips
  `environment.close()`, the Prometheus reporter keeps :25000, all later suites fail at `Creating fixture`.
- Flake. Test body passed; the sanity check is a teardown add-on with a 5 s budget on a call that legitimately waits
  for an in-flight DB transaction. Not a duplicate of an open ref; 10175 is the closest sibling.
- Fix locations:
  1. Test-side (branch `ray/fix-10176-sanity-check-pause-timeout`, e59f6538c0 off main 18f490ae5a):
     `TriggerTestUtil.setTriggersWithin` takes a `pauseTimeout` (default unchanged, the 5 s patience) and
     `UpdateHistorySanityCheckPlugin` passes one minute for the snapshot-trigger pause. Two files, 6 lines.
     Verified: ASCII-only diff, `apps-app/Test/scalafmtCheck` (see README row). NOT compiled or run here.
  2. Canton-side (described, not written): `EnvironmentSetup.manualDestroyEnvironment` should run
     `plugins.foreach(_.beforeEnvironmentDestroyed(environment))` inside the `try`, or collect its failure and rethrow
     after `environment.close()`, so a failing teardown check fails one test instead of the rest of the shard. Same
     file in canton main, `community/integration-testing/.../EnvironmentSetup.scala`.
  3. Infra observation for the runner owners: Postgres commit completion on `self-hosted-k8s-large-999lg-runner-jqm4d`
     had p99 350 ms and a 10.7 s outlier during this shard, against 10 to 30 ms p99 elsewhere.
- Not verified: the Postgres-side cause of the 10.7 s (no DB log in the artifact); that the one-minute wait would
  have been enough on a worse runner (it covers every gap seen in the four shards by a factor of 5).
