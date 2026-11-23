# 11660 - wall-clock-time (0) on release-line-0.10.1: Killed SBT after timeout 40m after a cold compile (run 44526310444)

DUPLICATE of 10358 (family J, release-line cold compile; also family E, missing backport). release-line-0.10.1
c12f83f ("Backport PR #8581 to release-line-0.10.1 (#8584)"), job wall-clock-time (0), Canton 3.7.2. No test failed
and there is no `Tests:` line: the precompiled-classes cache missed (as on every release-line run), sbt compiled from
12:47:34 to 13:00:46 (about 13 min) inside the timed step, the suites then progressed normally (no test ran longer
than 3 min 15 s) and sbt was killed by the 40 min `timeout` at 13:27:18 while ScanConnectionIntegrationTest was
running. The fix on main, splice#8319 (Test/compile in its own step before the timed step), is not on
release-line-0.10.1. The `custom container implementation failed` annotation is only the wrapper of the exit code;
the log has no `*** FAILED ***`.

- Run: https://github.com/canton-network/splice/actions/runs/44526310444, release-line-0.10.1 c12f83f ("Backport PR
  #8581 to release-line-0.10.1 (#8584)"), job 135568238346 `ci / scala_test_wall_clock_time / wall-clock-time (0)`,
  attempt 1.
- Runtime canton: 3.7.2.
- Component: infra (CI timeout budget on release lines).

## 0. Issue and job resolution

```
gh issue view 11660 -R DACH-NY/cn-test-failures --json title,body,state
```
```
OPEN GHA Run 44526310444 : job wall-clock-time (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/44526310444).
Job: wall-clock-time (0) in scala_test
Branch: [refs/heads/release-line-0.10.1](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.1)
Workflow: CI post-merge to main or release-line branches
Commit: [c12f83f](https://github.com/canton-network/splice/commit/c12f83fc7269e323a2a790d8ad7969992fa43539) Backport PR #8581 to release-line-0.10.1 (#8584)
Actor: pasindutennage-da
```
The run has two failed jobs; only wall-clock-time (0) is mapped to this ref. wall-clock-time (4) failed in the same
step at the same minute and was not analysed here (no ref given for it).
```
gh api "repos/canton-network/splice/actions/runs/44526310444/jobs?filter=all&per_page=100" --jq '.jobs[] | select(.conclusion=="failure") | "\(.id) attempt=\(.run_attempt) \(.name) started=\(.started_at) done=\(.completed_at) runner=\(.runner_name) failed=\([.steps[]|select(.conclusion=="failure")|.name]|join(","))"'
git show c12f83fc7269e323a2a790d8ad7969992fa43539:nix/canton-sources.json | grep -m1 version
```
```
135568238346 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (0) started=2026-11-19T12:43:32Z done=2026-11-19T13:29:41Z runner=self-hosted-k8s-large-runner-vmvf4 failed=Run Tests
135568238368 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (4) started=2026-11-19T12:43:38Z done=2026-11-19T13:29:42Z runner=self-hosted-k8s-large-runner-gs9x7 failed=Run Tests
  "version": "3.7.2",
```

## 1. Classification: timeout kill, no test result

Job log fetched with `gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135568238346/logs`,
ANSI stripped, at `log/11660/job.clean.log`.
```
L=log/11660/job.clean.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' $L | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id 59351db0-e417-11f1-93c5-a75d090846b5): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
```

## 2. Family confirming grep: cache miss, compile inside the timed step, kill at 40 min

```
grep -a -E 'Cache not found for input keys: classes|Cache restored from key: classes|compiling 9 Scala sources to .*project/target|compiling [0-9]+ Scala sources to .*apps/app/target/scala-2.13/test-classes|No tests to run for tools|Run completed in|Tests: succeeded|Killed SBT|FAILED \*\*\*' $L | grep -v echo | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-170
```
```
2026-11-19T12:46:30.788 Cache not found for input keys: classes-10 branch:release-line-0.10.1 dependencies:2ca2abc0374a6195ba450e2a6f489522eb3c1b0f4c0a5b1533bc677bcceed66
2026-11-19T12:47:34.928 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-19T12:59:30.288 [info] compiling 240 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/test-classes ...
2026-11-19T13:00:46.760 [info] No tests to run for tools / Test / testOnly
2026-11-19T13:27:18.469 Killed SBT after timeout 40m
```
The timed step started at 12:47:15; 13 min 12 s of it went to the compile, leaving 26.5 min for the suites.

## 3. The suites were progressing, not hung

Last `Test still running` line per test: no test exceeded 3 min 15 s, and four suites were reached.
```
grep -a -E 'Test still running' $L | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | awk '{print $1, $0}' | sed -E 's/^(\S+) .*after ([^:]+): suite name: ([A-Za-z]+), test name: (.{0,50}).*/\1 \3 | \4 | \2/' | awk -F' \\| ' '{k=$1" | "$2; sub(/^[^ ]+ /,"",k); last[k]=$0} END{for(k in last) print last[k]}' | sort
```
```
2026-11-19T13:03:16.624 ValidatorIntegrationTest | start and restart cleanly.  | 2 minutes, 28 seconds
2026-11-19T13:06:46.625 ValidatorIntegrationTest | initialize DSO and validator apps.  | 3 minutes, 15 seconds
2026-11-19T13:08:46.510 ValidatorIntegrationTest | validator apps connect to all DSO sequencers.  | 1 minute, 59 seconds
2026-11-19T13:15:16.816 ValidatorIntegrationTest | onboard, list and offboard users.  | 1 minute, 34 seconds
2026-11-19T13:20:16.776 ValidatorProxyIntegrationTest | validator should start and tap, using http forward | 1 minute, 22 seconds
2026-11-19T13:21:46.313 ValidatorProxyIntegrationTest | validator should bypass the proxy for hosts matche | 1 minute, 6 seconds
2026-11-19T13:24:46.392 UnclaimedSvRewardsScriptIntegrationTest | unclaimed_sv_rewards.py summarizes claimed, expire | 2 minutes, 30 seconds
2026-11-19T13:26:16.998 ScanConnectionIntegrationTest | amulet rules cache should be invalidated when the  | 1 minute, 14 seconds
```

## 4. The fix on main is not on the release line

```
git fetch origin release-line-0.10.1 main
git merge-base --is-ancestor 5c8e6ffcfa origin/release-line-0.10.1 && echo present || echo MISSING
git show origin/release-line-0.10.1:.github/actions/tests/scala_test/action.yml | grep -c 'Compile tests'
git log --oneline -3 origin/main -- .github/actions/tests/scala_test/action.yml
```
```
MISSING
0
b77153c658 Use DA BFT in all tests (#8234)
5c8e6ffcfa [ci] Compile the tests in their own step before the timed scala test step (#8319)
d1454efeb3 Add zizmor linting to gha (#8205)
```

## Verdict

Duplicate of 10358 (`Killed SBT after timeout 40m`, closed as completed 2026-11-06, the parent that 11208, 11212 and
11365 were closed against). Flake of the CI budget, not of a test: rerun the job. Lasting fix is infra-side, a
backport of splice#8319 to release-line-0.10.1; not written here (duplicate, no fix).

Not verified: the 366 MB log artifact was not downloaded (the job log alone carries the confirming grep and the
progress lines); wall-clock-time (4) of the same run; whether a rerun passed.
