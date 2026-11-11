# 11351 11352 - daily store perf: VerdictStore ingestion over its seeded threshold; read check has no rule for serializeJson (run 42954358024)

DUPLICATES. 11351 = 11321 (same breach, next day); 11352 = 11322 (same KeyError, next day). Both parents are open,
Triaged and assigned to jagathweerasinghe-da. The existing comment on 11322 quotes the ingestion breach, which is
not the cause of the read failure: the read job fails on `KeyError: no thresholds configured for
'UpdateHistoryReadPerformanceTest-serializeJson'` (#8351 added the operation without a rule). Not a flake: both
fail on every scheduled run until `.github/store-perf-thresholds.json` changes.

- Run: https://github.com/canton-network/splice/actions/runs/42954358024, main 9dd892fe2e ("SV UI: Add support for
  new fields in GrantFeaturedAppRight vote creation (#8062)"), schedule, attempt 1.
  - 11351: job 130305812492 `Ingestion Performance Tests` (conclusion success, issue filed by its report step on breaches).
  - 11352: job 130313819699 `Read Performance Tests` (failed at step `Check read thresholds`).
- Runtime canton: not relevant (store tests against Postgres, no Canton).
- Component: test config (`.github/store-perf-thresholds.json`).

## 1. Job mapping: both jobs, and why the successful ingestion job filed 11351

The run has exactly two jobs; the ingestion job succeeded but its `Report Failures on Slack & Github` step ran.

```
$ gh api "repos/canton-network/splice/actions/runs/42954358024/jobs?filter=all&per_page=100" --jq '.jobs[]|"\(.id) \(.name) \(.conclusion) att=\(.run_attempt) \(.started_at) \(.completed_at)\n  \([.steps[]|"\(.number):\(.name)=\(.conclusion)"]|join("; "))"'
130305812492 Ingestion Performance Tests success att=1 2026-11-11T03:41:18Z 2026-11-11T04:04:33Z
  1:Set up job=success; ...; 6:Run ingestion performance tests=success; 7:Push ingestion metrics to Prometheus Pushgateway=success; 8:Upload ingestion metrics artifact=success; 9:Check ingestion thresholds=success; 10:Check logs for errors=success; 11:Upload logs=skipped; 12:Report Failures on Slack & Github=success; ...
130313819699 Read Performance Tests failure att=1 2026-11-11T04:05:37Z 2026-11-11T04:16:51Z
  1:Set up job=success; ...; 6:Run read performance tests=success; 7:Push read metrics to Prometheus Pushgateway=success; 8:Upload read metrics artifact=success; 9:Check read thresholds=failure; 10:Check logs for errors=skipped; 11:Upload logs=success; 12:Report Failures on Slack & Github=success; ...
```

11351 was created 2026-11-11T04:04:22Z, inside the ingestion job (03:41:18 to 04:04:33) and before the read job
started (04:05:37); 11352 at 04:16:41Z, inside the read job. Issue job names `Ingestion Tests in
ingestion_performance_tests` / `Read Tests in read_performance_tests` are the workflow job ids, the API shows the
display names.

The report step fires on breaches even when the threshold step succeeds (workflow at the run sha):

```
$ git show 9dd892fe2e28:.github/workflows/performance_tests.yml | grep -n -A3 -E 'name: (Check ingestion thresholds|Report Failures)'
94:      - name: Check ingestion thresholds
95-        id: perf_check
96-        if: ${{ success() && github.event_name == 'schedule' }}
97-        run: |
116:      - name: Report Failures on Slack & Github
117:        if: ${{ github.event_name == 'schedule' && (failure() || steps.perf_check.outputs.has_breaches == 'true') }}
118:        uses: ./.github/actions/tests/failure_notifications
119-        with:
```

and `check_perf_thresholds.py` only writes `has_breaches` (it raises only on a metrics file without a rule):

```
$ git show 9dd892fe2e28:scripts/performance/check_perf_thresholds.py | sed -n '127,133p;178,181p'
        test_name = data.get("test_name") or f.stem
        rules = thresholds.get(test_name)
        if not rules:
            raise KeyError(
                f"no thresholds configured for '{test_name}' (from {f.name}); "
                f"add a rule to {DEFAULT_THRESHOLDS}"
            )
    # Breach signaling is done via GITHUB_OUTPUT
    # downstream steps can branch on `steps.<id>.outputs.has_breaches`
    append_step_output("has_breaches", "true" if breaches else "false")
    append_step_output("breach_count", str(len(breaches)))
```

## 2. 11351: ingestion breaches

```
$ gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/130305812492/logs > log/inbox-1111/130305812492.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/inbox-1111/130305812492.log | grep -aE 'BREACH|^.{29}OK ' | cut -c1-230
2026-11-11T04:03:52.9333976Z OK     ScanStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 1948164 (<= 3000000)
2026-11-11T04:03:52.9334808Z OK     ScanStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 15676874399 (<= 600000000000)
2026-11-11T04:03:52.9335832Z OK     SvDsoStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 2406318 (<= 3700000)
2026-11-11T04:03:52.9336513Z OK     SvDsoStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 19363639410 (<= 600000000000)
2026-11-11T04:03:52.9337260Z BREACH UpdateHistoryIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 34014774 (> 33100000, +2.76%)
2026-11-11T04:03:52.9337999Z OK     UpdateHistoryIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 273716888348 (<= 1200000000000)
2026-11-11T04:03:52.9338783Z BREACH VerdictStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 11835193 (> 3700000, +219.87%)
2026-11-11T04:03:52.9339603Z OK     VerdictStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 400467414440 (<= 600000000000)
```

The previous day (run 42750998590, the run of 11321), same VerdictStore breach, UpdateHistory under its threshold:

```
$ gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/129614322904/logs > log/inbox-1111/129614322904.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/inbox-1111/129614322904.log | grep -aE 'BREACH|^.{29}OK ' | cut -c1-230
2026-11-10T04:02:57.3007173Z OK     ScanStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 2009744 (<= 3000000)
2026-11-10T04:02:57.3007976Z OK     ScanStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 16172409327 (<= 600000000000)
2026-11-10T04:02:57.3008656Z OK     SvDsoStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 2202277 (<= 3700000)
2026-11-10T04:02:57.3009321Z OK     SvDsoStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 17721725825 (<= 600000000000)
2026-11-10T04:02:57.3010058Z OK     UpdateHistoryIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 29780961 (<= 33100000)
2026-11-10T04:02:57.3010746Z OK     UpdateHistoryIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 239647392088 (<= 1200000000000)
2026-11-10T04:02:57.3011460Z BREACH VerdictStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 10554726 (> 3700000, +185.26%)
2026-11-10T04:02:57.3012166Z OK     VerdictStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 357140257138 (<= 600000000000)
```

The VerdictStore threshold of 3700000 ns is a seed copied from the sibling tests, not a measured baseline
(#8376, the fix of 11280):

```
$ git show -s --format='%h %an %ad %s' --date=iso 91715b25c0
91715b25c0 Jagath Weerasinghe 2026-11-09 17:47:46 +0100 set temp thresholds (#8376)
$ git show 91715b25c0 | grep '^+' | grep -A3 VerdictStore
+  "VerdictStoreIngestionPerformanceTest": {
+    "_comment_avg_item_time_ns": "newly added test with no 30d history yet; seeded conservatively from sibling ingestion tests. Need to recalculate as median*(1+ noise_margin + safety_margin) of last 30d once data is collected.",
+    "splice_perf_ingestion_avg_item_time_ns": {
+      "max": 3700000
```

So the VerdictStore breach is a threshold that was never measured (observed 10.6 ms and 11.8 ms per item on the
only two scheduled runs since), not a regression. The UpdateHistory breach (+2.76%) is new on 11-11 and marginal;
two data points do not show a trend.

## 3. 11352: read check KeyError

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/inbox-1111/130313819699.log | grep -aE "KeyError|Read 'serializeJson'|^.{29}OK " | cut -c1-230
2026-11-11T04:16:14.8837091Z org.lfdecentralizedtrust.splice.performance.tests.UpdateHistoryReadPerformanceTest: Read 'serializeJson': time=390.58 ms, dbWait=0.00 ms, dbExec=0.00 ms, nonDb=390.58 ms, dbQueries=0, maxDbQuery=0.00 m
2026-11-11T04:16:22.2435683Z OK     UpdateHistoryReadPerformanceTest-encodeUpdate :: splice_perf_read_avg_item_time_ns = 283264586 (<= 441300000)
2026-11-11T04:16:22.2436645Z OK     UpdateHistoryReadPerformanceTest-encodeUpdate :: splice_perf_read_total_time_ns = 283264586 (<= 441300000)
2026-11-11T04:16:22.2437540Z OK     UpdateHistoryReadPerformanceTest-getUpdate :: splice_perf_read_avg_item_time_ns = 902577990 (<= 947000000)
2026-11-11T04:16:22.2438416Z OK     UpdateHistoryReadPerformanceTest-getUpdate :: splice_perf_read_total_time_ns = 902577990 (<= 947000000)
2026-11-11T04:16:22.2443405Z     raise KeyError(
2026-11-11T04:16:22.2444341Z KeyError: "no thresholds configured for 'UpdateHistoryReadPerformanceTest-serializeJson' (from UpdateHistoryReadPerformanceTest-serializeJson.json); add a rule to .github/store-perf-thresholds.json"
```

The previous day's read job (run 42750998590, job 129622014509, the run of 11322) failed identically:

```
$ gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/129622014509/logs > log/inbox-1111/129622014509.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/inbox-1111/129622014509.log | grep -aE "KeyError|Read 'serializeJson'|^.{29}OK " | cut -c1-230
2026-11-10T04:10:43.4445278Z org.lfdecentralizedtrust.splice.performance.tests.UpdateHistoryReadPerformanceTest: Read 'serializeJson': time=239.24 ms, dbWait=0.10 ms, dbExec=0.45 ms, nonDb=238.69 ms, 
2026-11-10T04:10:49.2177196Z OK     UpdateHistoryReadPerformanceTest-encodeUpdate :: splice_perf_read_avg_item_time_ns = 152803123 (<= 441300000)
2026-11-10T04:10:49.2177809Z OK     UpdateHistoryReadPerformanceTest-encodeUpdate :: splice_perf_read_total_time_ns = 152803123 (<= 441300000)
2026-11-10T04:10:49.2178307Z OK     UpdateHistoryReadPerformanceTest-getUpdate :: splice_perf_read_avg_item_time_ns = 557824580 (<= 947000000)
2026-11-10T04:10:49.2178833Z OK     UpdateHistoryReadPerformanceTest-getUpdate :: splice_perf_read_total_time_ns = 557824580 (<= 947000000)
2026-11-10T04:10:49.2182285Z KeyError: "no thresholds configured for 'UpdateHistoryReadPerformanceTest-serializeJson' (from UpdateHistoryReadPerformanceTest-serializeJson.json); add a rule to .github/store-perf-thresholds.json"
```

The two ruled read files are checked before the KeyError; `getUpdate` was at 902.6 ms of its 947 ms threshold on 11-11.

The operation comes from #8351; the thresholds file at the run sha has rules only for `-getUpdate` and
`-encodeUpdate`, and main has no later change to the file:

```
$ git log --oneline -S'serializeJson' 9dd892fe2e28 -- apps scripts .github
6f7a5f83bc Benchmarking JSON serialization in getUpdateById (#8351)
$ git show -s --format='%h %an %ad %s' --date=iso 6f7a5f83bc
6f7a5f83bc Jagath Weerasinghe 2026-11-06 17:04:29 +0100 Benchmarking JSON serialization in getUpdateById (#8351)
$ git show 6f7a5f83bc | grep -n 'name = "serializeJson"'
70:+      name = "serializeJson",
$ git show 9dd892fe2e28:.github/store-perf-thresholds.json | grep -n 'ReadPerformanceTest-'
42:  "UpdateHistoryReadPerformanceTest-getUpdate": {
52:  "UpdateHistoryReadPerformanceTest-encodeUpdate": {
$ git fetch -q https://github.com/canton-network/splice.git main:refs/remotes/origin/main
$ git log --oneline 9dd892fe2e28..origin/main -- .github/store-perf-thresholds.json
(empty)
$ git show origin/main:.github/store-perf-thresholds.json | grep -c serializeJson
0
```

## 4. Run history: every scheduled run since #8351 fails

```
$ gh api "repos/canton-network/splice/actions/workflows/225673880/runs?per_page=10" --jq '.workflow_runs[]|"\(.id) \(.created_at) \(.event) \(.head_sha[0:10]) \(.conclusion)"'  # plus per-run jobs
42954358024 2026-11-11T03:41:01Z schedule 9dd892fe2e failure
   130305812492 Ingestion Performance Tests success
   130313819699 Read Performance Tests failure Check read thresholds
42750998590 2026-11-10T03:40:50Z schedule 885cd84668 failure
   129614322904 Ingestion Performance Tests success
   129622014509 Read Performance Tests failure Check read thresholds
42641356382 2026-11-09T15:07:02Z workflow_dispatch 4ba7a0d201 success
   129243010028 Read Performance Tests success
   129243012103 Ingestion Performance Tests skipped
42557724754 2026-11-09T03:40:52Z schedule 0b2ccec235 failure
   128967079585 Ingestion Performance Tests failure Check ingestion thresholds
   128970115717 Read Performance Tests skipped
42239552308 2026-11-06T15:04:01Z workflow_dispatch 4c3a54e2e0 success
   ...
42157992110 2026-11-06T03:40:45Z schedule 01436df75f success
41963108801 2026-11-05T03:40:58Z schedule 2930ba1a4b success
41768623187 2026-11-04T03:40:58Z schedule 9ab7e060e0 success
```

11-09: 11280 (missing VerdictStore rule, fixed by #8376); read skipped (needs ingestion). 11-10 and 11-11: both
jobs report. workflow_dispatch runs skip the threshold step (`github.event_name == 'schedule'`), so manual runs do
not show the problem.

## 5. Parents and fix PRs

```
$ for n in 11321 11322; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"\(.number) \(.state) \(.created_at) assignees=\([.assignees[].login]|join(",")) | \(.title)"'; done
11321 open 2026-11-10T04:03:27Z assignees=jagathweerasinghe-da | GHA Run 42750998590 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
11322 open 2026-11-10T04:11:05Z assignees=jagathweerasinghe-da | GHA Run 42750998590 : job Read Tests in read_performance_tests Failed :fire:
$ gh api repos/DACH-NY/cn-test-failures/issues/11322/comments --jq '.[]|"[\(.user.login) \(.created_at)] \(.body)"'
[jagathweerasinghe-da 2026-11-10T09:11:19Z] BREACH VerdictStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 10554726 (> 3700000, +185.26%)
```

Both are Status Triaged in the 2026-11-11 07:37 board export (`triaged.jsonl`). No open splice PR touches the
thresholds file: the only open PR of the author in this area, #8377 "Measure allocated memory in read perf tests",
changes test code and dashboards only:

```
$ gh api repos/canton-network/splice/pulls/8377/files --jq '.[]|.filename'
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/performance/tests/BaseStorePerformanceTest.scala
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/performance/tests/StoreReadPerformanceTest.scala
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/performance/tests/UpdateHistoryReadPerformanceTest.scala
cluster/expected/observability/grafana-dashboards-splice-stores.kubernetes_core_v1_ConfigMap.json
cluster/pulumi/observability/grafana-dashboards/splice-stores/store-read-performance.json
$ gh api -X GET search/issues -f q='repo:canton-network/splice is:pr perf threshold created:>=2026-11-05' --jq '.items[]|"#\(.number) \(.state) \(.user.login) \(.title)"'
#8376 closed jagathweerasinghe-da Fix perf thresholds
```

## Verdict

- 11351: duplicate of 11321 (VerdictStore ingestion avg_item_time over the 3.7 ms seed). New on 11-11: a marginal
  UpdateHistory ingestion breach (+2.76%).
- 11352: duplicate of 11322 (no threshold rule for `UpdateHistoryReadPerformanceTest-serializeJson`, added by #8351).
  The comment on 11322 names the ingestion breach; the read job fails on the KeyError.
- Real (deterministic) config gap, not a flake. Fix in `.github/store-perf-thresholds.json`: add a
  `UpdateHistoryReadPerformanceTest-serializeJson` rule (`splice_perf_read_avg_item_time_ns` and
  `splice_perf_read_total_time_ns`; observed 239 ms and 391 ms), and reseed the VerdictStore
  `splice_perf_ingestion_avg_item_time_ns` from measured data (observed 10.6 and 11.8 ms). Owner
  jagathweerasinghe-da (author of #8351 and #8376, assigned to both parents).
- Not verified: the order in which `iter_metric_files` visits files; in both runs the two ruled read files were
  checked before the KeyError, but any file visited after a rule-less one is not checked. A follow-up could report
  a missing rule as a breach instead of raising, so one missing rule does not mask the rest.
