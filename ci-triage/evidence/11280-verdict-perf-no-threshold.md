# 11280 - Daily store performance tests: VerdictStoreIngestionPerformanceTest has no threshold rule (run 42557724754)

New, hard broken (deterministic). The scheduled run on main 0b2ccec235 ran all four ingestion perf tests
successfully, pushed the metrics, then the threshold check step `check_perf_thresholds.py` raised `KeyError: "no
thresholds configured for 'VerdictStoreIngestionPerformanceTest'"`. canton-network/splice#8269 (merged
2026-11-06 12:00 UTC by its author) added `SplicePerf run -t VerdictStore` to the scheduled job but no entry in
`.github/store-perf-thresholds.json`. The threshold check runs only on `schedule` events, so the PR's own
workflow_dispatch run passed; 11-09 03:40 is the first scheduled run after the merge (the cron is workdays only).
Every scheduled run fails until a rule is added.

- Run: https://github.com/canton-network/splice/actions/runs/42557724754, main 0b2ccec235 ("Bump the
  production-dependencies group in /apps with 3 updates (#8357)"), job 128967079585 `Ingestion Performance Tests`,
  attempt 1.
- Runtime canton: not relevant (no Canton in this job's failing step).
- Component: CI config (`.github/store-perf-thresholds.json`).

## 1. Failing step

Job id, then the log (escape codes stripped):
```
gh api repos/canton-network/splice/actions/runs/42557724754/jobs --paginate --jq '.jobs[] | select(.conclusion=="failure") | [.id,.name]|@tsv'
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/128967079585/logs > log/11280/job.log
sed 's/\x1b\[[0-9;]*m//g' log/11280/job.log > log/11280/job.txt
sed -n 12459,12461p log/11280/job.txt; grep -n 'Total time\|No problems\|Pushed\|^.\{29\}OK\|KeyError' log/11280/job.txt
```
```
128967079585	Ingestion Performance Tests
```
```
2026-11-09T03:51:41.2551637Z [success] Total time: 98 s (0:01:38.0), completed Nov 9, 2026, 3:51:40 AM
2026-11-09T03:51:42.2592536Z No problems found in the sbt output.
2026-11-09T03:51:43.5293040Z Pushed 12 metric(s) for 'ScanStoreIngestionPerformanceTest'
2026-11-09T03:51:43.5293330Z Pushed 12 metric(s) for 'SvDsoStoreIngestionPerformanceTest'
2026-11-09T03:51:43.5293622Z Pushed 12 metric(s) for 'UpdateHistoryIngestionPerformanceTest'
2026-11-09T03:51:43.5293915Z Pushed 14 metric(s) for 'VerdictStoreIngestionPerformanceTest'
2026-11-09T03:51:47.0467575Z OK     ScanStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 1429919 (<= 3000000)
2026-11-09T03:51:47.0468216Z OK     ScanStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 11506559157 (<= 600000000000)
2026-11-09T03:51:47.0468969Z OK     SvDsoStoreIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 1707949 (<= 3700000)
2026-11-09T03:51:47.0469412Z OK     SvDsoStoreIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 13743862367 (<= 600000000000)
2026-11-09T03:51:47.0469876Z OK     UpdateHistoryIngestionPerformanceTest :: splice_perf_ingestion_avg_item_time_ns = 8750254 (<= 33100000)
2026-11-09T03:51:47.0470335Z OK     UpdateHistoryIngestionPerformanceTest :: splice_perf_ingestion_total_time_ns = 70413291950 (<= 1200000000000)
2026-11-09T03:51:47.0473600Z KeyError: "no thresholds configured for 'VerdictStoreIngestionPerformanceTest' (from VerdictStoreIngestionPerformanceTest.json); add a rule to .github/store-perf-thresholds.json"
```
(The `Total time` line is the last of the four sbt invocations, the VerdictStore one.)

The VerdictStore metrics of this run, for whoever sets the threshold (one data point only):
```
sed -n 12286,12293p log/11280/job.txt
```
```
2026-11-09T03:51:40.2526076Z       "name" : "splice_perf_ingestion_avg_item_time_ns",
2026-11-09T03:51:40.2526331Z       "description" : "Average nanoseconds per ingested item",
2026-11-09T03:51:40.2526566Z       "value" : 2505635.079321452847474657918846233
2026-11-09T03:51:40.2526757Z     },
2026-11-09T03:51:40.2526894Z     {
2026-11-09T03:51:40.2527060Z       "name" : "splice_perf_ingestion_total_time_ns",
2026-11-09T03:51:40.2527299Z       "description" : "Total ingestion time in nanoseconds",
2026-11-09T03:51:40.2527526Z       "value" : 84783174179
```

## 2. The script raises on any metrics file without a rule

```
git show 0b2ccec235:scripts/performance/check_perf_thresholds.py | sed -n 127,133p
```
```
        test_name = data.get("test_name") or f.stem
        rules = thresholds.get(test_name)
        if not rules:
            raise KeyError(
                f"no thresholds configured for '{test_name}' (from {f.name}); "
                f"add a rule to {DEFAULT_THRESHOLDS}"
            )
```
The thresholds file at the run's sha has no VerdictStore entry:
```
git show 0b2ccec235:.github/store-perf-thresholds.json | grep -o '^  "[A-Za-z-]*"'
```
```
  "SvDsoStoreIngestionPerformanceTest"
  "ScanStoreIngestionPerformanceTest"
  "UpdateHistoryIngestionPerformanceTest"
  "UpdateHistoryReadPerformanceTest-getUpdate"
  "UpdateHistoryReadPerformanceTest-encodeUpdate"
```

## 3. Which commit added VerdictStore to the scheduled job

```
git log --format='%h %an %ad %s' --date=iso-strict -6 0b2ccec235 -- .github/store-perf-thresholds.json scripts/performance/check_perf_thresholds.py scripts/performance/push_store_ingestion_perf_metrics.py .github/workflows/ | head -3
git show 97df2c4399 --stat | tail -6
git show 97df2c4399 -- .github/workflows/performance_tests.yml | grep '^+' | grep -o 'run -t VerdictStore[^"]*'
gh pr view 8269 -R canton-network/splice --json author,mergedAt,mergedBy --jq '[.author.login,.mergedAt,.mergedBy.login]|@tsv'
```
```
d8537ea52b Stephen Compall 2026-11-06T17:31:57+01:00 Shallow `split_tests` checkout and report git size per CI job (#7931)
b77153c658 Robert Autenrieth 2026-11-06T14:34:52+01:00 Use DA BFT in all tests (#8234)
97df2c4399 Jagath Weerasinghe 2026-11-06T13:00:48+01:00 Add grafana for verdict ingestion tests (#8269)
```
```
 .github/workflows/performance_tests.yml            |    6 +-
 .../tests/StoreIngestionPerformanceTest.scala      |  161 +--
 .../VerdictStoreIngestionPerformanceTest.scala     |   91 +-
 ...splice-stores.kubernetes_core_v1_ConfigMap.json |    2 +-
 .../splice-stores/store-ingestion-performance.json | 1082 +++++++++++++++++++-
 5 files changed, 1246 insertions(+), 96 deletions(-)
```
```
run -t VerdictStore -c ./apps/app/src/test/resources/performance/tests.conf -d /tmp/mainnetevents.json
```
```
jagathweerasinghe-da	2026-11-06T12:00:48Z	jagathweerasinghe-da
```
#8269 does not touch the thresholds file (5 files above). The last commits to the thresholds file are all by the
same author:
```
git log --format='%h %an' -5 0b2ccec235 -- .github/store-perf-thresholds.json
```
```
f2be6819a9 Jagath Weerasinghe
76d19278f3 Jagath Weerasinghe
572252523b Jagath Weerasinghe
4d0b0d0dfb Jagath Weerasinghe
1a93eb2f1b Jagath Weerasinghe
```

## 4. Why the PR run passed: the check runs only on schedule

```
git show 0b2ccec235:.github/workflows/performance_tests.yml | grep -n -A2 "cron\|id: perf_check"
```
```
6:    - cron: '37 3 * * 1-5' # 3:37 AM on workdays
--
95-        id: perf_check
96-        if: ${{ success() && github.event_name == 'schedule' }}
97-        run: |
--
199-        id: perf_check
200-        if: ${{ success() && github.event_name == 'schedule' }}
201-        run: |
```
Workflow runs (the board's "passed on 6 earlier runs" includes workflow_dispatch runs on branches):
```
for r in 42557724754 42239552308 42230731460 42190144460 42157992110 41963108801; do gh api repos/canton-network/splice/actions/runs/$r --jq '[.id,.created_at,.event,.head_branch,.head_sha[0:10],.conclusion]|@tsv'; done
gh api repos/canton-network/splice/actions/runs/42190144460/jobs --jq '.jobs[]|[.id,.name,.conclusion]|@tsv'
gh api "repos/canton-network/splice/compare/97df2c4399...01436df75f" --jq .status
```
```
42557724754	2026-11-09T03:40:52Z	schedule	main	0b2ccec235	failure
42239552308	2026-11-06T15:04:01Z	workflow_dispatch	wee/benchmark_http_marshalling_ing_getupdate	4c3a54e2e0	success
42230731460	2026-11-06T14:04:48Z	workflow_dispatch	wee/benchmark_http_marshalling_ing_getupdate	086d9a66c0	success
42190144460	2026-11-06T08:46:56Z	workflow_dispatch	wee/add_grafana_for_verdict_ingestion_tests	4b092fd019	success
42157992110	2026-11-06T03:40:45Z	schedule	main	01436df75f	success
41963108801	2026-11-05T03:40:58Z	schedule	main	2930ba1a4b	success
```
```
127799855442	Ingestion Performance Tests	success
127808877110	Read Performance Tests	skipped
```
```
behind
```
The PR branch's dispatch run executed the ingestion job (success, check skipped as not `schedule`); the last
scheduled run, 11-06 03:40 on 01436df75f, is before #8269 (01436df75f is behind 97df2c4399); 11-07 and 11-08 were
weekend days without a scheduled run. So 11-09 is the first scheduled run with #8269.

## 5. Duplicate check

```
grep -in "threshold\|perf" ci-triage/known-families.md
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "no thresholds configured"' --jq '.total_count'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Ingestion Tests"' --jq '.items[]|[.number,.state,.created_at[0:10],.title]|@tsv' | head
```
```
ci-triage/known-families.md:403:  0,3,6,18,21; 8 h threshold; 12 h gap 04->16): 10915, open with Krzysztof. Earlier 18:xx hits 10514 10638 10692.
```
```
0
```
```
11280	open	2026-11-09	GHA Run 42557724754 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
10845	closed	2026-10-29	GHA Run 40813530314 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
10355	closed	2026-10-16	GHA Run 38847323353 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
10335	closed	2026-10-12	GHA Run 38202061937 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
8389	open	2026-05-13	 ingestion_performance_tests Failed due to GCS bucket permissions
10304	closed	2026-10-08	GHA Run 37724882996 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
10298	closed	2026-10-07	GHA Run 37568797354 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
10284	closed	2026-10-06	GHA Run 37410928884 : job Ingestion Tests in ingestion_performance_tests Failed :fire:
11240	closed	2026-11-06	GHA Run 42214429624 : job wall-clock-time (7) in scala_test Failed :fire:
10274	closed	2026-10-05	Ingestion performance tests: Breach with October data
```
- known-families line 403: a Devnet backup-status threshold, unrelated.
- 10845: the 10-29 main compile break (`truncateInstant`, INDEX.tsv, fixed by #8109), not this.
- 10355, 10335, 10304, 10298, 10284: closed as duplicate (state_reason `duplicate`); their parent era is 10274
  ("Breach with October data"), a threshold breach, not a missing rule. Not this.
- 8389: GCS bucket permissions, not this. 11240: unrelated wall-clock test.
No earlier issue with this KeyError.

## Verdict

New, deterministic CI config break (not a flake), introduced by canton-network/splice#8269. Every scheduled run of
"Daily store performance tests" fails at the threshold step until fixed. Tests themselves pass.

Fix: add a `VerdictStoreIngestionPerformanceTest` entry to `.github/store-perf-thresholds.json` with
`splice_perf_ingestion_avg_item_time_ns` and `splice_perf_ingestion_total_time_ns` maxima (this run: 2505635 ns and
84783174179 ns; the other entries are derived from 30 days of data, so a provisional generous value until data
exists). Optional hardening: run `check_perf_thresholds.py` in non-schedule runs too (or a static check that every
`-t` target in the workflow has a rule), so the PR run catches it. Owner: jagathweerasinghe-da (author of #8269 and
of the last five changes to the thresholds file).

Not verified: whether the push to Pushgateway of the new metrics matters for the Grafana dashboard (unaffected by
this failure, the push succeeded before the check).
