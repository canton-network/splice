# 11674 - resource-intensive (0) on release-line-0.10.1: Killed SBT after timeout 40m after a 31 min cold compile (run 44673774254)

DUPLICATE of 10358 (family J, release-line cold compile; also family E, missing backport). Third hit on
release-line-0.10.1 after 11660 and 11661 (2026-11-19). release-line-0.10.1 5ddb73d ("Backport PR #8521 to
release-line-0.10.1 (#8595)"), job resource-intensive (0), Canton 3.7.2. No test failed and there is no `Tests:`
line: the precompiled-classes cache missed (as on every release-line run, together with the sbt, npm and
daml-artifacts caches), sbt compiled from 04:33:02 to 05:04:26 (about 31 min, against 13 min in 11660 and 11661)
inside the timed step, the first suite started at 05:04:26, both tests of ManualStartIntegrationTest succeeded, and
sbt was killed by the 40 min `timeout` at 05:12:20, 79 s into BootstrapPackageConfigIntegrationTest. The fix on
main, splice#8319 (Test/compile in its own step before the timed step), is not on release-line-0.10.1. The `custom
container implementation failed` annotation is only the wrapper of the exit code; the log has no `*** FAILED ***`.

- Run: https://github.com/canton-network/splice/actions/runs/44673774254, release-line-0.10.1 5ddb73d ("Backport PR
  #8521 to release-line-0.10.1 (#8595)"), job 136081554461 `ci / scala_test_resource_intensive / resource-intensive
  (0)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: infra (CI timeout budget on release lines).

## 0. Issue and job resolution

```
gh issue view 11674 -R DACH-NY/cn-test-failures --json title,body,state
```
```
OPEN GHA Run 44673774254 : job resource-intensive (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/44673774254).
Job: resource-intensive (0) in scala_test
Branch: [refs/heads/release-line-0.10.1](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.1)
Workflow: CI post-merge to main or release-line branches
Commit: [5ddb73d](https://github.com/canton-network/splice/commit/5ddb73d3d97a733a9098006cc2b7581a783388d7) Backport PR #8521 to release-line-0.10.1 (#8595)
Actor: stephencompall-DA
```
The run has three failed jobs; this ref is resource-intensive (0) by the `Job:` line of the issue. The other two
(wall-clock-time (8), roll-forward-lsu (1)) are not part of this packet and were not looked at.
```
gh api "repos/canton-network/splice/actions/runs/44673774254/jobs?filter=all&per_page=100" --jq '.jobs[] | select(.conclusion=="failure") | "\(.id) attempt=\(.run_attempt) \(.name) started=\(.started_at) done=\(.completed_at) runner=\(.runner_name) failed=\([.steps[]|select(.conclusion=="failure")|.name]|join(","))"'
git show 5ddb73d3d9:nix/canton-sources.json | grep -m1 version
```
```
136081352560 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (8) started=2026-11-20T04:24:34Z done=2026-11-20T05:13:01Z runner=self-hosted-k8s-large-runner-blskk failed=Run Tests
136081554461 attempt=1 ci / scala_test_resource_intensive / resource-intensive (0) started=2026-11-20T04:24:35Z done=2026-11-20T05:12:54Z runner=self-hosted-k8s-x-large-runner-2gv5d failed=Run Tests
136081791130 attempt=1 ci / scala_test_roll_forward_lsu / roll-forward-lsu (1) started=2026-11-20T04:25:48Z done=2026-11-20T04:54:45Z runner=self-hosted-k8s-x-large-runner-5rt7w failed=Run Tests
  "version": "3.7.2",
```

## 1. Classification: timeout kill, no test result

Job log fetched with `gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/136081554461/logs`,
ANSI stripped, at `log/11674/job.ts.log`.
```
L=log/11674/job.ts.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' $L | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id 5cc6eb00-e49b-11f1-9fc8-bdf03d836061): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
```

## 2. Family confirming grep: cache miss, compile inside the timed step, kill at 40 min

```
grep -a -E 'Cache not found for input keys: |Cache restored from key: |compiling 9 Scala sources to .*project/target|compiling [0-9]+ Scala sources to .*apps/app/target/scala-2.13/test-classes|No tests to run for tools|Run completed in|Tests: succeeded|Killed SBT|FAILED \*\*\*' $L | grep -v echo | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-150
```
```
2026-11-20T04:30:30.634 Cache not found for input keys: classes-10 branch:release-line-0.10.1 dependencies:77ed6c0326fb97e4f8360c01be6137529c4491488f9
2026-11-20T04:30:41.342 Cache not found for input keys: sbt-10 job=scala_test dependencies=fa5258d77e15f39c5c79c35a16875d470d34eb150b965cdb5c3abe5efc8
2026-11-20T04:30:45.117 Cache not found for input keys: frontend-npm-install-10 branch:release-line-0.10.1 dependencies:c3a69a3af791e0d14f4daafd0f593f
2026-11-20T04:30:56.642 Cache not found for input keys: daml-artifacts-10 branch:release-line-0.10.1 dependencies:77bdec234eab6d11e134f3c130e8d2ec2aa2
2026-11-20T04:33:02.890 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-20T05:03:20.870 [info] compiling 240 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/test-classes ...
2026-11-20T05:04:26.237 [info] No tests to run for tools / Test / testOnly
2026-11-20T05:12:20.990 Killed SBT after timeout 40m
```
The timed step started at 04:32:14; about 32 min of it went to the build, leaving 8 min for three suites. The same
four caches missed in 11660 and 11661, where the build took 13 min; here the same build milestones come about 2.5
times later (app-base compile 20.8 min after step start here, 6.4 min after the cache-restore lines in 11660):
```
grep -a -E 'compiling [0-9]+ Scala sources to .*(apps/app/target/scala-2.13/classes|canton/community/app-base)' log/11674/job.ts.log | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-150
grep -a -E 'compiling [0-9]+ Scala sources to .*(apps/app/target/scala-2.13/classes|canton/community/app-base)' log/11660/job.clean.log | cut -c1-150
```
```
2026-11-20T04:53:00.788 [info] compiling 112 Scala sources to /__w/splice/splice/canton/community/app-base/target/scala-2.13/classes ...
2026-11-20T04:59:01.889 [info] compiling 21 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/classes ...
2026-11-19T12:54:54.4866448Z [info] compiling 112 Scala sources to /__w/splice/splice/canton/community/app-base/target/scala-2.13/classes ...
2026-11-19T12:58:39.9854153Z [info] compiling 21 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/classes ...
```
Why this runner (self-hosted-k8s-x-large-runner-2gv5d) built 2.5 times slower was not established. `Run Tests` step
durations of the run's scala_test shards (top of the list): the passing wall-clock shards took 40 to 45 min of
their step, so the whole run was slow and close to the budget.
```
gh api "repos/canton-network/splice/actions/runs/44673774254/jobs?filter=all&per_page=100" --paginate --jq '.jobs[] | select(.name|test("scala_test")) | . as $j | (.steps[]|select(.name=="Run Tests")) | "\($j.conclusion) \(((.completed_at|fromdate)-(.started_at|fromdate))/60|floor)min \($j.name|sub("ci / ";"")) \($j.runner_name)"' | sort -k2 -n -r | head -8
```
```
failure 47min scala_test_wall_clock_time / wall-clock-time (8) self-hosted-k8s-large-runner-blskk
failure 47min scala_test_resource_intensive / resource-intensive (0) self-hosted-k8s-x-large-runner-2gv5d
success 45min scala_test_wall_clock_time / wall-clock-time (2) self-hosted-k8s-large-runner-45rxd
success 43min scala_test_wall_clock_time / wall-clock-time (0) self-hosted-k8s-large-runner-4wv2b
success 42min scala_test_record_time_tolerance / dyn-sync-params-reconciliation (0) self-hosted-k8s-medium-runner-gm9cv
success 41min scala_test_wall_clock_time / wall-clock-time (6) self-hosted-k8s-large-runner-ksmh4
success 40min scala_test_wall_clock_time / wall-clock-time (3) self-hosted-k8s-large-runner-nqn4r
success 38min scala_test_docker_compose / docker-compose (0) self-hosted-docker-large-runner-vqds6
```

## 3. The suites were progressing, not hung

Suite timeline from the artifact `logs-resource-intensive-0` (`canton_network_test.clog.gz`): both ManualStart
tests succeeded, the third test had run 79 s at the kill, the log has no ERROR line and its last line is 2 s before
the kill. (This rules out the ManualStart reconnect hang of 9151, which wedges the first test.)
```
T=log/11674/logs-resource-intensive-0/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat $T | tail -1 | cut -c1-60; zcat $T | grep -a -c '"level":"ERROR"'
```
```
2026-11-20T05:04:26.414Z Starting test suite 'ManualStartIntegrationTest'...",
2026-11-20T05:04:28.538Z Starting 'ManualStartIntegrationTest/Splice apps should start with uninitialized Canton nodes'...",
2026-11-20T05:08:11.119Z Test succeeded: 'ManualStartIntegrationTest/Splice apps should start with uninitialized Canton nodes'",
2026-11-20T05:08:16.295Z Starting 'ManualStartIntegrationTest/Splice apps should start with initialized Canton participants and fix signing keys if needed'...",
2026-11-20T05:10:58.368Z Test succeeded: 'ManualStartIntegrationTest/Splice apps should start with initialized Canton participants and fix signing keys if needed'",
2026-11-20T05:11:01.929Z Starting test suite 'BootstrapPackageConfigIntegrationTest'...",
2026-11-20T05:11:01.932Z Starting 'BootstrapPackageConfigIntegrationTest/Bootstrap with specific versions and then upgrade to latest'...",
{"@timestamp":"2026-11-20T05:12:18.541Z","message":"Request
0
```
SvOffboardingIntegrationTest, the third suite of the shard, never started.

## 4. The fix on main is not on the release line

```
git fetch origin release-line-0.10.1 main
git log -1 --format='%h %s' 5c8e6ffcfa
git merge-base --is-ancestor 5c8e6ffcfa origin/release-line-0.10.1 && echo present || echo MISSING
git show origin/release-line-0.10.1:.github/actions/tests/scala_test/action.yml | grep -c 'name: Compile tests'
git show origin/main:.github/actions/tests/scala_test/action.yml | grep -n 'name: Compile tests'
git log --oneline -1 origin/release-line-0.10.1
```
```
5c8e6ffcfa [ci] Compile the tests in their own step before the timed scala test step (#8319)
MISSING
0
270:    - name: Compile tests
5ddb73d3d9 Backport PR #8521 to release-line-0.10.1 (#8595)
```

## Verdict

Duplicate of 10358 (closed as completed 2026-11-06, the parent of the family; 11660 and 11661 point to it). Not a
test failure: a rerun of the job is expected to pass or to hit the same budget, depending on build speed. Fix
location: CI on the release line, a backport of splice#8319 to release-line-0.10.1 (workflow change, not written
here). Not verified: why the build on this runner took 31 min instead of 13; the other two failed jobs of the run.
