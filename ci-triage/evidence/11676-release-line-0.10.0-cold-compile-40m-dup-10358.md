# 11676 - wall-clock-time (0) on release-line-0.10.0: Killed SBT after timeout 40m after an 11 min cold compile (run 44673845927)

DUPLICATE of 10358 (family J, release-line cold compile; also family E, missing backport). First hit recorded on
release-line-0.10.0; the same backport (#8521) produced 11674 and 11675 on release-line-0.10.1 in the sibling run
44673774254 at the same time. release-line-0.10.0 500817c ("Backport PR #8521 to release-line-0.10.0 (#8594)"), job
wall-clock-time (0), Canton 3.7.1. No test failed and there is no `Tests:` line: the precompiled-classes cache missed
(as on every release-line run, together with the sbt, npm and daml-artifacts caches), sbt built from 04:33:01 to
04:44:02 (11 min) inside the timed step, the suites then ran for 28 min 48 s with 37 tests succeeded and 0 failed,
and sbt was killed by the 40 min `timeout` at 05:12:50, 5 s into the seventh of eight suites. The fix on main,
splice#8319 (Test/compile in its own step before the timed step), is not on release-line-0.10.0. The `custom
container implementation failed` annotation is only the wrapper of the exit code; the log has no `*** FAILED ***`.

- Run: https://github.com/canton-network/splice/actions/runs/44673845927, release-line-0.10.0 500817c ("Backport PR
  #8521 to release-line-0.10.0 (#8594)"), job 136081884400 `ci / scala_test_wall_clock_time / wall-clock-time (0)`,
  runner self-hosted-k8s-large-runner-vjdgp.
- Runtime canton: 3.7.1.
- Component: infra (CI timeout budget on release lines).

## 0. Issue and job resolution

```
gh issue view 11676 -R DACH-NY/cn-test-failures --json title,body,state
```
```
OPEN GHA Run 44673845927 : job wall-clock-time (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/44673845927).
Job: wall-clock-time (0) in scala_test
Branch: [refs/heads/release-line-0.10.0](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.0)
Workflow: CI post-merge to main or release-line branches
Commit: [500817c](https://github.com/canton-network/splice/commit/500817c15d97e0a11daa5df67bd33e62f6d359e0) Backport PR #8521 to release-line-0.10.0 (#8594)
Actor: stephencompall-DA
```
The run has one failed job, the one of this ref (`Job:` line of the issue and the tuple given for the triage), and
one cancelled job, docker-compose (1), which is not part of this ref and was not looked at.
```
gh run view 44673845927 -R canton-network/splice --json jobs --jq '.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|"\(.databaseId) \(.conclusion) \(.name) started=\(.startedAt) done=\(.completedAt)"'
git show 500817c15d97e0a11daa5df67bd33e62f6d359e0:nix/canton-sources.json | grep -m1 version
```
```
136081660176 cancelled ci / scala_test_docker_compose / docker-compose (1) started=2026-11-20T04:25:18Z done=2026-11-20T05:20:19Z
136081884400 failure ci / scala_test_wall_clock_time / wall-clock-time (0) started=2026-11-20T04:26:23Z done=2026-11-20T05:17:02Z
  "version": "3.7.1",
```

## 1. Classification: timeout kill, no test result

Job log fetched with `gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/136081884400/logs`,
ANSI stripped, at `log/11676/job.ts.log`.
```
L=log/11676/job.ts.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' $L | sed -E 's/^[^Z]*Z //' | sort -u
grep -a -c 'FAILED \*\*\*' $L
```
```
##[error]Error: failed to run script step (id 6fd01e10-e49b-11f1-be44-79ad657ff08e): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
0
```

## 2. Family confirming grep: cache miss, compile inside the timed step, kill at 40 min

```
grep -a -E 'Cache not found for input keys: |Cache restored from key: |compiling 9 Scala sources to .*project/target|compiling [0-9]+ Scala sources to .*(apps/app/target/scala-2.13/(test-)?classes|canton/community/app-base)|No tests to run for tools|Run completed in|Tests: succeeded|Killed SBT|FAILED \*\*\*' $L | grep -v echo | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-150
```
```
2026-11-20T04:32:09.689 Cache not found for input keys: classes-10 branch:release-line-0.10.0 dependencies:77ed6c0326fb97e4f8360c01be6137529c4491488f9
2026-11-20T04:32:13.906 Cache not found for input keys: sbt-10 job=scala_test dependencies=fa5258d77e15f39c5c79c35a16875d470d34eb150b965cdb5c3abe5efc8
2026-11-20T04:32:15.665 Cache not found for input keys: frontend-npm-install-10 branch:release-line-0.10.0 dependencies:40920bb9089e280645154ac14976db
2026-11-20T04:32:19.882 Cache not found for input keys: daml-artifacts-10 branch:release-line-0.10.0 dependencies:62467593fd63592d180d38a0e399fd463a60
2026-11-20T04:33:01.354 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-20T04:39:16.121 [info] compiling 112 Scala sources to /__w/splice/splice/canton/community/app-base/target/scala-2.13/classes ...
2026-11-20T04:42:26.231 [info] compiling 21 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/classes ...
2026-11-20T04:43:04.427 [info] compiling 239 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/test-classes ...
2026-11-20T04:44:02.725 [info] No tests to run for tools / Test / testOnly
2026-11-20T05:12:50.146 Killed SBT after timeout 40m
```
The timed step started at 04:32:46 (`Run '/home/runner/k8s/index.js'` of the sbt step); 11 min 16 s of it went to the
build, which is the normal cold-compile time of the family (13 min in 11660 and 11661), not the 31-32 min of the
sibling run's 11674 and 11675. The remaining 28 min 48 s were not enough for the eight suites of the shard.

Suites assigned to the shard (split echo in the job log): ValidatorIntegrationTest, SplitwellIntegrationTest,
AmuletExpiryV1FallbackIntegrationTest, TokenStandardV2TransferIntegrationTest,
SvReconcileSynchronizerConfigIntegrationTest, WalletExpirationsIntegrationTest,
BatchedFeaturedAppActivityMarkerIntegrationTest, ScheduledPackageVettingIntegrationTest.

## 3. The suites were progressing, not hung

Suite timeline from the artifact `logs-wall-clock-time-0` (`canton_network_test.clog.gz`): six suites finished, the
seventh had just started, no test failed, the log has no ERROR line and its last line is 2 s before the kill.
```
T=log/11676/logs-wall-clock-time-0/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat $T | grep -a -E "Test (succeeded|failed): " | tail -1 | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat $T | grep -a -c "Test succeeded: '"; zcat $T | grep -a -c "Test failed: '"
zcat $T | tail -1 | cut -c1-60; zcat $T | grep -a -c '"level":"ERROR"'
```
```
2026-11-20T04:44:02.431Z Starting test suite 'ValidatorIntegrationTest'...",
2026-11-20T05:00:18.704Z Starting test suite 'SplitwellIntegrationTest'...",
2026-11-20T05:03:21.287Z Starting test suite 'AmuletExpiryV1FallbackIntegrationTest'...",
2026-11-20T05:04:59.767Z Starting test suite 'TokenStandardV2TransferIntegrationTest'...",
2026-11-20T05:08:29.950Z Starting test suite 'SvReconcileSynchronizerConfigIntegrationTest'...",
2026-11-20T05:11:09.876Z Starting test suite 'WalletExpirationsIntegrationTest'...",
2026-11-20T05:12:45.489Z Starting test suite 'BatchedFeaturedAppActivityMarkerIntegrationTest'...",
2026-11-20T05:12:30.249Z Test succeeded: 'WalletExpirationsIntegrationTest/A wallet should support expiring transfer offers'",
37
0
{"@timestamp":"2026-11-20T05:12:48.163Z","message":"Operatio
0
```
The longest suite is ValidatorIntegrationTest, 16 min 16 s for 17 tests (04:44:02 to 05:00:18), every test of it
succeeded; the longest single test is `initialize DSO and validator apps` at 3 min 18 s (04:46:29 to 04:49:46). No
test sat in a wait until the kill.

## 4. The fix on main is not on the release line

```
git fetch origin release-line-0.10.0 main
git log -1 --format='%h %s' 5c8e6ffcfa
git merge-base --is-ancestor 5c8e6ffcfa origin/release-line-0.10.0 && echo present || echo MISSING
git show origin/release-line-0.10.0:.github/actions/tests/scala_test/action.yml | grep -c 'name: Compile tests'
git show origin/main:.github/actions/tests/scala_test/action.yml | grep -n 'name: Compile tests'
git log --oneline -1 origin/release-line-0.10.0
```
```
5c8e6ffcfa [ci] Compile the tests in their own step before the timed scala test step (#8319)
MISSING
0
270:    - name: Compile tests
500817c15d Backport PR #8521 to release-line-0.10.0 (#8594)
```

## Verdict

Duplicate of 10358 ("Killed SBT after timeout 40m", closed as completed 2026-11-06, the parent of the family; 11660,
11661, 11674 and 11675 point to it). Not a test failure: a rerun of the job is expected to pass or to hit the same
budget, depending on build and test speed. Fix location: CI on the release line, a backport of splice#8319 to
release-line-0.10.0 (workflow change, not written here). Not verified: whether the two suites that did not run
would have passed; how long this shard takes on a passing release-line-0.10.0 run; the cancelled docker-compose (1)
job of the same run.
