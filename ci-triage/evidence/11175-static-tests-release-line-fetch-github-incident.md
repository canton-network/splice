# 11175 - static_tests: fetch of release-line-0.10.0 failed three times during a GitHub Git Operations incident (run 41720897995)

DUPLICATE of 10213 (family J, GitHub incident). main 634627f601, job 126213071654 `static_tests`, step Setup
(`common_setup.sh`) failed after 917 s: three `git fetch` attempts for `release-line-0.10.0` failed, no further step ran.
githubstatus lists `Incident with Git Operations` 2026-11-03 20:57 to 22:41 UTC, covering the whole step.

- Run: https://github.com/canton-network/splice/actions/runs/41720897995, main 634627f601 ("WalletMetricsTest: look up trigger.completed by outcome succ..."), job 126213071654 `static_tests`, attempt 1.
- Component: infra (GitHub).

## 1. Failing step

```
jq -rs '[.[].jobs[]] | .[] | select(.id==126213071654) | .steps[] | "\(.number) \(.name) \(.conclusion) \(.started_at[11:19])-\(.completed_at[11:19])"' log/11175/jobs.json | head -5
```
```
1 Set up job success 21:20:42-21:20:43
2 Initialize containers success 21:20:43-21:20:51
3 Check out repository code success 21:20:51-21:21:30
4 Setup failure 21:21:30-21:36:50
5 Check Grafana Dashboards skipped 21:36:50-21:36:50
```
```
grep -a -A6 'FETCHING LATEST RELEASE LINE' log/11175/job.txt
```
```
FETCHING LATEST RELEASE LINE
Fetch attempt 1 for release line 0.10.0 failed. Retrying...
Fetch attempt 2 for release line 0.10.0 failed. Retrying...
Fetch attempt 3 for release line 0.10.0 failed. Retrying...
This was the last attempt. Exiting.
total elapsed: 917s
```

## 2. GitHub incident

```
python3 ci-triage/scripts/github_incidents.py 2026-11-03T21:22:00Z 2026-11-03T21:30:00Z
```
```
2026-11-03 21:22:00 UTC: 1 incident(s)
  2026-11-03 20:57 to 2026-11-03 22:41 UTC minor | Incident with Git Operations | Git Operations | https://stspg.io/n478rfsspcmr
2026-11-03 21:30:00 UTC: 1 incident(s)
  2026-11-03 20:57 to 2026-11-03 22:41 UTC minor | Incident with Git Operations | Git Operations | https://stspg.io/n478rfsspcmr
```

## Verdict
Duplicate of 10213 (family J, GitHub incident). No splice change. Not verified: the git error text (the fetch helper
does not print stderr).
