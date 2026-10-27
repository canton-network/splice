# 10606 - sv1Scan keeps retrying a DB query after its storage closed in LsuIntegrationTest teardown (run 40307406863)

New family. Release-line-0.9.x post-merge CI, all tests passed, checkErrors flagged 30 WARNs from
`DbStorageSingle` of sv1Scan. The test's own teardown ("stop apps manually") stopped sv1Scan at 10:54:19.176 while
the SV apps were still running. sv2's `CalculateRewardsTrigger` sent
`GET /api/scan/v0/internal/reward-accounting-process/rounds/0/root-hash` to sv1Scan 37 ms later; sv1Scan's HTTP
server still accepted it, `HttpScanHandler` called `appRewards.getAppRewardRootHashByRound` on a DB storage whose
executor was already `Terminated`, and the resulting `RejectedExecutionException` is classified as a transient error
with infinite retries, so it retried until the environment closed and crossed the WARN threshold.

- Run: https://github.com/canton-network/splice/actions/runs/40307406863, release-line-0.9.x 3eb742f434
  ("Backport PR #7964 to release-line-0.9.x (#7967)"), job 121520389410 `logical-sync-upgrade (0)`.
- Runtime canton: 3.6.4.
- Component: splice scan app shutdown order (HTTP server still serving after storage close) plus Canton
  DbStorage retry policy (RejectedExecutionException from a terminated executor retried forever).

## 1. Flagged lines (30, all sv1Scan DbStorageSingle WARN, 10:54:19.249 .. 10:54:27.959)

```
gh api repos/canton-network/splice/actions/jobs/121520389410/logs > log/10606/job.log
sed -n 15492,15525p log/10606/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' > log/10606/flagged.txt
grep -a -o '"message":"[^"]\{0,200\}' log/10606/flagged.txt | sed -E 's/@[0-9a-f]{6,}//g' | sort | uniq -c | cut -c1-200 | head -3
```
```
     17 "message":"DB_STORAGE_DEGRADATION(13,253e9c15): A database task was rejected from the database task queue.\nThe full error message from the task queue is:\njava.util.concurrent.RejectedExecutionException: Task s
      6 "message":"Now retrying operation 'appRewards.getAppRewardRootHashByRound'.
      1 "message":"The operation 'appRewards.getAppRewardRootHashByRound' has failed with an exception. Retrying after a number of 10 failures, and after 665 milliseconds.
```
The first one in full shows the executor state: `rejected from slick.util.AsyncExecutorWithMetrics$$anon$1@...[Terminated, pool size = 7, active threads = 0, queued tasks = 0, completed tasks = 1932]`.

## 2. Timeline: teardown stops scans first, the query arrives after

```
unzip -p log/10606/a.zip canton_network_test.clog.gz | gunzip -c | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Stopping|stopped|Closing|DbStorageSingle.*(clos|Terminat)" | grep -a -E 'T10:5[2-4]' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-220 | tail -40
```
Selected lines (DB_STORAGE_DEGRADATION messages cut after the first sentence):
```
2026-10-26T10:54:19.176Z Stopping sv1Scan node",[o.l.s.s.ScanApp:LsuIntegrationTest/config=73bea7fe/scan=sv1Scan] INFO
2026-10-26T10:54:19.249Z DB_STORAGE_DEGRADATION(13,253e9c15): A database task was rejected from the database task queue.
2026-10-26T10:54:24.273Z Stopping node sv1",[o.l.s.e.SvApps:LsuIntegrationTest/config=73bea7fe] INFO
2026-10-26T10:54:24.417Z Finished clue: stop apps manually to prevent errors from the synchronizer being force stopped",[o.l.s.i.t.LsuIntegrationTest:LsuIntegrationTest] DEBUG
2026-10-26T10:54:25.682Z Test succeeded: 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-10-26T10:54:27.959Z DB_STORAGE_DEGRADATION(13,253e9c15): A database task was rejected from the database task queue.
2026-10-26T10:54:28.192Z Closing environment...",[o.l.s.e.SpliceEnvironment:LsuIntegrationTest/config=73bea7fe] INFO
```

## 3. The request: trace 253e9c15... is sv2's CalculateRewardsTrigger polling peer scans

```
unzip -p log/10606/a.zip canton_network_test.clog.gz | gunzip -c > log/10606/test.clog
grep -a "253e9c15799e32a4e614655467b09232" log/10606/test.clog | grep -a -i "sv1Scan\|5012" | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-230 | head -12
```
Selected lines:
```
2026-10-26T10:54:19.213Z HTTP GET /api/scan/v0/internal/reward-accounting-process/rounds/0/root-hash from (127.0.0.1:55724): received request.",[o.l.s.a.a.HttpRequestLogger:LsuIntegrationTest/config=73bea7fe/scan=sv1Scan] DEBUG
2026-10-26T10:54:19.249Z Detected an error.",[c.d.c.r.DbStorageSingle:LsuIntegrationTest/config=73bea7fe/scan=sv1Scan] INFO
2026-10-26T10:54:19.250Z The operation 'appRewards.getAppRewardRootHashByRound' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a number of 0 failures, and after 50 milli
```
The trace's first line is `Processing Task(round = 0, dryRun = false)` from `CalculateRewardsTrigger` on SV=sv2 at
10:54:18.970 (span-name CalculateRewardsTrigger).

## 4. Caller in code (release-line-0.9.x)

```
git grep -n "getAppRewardRootHashByRound" cn/release-line-0.9.x -- apps/scan/src/main
```
```
cn/release-line-0.9.x:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/http/HttpScanHandler.scala:2867:      appRewardsStore.getAppRewardRootHashByRound(roundNumber).flatMap {
cn/release-line-0.9.x:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/db/DbScanAppRewardsStore.scala:534:      "appRewards.getAppRewardRootHashByRound",
```
Same call path on main (HttpScanHandler.scala:2941, DbScanAppRewardsStore.scala:543) and the same teardown clue in
LsuIntegrationTest on main, so main can hit it too.

## Verdict

New family, flake by timing (needs a peer-scan HTTP request inside the gap between scan storage close and HTTP
unbind). Fix belongs in the app: ScanApp should stop accepting HTTP requests before closing its storage, or the
store query should fail fast once the storage is closing instead of being retried as transient. Test-side
mitigation: stop the SV apps (whose triggers poll peer scans) before the scans in the "stop apps manually" clue.
No fix branch written (production shutdown order is the owner's call). Not verified: the ScanApp close order in
code, and whether the Canton retry policy would also stop on its own once the storage's closing flag is set.
