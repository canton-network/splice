# 11152, 11153, 11154 - Deploy CIMain: cluster test invoker returned HTTP 500 for 20 minutes (run 41610142697)

New, family J (infra, no code change). One splice run, three issues: the job `trigger_cimain / trigger_cluster_test`
of Deploy CIMain run 41610142697 (main 8fd9921238, "[ci] Bump react and @types/react in /apps (#8053)") failed in
the step "Trigger cluster test" in attempts 1, 2 and 3 and passed in attempt 4. Each failed attempt filed its own
issue; the log of each attempt names it (11152 = attempt 1, 11153 = attempt 2, 11154 = attempt 3). In every failed
attempt the POST to the cluster test invoker (secret CLUSTER_TEST_INVOKER_URL, a service in GCP project da-cn-ci)
returned HTTP 500 four times (curl --retry 3, 10 s apart), with the Werkzeug default body "500 Internal Server
Error", which points to an unhandled exception in the invoker app rather than a proxy or overload answer. Window:
09:20:12 to 09:40:29 UTC; at 09:44:12 attempt 4 got a canton-network-internal run back (41613435215). No splice
code involved; the reruns succeeded, nothing to fix in splice.

- Run: https://github.com/canton-network/splice/actions/runs/41610142697, main 8fd9921238 ("[ci] Bump react and
  @types/react in /apps (#8053)"), jobs 125829695539 (attempt 1), 125831853150 (attempt 2), 125838729554
  (attempt 3), all `trigger_cimain / trigger_cluster_test`; attempt 4 job 125840697338 passed.
- Runtime canton: not relevant (no Canton in the job).
- Component: infra (cluster test invoker service, canton-network-internal / GCP da-cn-ci).

## 1. Failing step, per attempt, and the issue each attempt filed

Every failed attempt: four HTTP 500 answers about 10 s apart, then the error; the failure notification step logs
the issue URL it filed.
```
for r in 11152 11153 11154; do echo "== log/$r/job.log"; sed -E 's/\x1b\[[0-9;]*m//g' log/$r/job.log | grep -a 'curl: (\|issues/11\|##\[error\]Failed' | grep -aoE '^\S+Z|curl: \(22\).*|issues/[0-9]+|##\[error\]Failed.*' | awk '!(/issues/ && s[$0]++)'; done
```
```
== log/11152/job.log
2026-11-03T09:20:12.2647211Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:20:22.7345760Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:20:33.1329395Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:20:43.5400604Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:20:43.5446120Z
##[error]Failed to trigger cluster test workflow 'deploy_cimain' for sha '8fd99212387cdb6773723304e0e90f179c11a1da'
2026-11-03T09:20:57.7481696Z
issues/11152
== log/11153/job.log
2026-11-03T09:24:56.8257931Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:25:07.2300399Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:25:17.6307196Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:25:28.0322511Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:25:28.0360874Z
##[error]Failed to trigger cluster test workflow 'deploy_cimain' for sha '8fd99212387cdb6773723304e0e90f179c11a1da'
2026-11-03T09:25:42.0816112Z
issues/11153
== log/11154/job.log
2026-11-03T09:39:57.5443230Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:40:08.0506313Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:40:18.7522988Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:40:29.2517724Z
curl: (22) The requested URL returned error: 500
2026-11-03T09:40:29.2559796Z
##[error]Failed to trigger cluster test workflow 'deploy_cimain' for sha '8fd99212387cdb6773723304e0e90f179c11a1da'
2026-11-03T09:40:41.1245703Z
issues/11154
```

## 2. Response body of the invoker

The body is the default Werkzeug (Flask) 500 page: the invoker app raised, not a load balancer or quota answer.
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11152/job.log | grep -a -A1 'Response body' | sed -E 's/^\S+Z //' | cut -c1-200
```
```
  echo "Response body: $result"
  exit 1
--
Response body: 500 Internal Server Error: The server encountered an internal error and was unable to complete your request. Either the server is overloaded or there is an error in the application.500 
##[error]Process completed with exit code 1.
```

## 3. The successful attempt 4 (09:44)

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/125840697338/logs > log/11152/job-attempt4.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11152/job-attempt4.log | grep -a '^\S*Z Triggered deploy_cimain' | sed -E 's/^\S+Z //'
```
```
Triggered deploy_cimain: {"html_url":"https://github.com/DACH-NY/canton-network-internal/actions/runs/41613435215","run_url":"https://api.github.com/repos/DACH-NY/canton-network-internal/actions/runs/41613435215","workflow_run_id":41613435215}
```

## 4. The step (splice side): curl already retries 500 for up to 60 s

`.github/workflows/cluster_tests.yml`; curl --retry covers HTTP 500. The outage (at least 09:20:12 to 09:40:29)
was far longer than any sensible retry window, so no splice change would have helped.
```
sed -n 46,56p .github/workflows/cluster_tests.yml
```
```
        run: |
          set -euo pipefail
          if ! result=$(curl -sSL -X POST --fail-with-body \
            --retry 3 --retry-delay 10 --retry-max-time 60 \
            --connect-timeout 10 --max-time 60 \
            -H "Authorization: Bearer ${{ steps.auth.outputs.id_token }}" \
            "${{ secrets.CLUSTER_TEST_INVOKER_URL }}?workflow=${{ inputs.workflow }}&splice-git-ref=${{ inputs.sha }}"); then
            echo "::error::Failed to trigger cluster test workflow '${{ inputs.workflow }}' for sha '${{ inputs.sha }}'"
            echo "Response body: $result"
            exit 1
          fi
```

## 5. Other runs of Deploy CIMain: before and after all fine

The run before (2026-11-02 22:45) and every run after (10:37 onwards) triggered on the first attempt; the
pr_cluster_test workflow had no run between 09:19 and 09:44, so no other caller hit the invoker in the window.
```
gh api '/repos/canton-network/splice/actions/workflows/cimain.yml/runs?per_page=6' --jq '.workflow_runs[] | "\(.id) \(.created_at) \(.updated_at) attempt=\(.run_attempt) \(.conclusion) \(.head_sha[:10])"'
```
```
41637827736 2026-11-03T12:41:23Z 2026-11-03T12:41:45Z attempt=1 success 5c53b74294
41637223245 2026-11-03T12:37:03Z 2026-11-03T12:37:24Z attempt=1 success 6a32b00e92
41633306328 2026-11-03T12:09:12Z 2026-11-03T12:09:36Z attempt=1 success be675a11dd
41620786413 2026-11-03T10:37:15Z 2026-11-03T10:37:37Z attempt=1 success 6949aa0f3b
41610142697 2026-11-03T09:19:54Z 2026-11-03T09:44:16Z attempt=4 success 8fd9921238
41539984711 2026-11-02T22:45:53Z 2026-11-02T22:46:16Z attempt=1 success 5418c2e54a
```

pr_cluster_test runs from 08:30 to 10:30 UTC: the first is at 09:57, after the window.
```
gh api '/repos/canton-network/splice/actions/workflows/pr_cluster_test.yml/runs?per_page=50&created=2026-11-03T08:30:00Z..2026-11-03T10:30:00Z' --jq '.workflow_runs[] | "\(.id) \(.created_at) attempt=\(.run_attempt) \(.event) \(.conclusion)"'
```
```
41618132726 2026-11-03T10:18:14Z attempt=1 issue_comment success
41618030702 2026-11-03T10:17:30Z attempt=1 issue_comment success
41617943661 2026-11-03T10:16:53Z attempt=1 issue_comment success
41617793960 2026-11-03T10:15:49Z attempt=1 issue_comment success
41615931739 2026-11-03T10:02:39Z attempt=1 issue_comment success
41615259507 2026-11-03T09:57:48Z attempt=1 issue_comment success
```

## 6. Recurrence: no other issue with this step since 2026-10-20

```
python3 -I -c '...' /run/sandbox/source/ci-triage/export/issues-since-2026-10-20.jsonl   # title or body contains trigger_cluster_test or "Failed to trigger cluster test"
```
```
11152 OPEN 2026-11-03T09:20 GHA Run 41610142697 : job deploy_cimain in trigger_cluster_test Failed :fire:
11153 OPEN 2026-11-03T09:25 GHA Run 41610142697 : job deploy_cimain in trigger_cluster_test Failed :fire:
11154 OPEN 2026-11-03T09:40 GHA Run 41610142697 : job deploy_cimain in trigger_cluster_test Failed :fire:
```

## Verdict

New occurrence in family J (infra): the cluster test invoker answered HTTP 500 to every trigger from 09:20 to at
least 09:40 UTC on 2026-11-03 and worked again at 09:44. First hit of this step. Flake, no fix in splice (the curl
retry already covers 500; a 20 min outage is not something to retry through). Why the invoker raised is NOT
verified: its logs are in GCP project da-cn-ci (the service behind CLUSTER_TEST_INVOKER_URL, defined in
canton-network-internal), which the sandbox cannot read; a likely cause is a failing upstream call (the CircleCI
pipeline API) that the invoker does not handle. If it recurs, check the invoker logs for 09:20-09:41 UTC on
2026-11-03. Note the job log of deploy_cimain itself was never reached; the CircleCI deploy for 8fd9921238 ran
from attempt 4.
