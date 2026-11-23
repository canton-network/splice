# 11675 - wall-clock-time (8) on release-line-0.10.1: Killed SBT after timeout 40m after a 32 min cold compile (run 44673774254)

DUPLICATE of 10358 (family J, release-line cold compile; also family E, missing backport). Fourth hit on
release-line-0.10.1 after 11660, 11661 (2026-11-19) and 11674 (the resource-intensive (0) shard of this same run).
release-line-0.10.1 5ddb73d ("Backport PR #8521 to release-line-0.10.1 (#8595)"), job wall-clock-time (8), Canton
3.7.2. No test failed and there is no `Tests:` line: the precompiled-classes cache missed (as on every release-line
run, together with the sbt, npm and daml-artifacts caches), sbt built from 04:33:05 to 05:05:14 (about 32 min,
against 13 min in 11660 and 11661) inside the timed step, the first of nine suites started at 05:05:15, and sbt was
killed by the 40 min `timeout` at 05:12:31, 7 min into the first test of SvOnboardingViaNonFoundingSvIntegrationTest,
which was still advancing through its steps. The fix on main, splice#8319 (Test/compile in its own step before the
timed step), is not on release-line-0.10.1. The `custom container implementation failed` annotation is only the
wrapper of the exit code; the log has no `*** FAILED ***`.

- Run: https://github.com/canton-network/splice/actions/runs/44673774254, release-line-0.10.1 5ddb73d ("Backport PR
  #8521 to release-line-0.10.1 (#8595)"), job 136081352560 `ci / scala_test_wall_clock_time / wall-clock-time (8)`,
  attempt 1, runner self-hosted-k8s-large-runner-blskk.
- Runtime canton: 3.7.2.
- Component: infra (CI timeout budget on release lines).

## 0. Issue and job resolution

```
gh issue view 11675 -R DACH-NY/cn-test-failures --json title,body,state
```
```
OPEN GHA Run 44673774254 : job wall-clock-time (8) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/44673774254).
Job: wall-clock-time (8) in scala_test
Branch: [refs/heads/release-line-0.10.1](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.1)
Workflow: CI post-merge to main or release-line branches
Commit: [5ddb73d](https://github.com/canton-network/splice/commit/5ddb73d3d97a733a9098006cc2b7581a783388d7) Backport PR #8521 to release-line-0.10.1 (#8595)
Actor: stephencompall-DA
```
The run has three failed jobs; this ref is wall-clock-time (8) by the `Job:` line of the issue and by the tuple given
for the triage. resource-intensive (0) is 11674 (same family); roll-forward-lsu (1) is 11673 (own packet, family BQ).
```
gh run view 44673774254 -R canton-network/splice --json jobs --jq '.jobs[]|select(.conclusion=="failure")|"\(.databaseId) \(.name) started=\(.startedAt) done=\(.completedAt)"'
git show 5ddb73d3d97a733a9098006cc2b7581a783388d7:nix/canton-sources.json | grep -m1 version
```
```
136081352560 ci / scala_test_wall_clock_time / wall-clock-time (8) started=2026-11-20T04:24:34Z done=2026-11-20T05:13:01Z
136081554461 ci / scala_test_resource_intensive / resource-intensive (0) started=2026-11-20T04:24:35Z done=2026-11-20T05:12:54Z
136081791130 ci / scala_test_roll_forward_lsu / roll-forward-lsu (1) started=2026-11-20T04:25:48Z done=2026-11-20T04:54:45Z
  "version": "3.7.2",
```

## 1. Classification: timeout kill, no test result

Job log fetched with `gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/136081352560/logs`,
ANSI stripped, at `log/11675/job.ts.log`.
```
L=log/11675/job.ts.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' $L | sed -E 's/^[^Z]*Z //' | sort -u
grep -a -c 'FAILED \*\*\*' $L
```
```
##[error]Error: failed to run script step (id 60621320-e49b-11f1-b993-39d5856cac5f): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
0
```

## 2. Family confirming grep: cache miss, compile inside the timed step, kill at 40 min

```
grep -a -E 'Cache not found for input keys: |Cache restored from key: |compiling 9 Scala sources to .*project/target|compiling [0-9]+ Scala sources to .*apps/app/target/scala-2.13/test-classes|No tests to run for tools|Run completed in|Tests: succeeded|Killed SBT|FAILED \*\*\*' $L | grep -v echo | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-150
```
```
2026-11-20T04:30:33.830 Cache not found for input keys: classes-10 branch:release-line-0.10.1 dependencies:77ed6c0326fb97e4f8360c01be6137529c4491488f9
2026-11-20T04:30:47.162 Cache not found for input keys: sbt-10 job=scala_test dependencies=fa5258d77e15f39c5c79c35a16875d470d34eb150b965cdb5c3abe5efc8
2026-11-20T04:30:51.262 Cache not found for input keys: frontend-npm-install-10 branch:release-line-0.10.1 dependencies:c3a69a3af791e0d14f4daafd0f593f
2026-11-20T04:31:02.239 Cache not found for input keys: daml-artifacts-10 branch:release-line-0.10.1 dependencies:77bdec234eab6d11e134f3c130e8d2ec2aa2
2026-11-20T04:33:05.991 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-20T05:04:12.535 [info] compiling 240 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/test-classes ...
2026-11-20T05:05:14.861 [info] No tests to run for tools / Test / testOnly
2026-11-20T05:12:31.176 Killed SBT after timeout 40m
```
The timed step (`timeout --kill-after=30s 40m`) started at 04:32:20; about 33 min of it went to the build, leaving
7 min for nine suites. The build milestones match the slow build of 11674 in the same run (app-base 04:53:00, apps-app
04:59:01 there) and are about 2.5 times later than in 11660 (app-base 6.4 min after the cache-restore lines):
```
grep -a -E 'compiling [0-9]+ Scala sources to .*(apps/app/target/scala-2.13/classes|canton/community/app-base)' $L | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-150
```
```
2026-11-20T04:54:58.931 [info] compiling 112 Scala sources to /__w/splice/splice/canton/community/app-base/target/scala-2.13/classes ...
2026-11-20T05:03:31.296 [info] compiling 21 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/classes ...
```
Why the build of this run was that slow was not established (two different runners, blskk here and 2gv5d in 11674,
show the same slowdown; the 11674 packet lists the passing wall-clock shards of the run at 40 to 45 min of the step).

Suites assigned to the shard (split echo in the job log): SvOnboardingViaNonFoundingSvIntegrationTest,
SvOnboardingIntegrationTest, TestTokenV2SettlementIntegrationTest, BootstrapPackageConfigDarUploadIntegrationTest,
SvDsoPartyManagementIntegrationTest, WalletAppConnectivityIntegrationTest, WalletBuyTrafficRequestIntegrationTest,
TokenStandardTransferIntegrationTest, DowngradeSvPackagesIntegrationTest. Only the first one started.

## 3. The suite was progressing, not hung

Suite timeline and the test's own clues from the artifact `logs-wall-clock-time-8` (`canton_network_test.clog.gz`):
the one started test was at its "Onboard SV3" step at the kill, the log has no ERROR line and its last line is 6 s
before the kill. The slow step is the DSO bootstrap with SV1 and SV2 (4 min 42 s), on the same loaded run.
```
T=log/11675/logs-wall-clock-time-8/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-230
zcat $T | tail -1 | cut -c1-60; zcat $T | grep -a -c '"level":"ERROR"'
```
```
2026-11-20T05:05:15.013Z Starting test suite 'SvOnboardingViaNonFoundingSvIntegrationTest'...",
2026-11-20T05:05:17.459Z Starting 'SvOnboardingViaNonFoundingSvIntegrationTest/A new SV can: 1) onboard via a non-sv1 while sv1 is offboarded from the DSO and 2) bootstrap using a sequencer that is not sv1's sequencer'...",
{"@timestamp":"2026-11-20T05:12:24.955Z","message":"Running 
0
```
```
zcat $T | grep -a -E '"logger_name":"[^"]*SvOnboardingViaNonFoundingSvIntegrationTest[^:/"]*"' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\2]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | grep -a -E '(Running|Finished) clue: (\(act\) Initialize DSO|\(check\) SV2 is now the only sv|Stop SV1|\(act\) Onboard SV3)' | cut -c1-120
```
```
2026-11-20T05:05:22.316Z Running clue: (act) Initialize DSO with SV1 and SV2",[DEBUG]
2026-11-20T05:10:04.347Z Finished clue: (act) Initialize DSO with SV1 and SV2",[DEBUG]
2026-11-20T05:10:59.725Z Running clue: (check) SV2 is now the only sv",[DEBUG]
2026-11-20T05:11:02.250Z Finished clue: (check) SV2 is now the only sv",[DEBUG]
2026-11-20T05:11:10.601Z Running clue: Stop SV1",[DEBUG]
2026-11-20T05:11:10.659Z Finished clue: Stop SV1",[DEBUG]
2026-11-20T05:11:10.659Z Running clue: (act) Onboard SV3",[DEBUG]
```

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

Duplicate of 10358 (closed as completed 2026-11-06, the parent of the family; 11660, 11661 and 11674 point to it).
Not a test failure: a rerun of the job is expected to pass or to hit the same budget, depending on build speed. Fix
location: CI on the release line, a backport of splice#8319 to release-line-0.10.1 (workflow change, not written
here). Not verified: why the build of this run took 32 min instead of 13; whether the one started test would have
passed.
