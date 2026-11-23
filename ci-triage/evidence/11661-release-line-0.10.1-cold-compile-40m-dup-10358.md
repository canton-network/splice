# 11661 - wall-clock-time (4) on release-line-0.10.1: Killed SBT after timeout 40m after a cold compile (run 44526310444)

DUPLICATE of 10358 (family J, release-line cold compile; also family E, missing backport). Sibling of 11660, the
wall-clock-time (0) shard of the same run. release-line-0.10.1 c12f83f ("Backport PR #8581 to release-line-0.10.1
(#8584)"), job wall-clock-time (4), Canton 3.7.2. No test failed and there is no `Tests:` line: the
precompiled-classes cache missed (as on every release-line run), sbt compiled from 12:49:09 to 13:01:59 (about
13 min) inside the timed step, ten suites then started and every finished test succeeded (0 `Test failed` in the
test log), and sbt was killed by the 40 min `timeout` at 13:28:56, 12 s after a test of
WalletTxLogWithSynchronizerFeesIntegrationTest succeeded. The fix on main, splice#8319 (Test/compile in its own step
before the timed step), is not on release-line-0.10.1. The `custom container implementation failed` annotation is
only the wrapper of the exit code; the log has no `*** FAILED ***`.

- Run: https://github.com/canton-network/splice/actions/runs/44526310444, release-line-0.10.1 c12f83f ("Backport PR
  #8581 to release-line-0.10.1 (#8584)"), job 135568238368 `ci / scala_test_wall_clock_time / wall-clock-time (4)`,
  attempt 1.
- Runtime canton: 3.7.2.
- Component: infra (CI timeout budget on release lines).

## 0. Issue and job resolution

```
gh issue view 11661 -R DACH-NY/cn-test-failures --json title,body,state
```
```
OPEN GHA Run 44526310444 : job wall-clock-time (4) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/44526310444).
Job: wall-clock-time (4) in scala_test
Branch: [refs/heads/release-line-0.10.1](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.1)
Workflow: CI post-merge to main or release-line branches
Commit: [c12f83f](https://github.com/canton-network/splice/commit/c12f83fc7269e323a2a790d8ad7969992fa43539) Backport PR #8581 to release-line-0.10.1 (#8584)
Actor: pasindutennage-da
```
The run has two failed jobs; wall-clock-time (0) is 11660 (its own packet), wall-clock-time (4) is this ref, both by
the `Job:` line of the issue.
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

Job log fetched with `gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135568238368/logs`,
ANSI stripped, at `log/11661/job.clean.log`.
```
L=log/11661/job.clean.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' $L | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id 92ede140-e417-11f1-9f9a-e32a207f2859): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
```

## 2. Family confirming grep: cache miss, compile inside the timed step, kill at 40 min

```
grep -a -E 'Cache not found for input keys: classes|Cache restored from key: classes|compiling 9 Scala sources to .*project/target|compiling [0-9]+ Scala sources to .*apps/app/target/scala-2.13/test-classes|No tests to run for tools|Run completed in|Tests: succeeded|Killed SBT|FAILED \*\*\*' $L | grep -v echo | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-170
```
```
2026-11-19T12:48:08.228 Cache not found for input keys: classes-10 branch:release-line-0.10.1 dependencies:2ca2abc0374a6195ba450e2a6f489522eb3c1b0f4c0a5b1533bc677bcceed66
2026-11-19T12:49:09.738 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-19T13:00:45.527 [info] compiling 240 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/test-classes ...
2026-11-19T13:01:59.976 [info] No tests to run for tools / Test / testOnly
2026-11-19T13:28:56.554 Killed SBT after timeout 40m
```
The timed step started at 12:48:52; about 13 min of it went to the compile, leaving 27 min for the suites.

## 3. The suites were progressing, not hung

Last `Test still running` line per test in the job log: no test exceeded 3 min 45 s.
```
grep -a -E 'Test still running' $L | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | awk '{print $1, $0}' | sed -E 's/^(\S+) .*after ([^:]+): suite name: ([A-Za-z0-9]+), test name: (.{0,50}).*/\1 \3 | \4 | \2/' | awk -F' \\| ' '{k=$1" | "$2; sub(/^[^ ]+ /,"",k); last[k]=$0} END{for(k in last) print last[k]}' | sort
```
```
2026-11-19T13:05:29.762 GovernanceLockIntegrationTest | A GovernanceLock substitution created via TSv1 can | 3 minutes, 29 seconds
2026-11-19T13:16:30.350 SequencerPruningIntegrationTest | sequencer can be pruned even if a participant is d | 3 minutes, 45 seconds
2026-11-19T13:19:59.988 Ans4SvsIntegrationTest | ans should terminated subscriptions are archived.  | 3 minutes, 0 seconds
2026-11-19T13:24:29.692 TokenStandardCliIntegrationTest | Token Standard CLI should execute transfers betwee | 1 minute, 24 seconds
```
Suite timeline from the artifact `logs-wall-clock-time-4` (`canton_network_test.clog.gz`): ten suites started, 18
tests succeeded, none failed, and the last test finished 12 s before the kill.
```
T=log/11661/logs-wall-clock-time-4/canton_network_test.clog.gz
zcat < $T | grep -a -E "Starting test suite|Finished test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-190 > log/11661/timeline.txt
grep -c 'Test succeeded' log/11661/timeline.txt; grep -c 'Test failed' log/11661/timeline.txt
grep 'Starting test suite' log/11661/timeline.txt; tail -4 log/11661/timeline.txt
```
```
18
0
2026-11-19T13:02:00.403Z Starting test suite 'GovernanceLockIntegrationTest'...
2026-11-19T13:12:43.700Z Starting test suite 'SequencerPruningIntegrationTest'...
2026-11-19T13:16:58.692Z Starting test suite 'Ans4SvsIntegrationTest'...
2026-11-19T13:20:11.790Z Starting test suite 'SvOnboardingVettingIntegrationTest'...
2026-11-19T13:21:01.277Z Starting test suite 'AmuletExpiryV1FallbackIntegrationTest'...
2026-11-19T13:22:26.438Z Starting test suite 'TokenStandardCliIntegrationTest'...
2026-11-19T13:24:50.088Z Starting test suite 'SvMergeSvRewardStateIntegrationTest'...
2026-11-19T13:25:37.274Z Starting test suite 'WalletExpirationsIntegrationTest'...
2026-11-19T13:27:00.531Z Starting test suite 'BatchedFeaturedAppActivityMarkerIntegrationTest'...
2026-11-19T13:28:06.440Z Starting test suite 'WalletTxLogWithSynchronizerFeesIntegrationTest'...
2026-11-19T13:27:51.533Z Test succeeded: 'BatchedFeaturedAppActivityMarkerIntegrationTest/Batched activity marker creation produces only one view'
2026-11-19T13:28:06.440Z Starting test suite 'WalletTxLogWithSynchronizerFeesIntegrationTest'...
2026-11-19T13:28:39.915Z Starting 'WalletTxLogWithSynchronizerFeesIntegrationTest/A wallet should handle domain fees that have been paid (in a DevNet cluster)'...
2026-11-19T13:28:44.825Z Test succeeded: 'WalletTxLogWithSynchronizerFeesIntegrationTest/A wallet should handle domain fees that have been paid (in a DevNet cluster)'
```

## 4. The fix on main is not on the release line

```
git fetch origin release-line-0.10.1 main
git merge-base --is-ancestor 5c8e6ffcfa origin/release-line-0.10.1 && echo present || echo MISSING
git show origin/release-line-0.10.1:.github/actions/tests/scala_test/action.yml | grep -c 'Compile tests'
git log --oneline -1 origin/release-line-0.10.1
```
```
MISSING
0
c12f83fc72 Backport PR #8581 to release-line-0.10.1 (#8584)
```

## Verdict

Duplicate of 10358 (`Killed SBT after timeout 40m`, closed as completed 2026-11-06, not as a duplicate; the parent
that 11208, 11212 and 11365 were closed against and that 11660 points to). Flake of the CI budget, not of a test:
rerun the job. Lasting fix is infra-side, a backport of splice#8319 to release-line-0.10.1; not written here
(duplicate, no fix).

Not verified: whether a rerun passed; `canton.clog.gz` was not read (no test failed and nothing hung).
