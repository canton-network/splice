# 10233 - sbt output check: Pekko "executeTask was rejected twice!" after TrafficBasedRewardsSvAppTimeBasedIntegrationTest teardown; a scan served a reward-accounting request after its DB storage closed and the query retried until the environment executor was gone (run 36536754273)

New failure, not in the catalogue. All tests passed; the job failed only on the sbt output check. At environment
teardown every app is stopped at once; sv1's `ProcessRewardsDryRunTrigger` was still running and called sv1Scan's
`/api/scan/v0/internal/reward-accounting-process/rounds/15/batches/<hash>`. sv1Scan's HTTP server accepted the
request although the ScanApp was already closing, because `NodeBootstrapBase.onClosed` closes the node (and its
DbStorage) before the HTTP binding. The handler's DB query hit the closed Slick executor, Canton's DbStorage
classified the `RejectedExecutionException` as transient with infinite retries, the HTTP binding waited out its
5 s hard deadline for the request, and the pending retry delay (`DelayUtil.delayIfNotClosing`) fired after the
environment's execution context had shut down; Pekko's dispatcher then printed the rejection to stdout, which the
sbt output check fails on. 10234 is the same chain on sv4Scan.

- Run: https://github.com/canton-network/splice/actions/runs/36536754273, main 9dd73aad3b ("Set high confirmation
  response rate limit defaults (#7499)"), post-merge CI, job 109302874217 `ci / scala_test_sim_time / simtime (2)`.
- Runtime canton: 3.6.0-snapshot.20260925.20321.0.vaecbf95c (`nix/canton-sources.json` at 9dd73aad3b).
- Component: splice app shutdown order (`apps/common/.../environment/NodeBootstrapBase.scala`), with a Canton-side
  contributor (DbStorage retrying against a closed storage).

## 1. Flagged lines: sbt output check, not checkErrors

The job summary has no failed test; the step fails on `check-sbt-output.sh`, which greps the sbt console for
error/warn/exception words minus `project/ignore-patterns/sbt-output.ignore.txt` (no pattern for this line).

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10233/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Found problems in|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
Found problems in the sbt output:
[info] All tests passed.
[info] Run completed in 15 minutes, 13 seconds.
[info] Tests: succeeded 10, failed 0, canceled 0, ignored 1, pending 0
```
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10233/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-600
grep -n -i -E 'reject|pekko|Dispatcher|delay-util' project/ignore-patterns/sbt-output.ignore.txt; echo "(exit $?)"
```
```
Found problems in the sbt output:
[ERROR] [09/29/2026 07:42:21.466] [delay-util-0] [org.apache.pekko.dispatch.Dispatcher] executeTask was rejected twice!
java.util.concurrent.RejectedExecutionException
[ERROR] [09/29/2026 07:42:21.467] [delay-util-0] [org.apache.pekko.dispatch.Dispatcher] null
java.util.concurrent.RejectedExecutionException
Total: 4 lines with problems.
(exit 1)
```

## 2. What fired: a Canton DelayUtil timer completing into a shut-down executor

The stack (Promise frames removed): a `delay-util` scheduler task from `DelayUtil.delayIfNotClosing` completes a
promise; its continuation goes to a Pekko dispatcher backed by a Canton `NamedExecutionContextExecutorService`
(ForkJoinPool) that rejects it.

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10233/job.log | awk '/executeTask was rejected twice/{p=1} /\[info\] - should handles/{p=0} p' \
  | grep -a -E 'ForkJoinPool.execute|NamedExecutionContextExecutorService.execute|Dispatcher.executeTask|DirectExecutionContext.execute|DelayUtil|ScheduledThreadPoolExecutor\$ScheduledFutureTask.run' | awk '!s[$0]++'
```
```
	at java.base/java.util.concurrent.ForkJoinPool.execute(ForkJoinPool.java:2847)
	at com.daml.executors.executors.NamedExecutionContextExecutorService.execute(NamedExecutionContextExecutorService.scala:60)
	at org.apache.pekko.dispatch.Dispatcher.executeTask(Dispatcher.scala:97)
	at com.digitalasset.canton.concurrent.DirectExecutionContext.execute(DirectExecutionContext.scala:23)
	at com.digitalasset.canton.util.DelayUtil$.$anonfun$delayIfNotClosing$1(DelayUtil.scala:100)
	at java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:304)
	at org.apache.pekko.dispatch.Dispatcher.executeTask(Dispatcher.scala:101)
```

(Source line numbers are from the stack trace of the running Canton 3.6.0-snapshot.20260925 jar, not `canton/`.)

## 3. Timeline: the flagged time is the end of TrafficBasedRewardsSvAppTimeBasedIntegrationTest's teardown

Save the filter below as `timeline.py` (prints scan-node close steps, the reward-accounting request, the retry
start and the environment close for one config id):

```
cat > timeline.py <<'EOF'
import sys, json, re
cfg, node, t0, t1 = sys.argv[1:5]
pats = [r"^Stopping node %s$" % node, r"Attempting to close 'org\.lfdecentralizedtrust\.splice\.scan\.ScanApp@",
        r"'db-storage' is now in state Failed", r"reward-accounting-process.*received request",
        r"lookupBatchByHash\.[a-z]+' has failed with an exception\. New kind", r"http binding admin service",
        r"still not completed after 4500", r"^Closing environment", r"^Completed test suite",
        r"retryWithDelay failed unexpectedly", r"^Processing\nTask\(round"]
for l in sys.stdin:
    try: j = json.loads(l)
    except Exception: continue
    ts, m, lg = j["@timestamp"], j["message"], j.get("logger_name", "")
    if not (t0 <= ts[11:23] <= t1): continue
    mine = ("config=%s/scan=%s" % (cfg, node)) in lg or lg.endswith("config=%s" % cfg) or "LogReporter" in lg \
        or ("ProcessRewardsDryRunTrigger" in lg and "config=%s" % cfg in lg)
    if mine and any(re.search(p, m) for p in pats):
        who = lg.split(":")[0].split(".")[-1] + ":" + lg.split("/")[-1]
        print(ts[11:23], who[:48], "|", m.replace("\n", " ")[:110])
EOF
zcat log/10233/logs-simtime-2/canton_network_test.clog.gz | python3 timeline.py f9dc5b29 sv1Scan 07:42:15.400 07:42:21.500 | awk '{k=$2 substr($0,index($0,"|"),60)} !s[k]++'
```
```
07:42:15.411 ProcessRewardsDryRunTrigger:SV=sv1 | Processing Task(round = 15, dryRun = true)
07:42:15.618 ScanApps:config=f9dc5b29 | Stopping node sv1Scan
07:42:15.618 ScanAppBootstrap:scan=sv1Scan | Attempting to close 'org.lfdecentralizedtrust.splice.scan.ScanApp@23f95af4'...
07:42:15.709 HttpRequestLogger:scan=sv1Scan | HTTP GET /api/scan/v0/internal/reward-accounting-process/rounds/15/batches/08170408a8c4127f8aaec4f6c1a90ab9ac5
07:42:15.711 DbStorageSingle:scan=sv1Scan | 'db-storage' is now in state Failed(Component is closed). Previous state was Ok().
07:42:15.715 HttpAdminService$HttpAdminServiceImpl:scan=sv1Sc | Attempting to close 'AsyncCloseable(name=http binding admin service)'...
07:42:15.788 DbStorageSingle:scan=sv1Scan | The operation 'appRewards.lookupBatchByHash.allowances' has failed with an exception. New kind of error: trans
07:42:20.201 ProcessRewardsDryRunTrigger:SV=sv1 | Task closing trigger polling loop still not completed after 4500 milliseconds. Continue waiting...
07:42:20.216 ScanAppBootstrap:scan=sv1Scan | Task closing http binding admin service still not completed after 4500 milliseconds. Continue waiting...
07:42:20.738 HttpAdminService$HttpAdminServiceImpl:scan=sv1Sc | Successfully closed 'AsyncCloseable(name=http binding admin service)'.
07:42:21.261 SpliceEnvironment:config=f9dc5b29 | Closing environment...
07:42:21.326 LogReporter:c.d.c.LogReporter:reporter=scala-tes | Completed test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'.
07:42:21.465 DbStorageSingle:scan=sv1Scan | retryWithDelay failed unexpectedly
```

Read top to bottom: teardown stops all apps at 15.618 (after the last test and the sanity-check plugins); sv1's
dry-run trigger task (started 15.411) sends its request, which sv1Scan's HTTP server accepts at 15.709 while the
ScanApp is closing; sv1Scan's DB storage is closed at 15.711, before the HTTP binding starts closing at 15.715; the
request's DB lookup fails and enters infinite transient retries at 15.788 (11 failures in total, backoff growing to
675 ms); the binding waits out its hard deadline and closes at 20.738, and sv1's trigger polling loop is stuck the
same 4.5 s on the HTTP call; the environment closes at 21.261 (the suite's `HasExecutorServiceGeneric` executor is closed at 21.324, see below); the last
retry delay, scheduled at 20.790 for 675 ms, fires at 21.465 into the closed executor: Canton logs
`retryWithDelay failed unexpectedly` and Pekko prints section 1's two lines (21.466, 21.467) to stdout.

The checkErrors step did not run (the sbt step had already failed), so the test-log ERROR was never checked; the
same message is ignored only for the Canton after-shutdown log half:

```
grep -a -A1 'start-action display=Check logs' log/10233/job.log | sed -E 's/^[^Z]*Z //' | head -2
zcat log/10233/logs-simtime-2/canton_network_test.clog.gz | grep -a -E 'T07:42:21\.32[45]' | grep -a 'HasExecutorServiceGeneric' | sed -E 's/"logger_name":"([^"]*)".*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c12-130
grep -n 'retryWithDelay failed unexpectedly' project/ignore-patterns/*.txt
```
```
##[start-action display=Check logs for errors;id=__self.__self_8]
##[end-action id=__self.__self_8;outcome=skipped;conclusion=skipped;duration_ms=0]
07:42:21.324Z Attempting to close 'com.digitalasset.canton.HasExecutorServiceGeneric$$Lambda/0x00007fff38635bb8@3e66607
07:42:21.325Z Successfully closed 'com.digitalasset.canton.HasExecutorServiceGeneric$$Lambda/0x00007fff38635bb8@3e66607
project/ignore-patterns/canton_log_shutdown_extra.ignore.txt:11:retryWithDelay failed unexpectedly
```

## 4. The request is the dry-run trigger's, by trace id

```
T=log/10233/logs-simtime-2/canton_network_test.clog.gz
TID=$(zcat $T | grep -a "scan=sv1Scan" | grep -a -m1 "lookupBatchByHash\.[a-z]*' has failed" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read())["trace-id"])'); echo "trace-id $TID"
zcat $T | grep -a "$TID" | python3 -c 'import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j["@timestamp"][11:23], j["logger_name"].split(":")[0].split(".")[-1]+":"+j["logger_name"].split("/")[-1], "|", j["message"].replace("\n"," ")[:90])' | head -3
```
```
trace-id 035391585c01cdab04e29ced53492796
07:42:15.411 ProcessRewardsDryRunTrigger:SV=sv1 | Processing Task(round = 15, dryRun = true)
07:42:15.708 SvApp:SV=sv1 | HTTP client (GET /api/scan/v0/internal/reward-accounting-process/rounds/15/batches/0817040
07:42:15.709 HttpRequestLogger:scan=sv1Scan | HTTP GET /api/scan/v0/internal/reward-accounting-process/rounds/15/batches/08170408a8c4127
```

## 5. Why the HTTP server still accepts requests: close order in NodeBootstrapBase

```
git show 9dd73aad3b:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/NodeBootstrapBase.scala | grep -n -A6 'override def onClosed'
git log origin/main --format='%h %ad %s' --date=short -S 'List(clock, httpAdminService)' -- apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/NodeBootstrapBase.scala
```
```
199:  override def onClosed(): Unit = blocking {
200-    synchronized {
201-      if (isRunningVar.getAndSet(false)) {
202-        val stores = List()
203-        val instances =
204-          grpcAdminServers ++ getNode.toList ++ stores ++ List(clock, httpAdminService)
205-        LifeCycle.close(instances*)(logger)
3052b21184 2024-10-18 Update Splice from CCI (#70)
```

`LifeCycle.close` closes in list order: the node (ScanApp, with its stores and DbStorage) goes before
`httpAdminService` (the Pekko HTTP binding, `terminate(hardDeadline = ...)`). Between the two, the server keeps
accepting requests whose handlers run against closed stores. The order is old (2024); the caller that hits the
window is newer (SV reward triggers #5552 2026-05-29, polling since #6130 2026-06-29).

## 6. 10234 (run 36541322662, main 95e122223c, job 109317557663 `simtime (2)`) - same chain on sv4Scan

Same flagged lines at `[09/29/2026 08:25:47.371]` / `.373` (`Total: 4 lines with problems.`, 11/11 tests passed),
same canton pin 3.6.0-snapshot.20260925.20321.0.vaecbf95c, same suite and last test. Here the request arrives even
after sv4Scan's DB storage is closed:

```
zcat log/10234/logs-simtime-2/canton_network_test.clog.gz | python3 timeline.py 179108e2 sv4Scan 08:25:41.300 08:25:47.400 | awk '{k=$2 substr($0,index($0,"|"),60)} !s[k]++' | grep -v 'SV=sv[123]'
```
```
08:25:41.480 ScanApps:config=179108e2 | Stopping node sv4Scan
08:25:41.480 ScanAppBootstrap:scan=sv4Scan | Attempting to close 'org.lfdecentralizedtrust.splice.scan.ScanApp@666d7b65'...
08:25:41.496 ProcessRewardsDryRunTrigger:SV=sv4 | Processing Task(round = 15, dryRun = true)
08:25:41.515 DbStorageSingle:scan=sv4Scan | 'db-storage' is now in state Failed(Component is closed). Previous state was Ok().
08:25:41.565 HttpRequestLogger:scan=sv4Scan | HTTP GET /api/scan/v0/internal/reward-accounting-process/rounds/15/batches/08170408a8c4127f8aaec4f6c1a90ab9ac5
08:25:41.567 HttpAdminService$HttpAdminServiceImpl:scan=sv4Sc | Attempting to close 'AsyncCloseable(name=http binding admin service)'...
08:25:41.582 DbStorageSingle:scan=sv4Scan | The operation 'appRewards.lookupBatchByHash.find' has failed with an exception. New kind of error: transient e
08:25:46.007 ProcessRewardsDryRunTrigger:SV=sv4 | Task closing trigger polling loop still not completed after 4500 milliseconds. Continue waiting...
08:25:46.067 ScanAppBootstrap:scan=sv4Scan | Task closing http binding admin service still not completed after 4500 milliseconds. Continue waiting...
08:25:46.605 HttpAdminService$HttpAdminServiceImpl:scan=sv4Sc | Successfully closed 'AsyncCloseable(name=http binding admin service)'.
08:25:47.250 SpliceEnvironment:config=179108e2 | Closing environment...
08:25:47.310 LogReporter:c.d.c.LogReporter:reporter=scala-tes | Completed test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'.
08:25:47.371 DbStorageSingle:scan=sv4Scan | retryWithDelay failed unexpectedly
```

The trace id `582db4527d7ea4f7345787c8e79cfb97` links it to sv4's `ProcessRewardsDryRunTrigger` (41.496) and sv4's
HTTP client call (41.563), as in section 4.

## 7. Scope in these two shards

```
for p in "10233 9dd73aad3b" "10234 95e122223c"; do set -- $p; T=log/$1/logs-simtime-2/canton_network_test.clog.gz
  echo "== $1 canton $(git show $2:nix/canton-sources.json | grep -m1 version | tr -d ' ,')"
  zcat $T | grep -a -E "retryWithDelay failed unexpectedly|lookupBatchByHash\.[a-z]+' has failed with an exception. New kind|still not completed after 4500" | python3 -c '
import sys,json,collections
c=collections.Counter()
for l in sys.stdin:
    j=json.loads(l); m=j["message"]; lg=j["logger_name"]
    suite=lg.split(":")[-1].split("/")[0]; node=lg.split("/")[-1]
    kind="retryWithDelay-ERROR" if "retryWithDelay" in m else ("binding-wait" if "still not" in m else "lookup-retry-start")
    c[(suite,node,kind)]+=1
for k,v in sorted(c.items()): print(v,*k)'; done
```
```
== 10233 canton "version":"3.6.0-snapshot.20260925.20321.0.vaecbf95c"
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest SV=sv1 binding-wait
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest scan=sv1Scan binding-wait
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest scan=sv1Scan lookup-retry-start
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest scan=sv1Scan retryWithDelay-ERROR
== 10234 canton "version":"3.6.0-snapshot.20260925.20321.0.vaecbf95c"
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest SV=sv4 binding-wait
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest scan=sv4Scan binding-wait
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest scan=sv4Scan lookup-retry-start
1 TrafficBasedRewardsSvAppTimeBasedIntegrationTest scan=sv4Scan retryWithDelay-ERROR
```

One hit per shard, both in this suite's teardown. The Pekko line only appears when the retry delay outlives the
environment executor; a shorter backoff at the moment of closing would end the loop silently (with only the
5 s teardown stall). Two older local artifacts that ran the same suite (9929 on 2026-09-02, canton
3.5.16-snapshot.20260901; 10238 on 2026-09-29) show no binding wait.

## Verdict

- New, not a duplicate. Real shutdown-ordering bug in splice (`NodeBootstrapBase.onClosed` closes the node before
  the HTTP binding), made visible by SV reward triggers that call a peer scan during teardown. Flake in CI terms
  (needs the dry-run trigger to fire in the ~100 ms window between the scan node closing and its binding closing,
  and the retry backoff to outlive the environment), deterministic mechanism.
- Fix location (app, described, not written): in `NodeBootstrapBase.onClosed`, close `httpAdminService` first,
  e.g. `List(httpAdminService) ++ grpcAdminServers ++ getNode.toList ++ stores ++ List(clock)`, so the binding
  stops accepting and drains in-flight requests while the stores are still open. Owner: splice app infra. The same
  change removes the 5 s teardown stall seen in both runs.
- Canton-side contributor (described): DbStorage treats `RejectedExecutionException` from a closed Slick executor
  as a transient error with infinite retries and keeps retrying after its storage reported
  `Failed(Component is closed)`; the retry should stop once the storage is closed. Cite against
  3.6.0-snapshot.20260925.20321.0.vaecbf95c when filing (not checked in the jar here).
- No ignore pattern: the Pekko line was the only signal of this bug that reached a checker (checkErrors was
  skipped); ignoring it would hide a request served against closed stores and a 5 s stall per teardown.
- Not verified: whether the Canton bump to 3.6.0-snapshot.20260925 (#7476, 2026-09-25) made this more likely
  (both hits are on it; only two older comparison artifacts); that the reordered close fixes it (not compiled or
  run); why the dry-run trigger starts a new task after teardown began (10234: task at 41.496, after the environment's
  `Stopping node` round started at 41.301).
