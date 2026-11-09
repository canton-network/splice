# 11231 - wall-clock-time (4) on release-line-0.9.4: Killed SBT after timeout 40m after a cold compile (run 42082967872)

DUPLICATE of 10358 (family 10358, cold compile inside the 40 min sbt budget), same as 11212 (closed as dup of 10358
on 2026-11-05). Release-line-0.9.4 post-merge run at dd989ad919, only wall-clock-time (4) failed. The classes cache
missed (release-line runs never hit it), sbt compiled from 18:30:17 to 18:42:19 (12 min), the tests then ran
normally (four suites progressed) until `timeout` killed sbt at 19:10:03. No `Tests:` line, no failing assertion.
The fix on main, splice #8319 (tests compiled in their own step before the timed step, merged 2026-11-05 21:06 UTC,
after this run), is not on release-line-0.9.4.

- Run: https://github.com/canton-network/splice/actions/runs/42082967872, release-line-0.9.4 dd989ad919
  ("Backport PR #8219 to release-line-0.9.4 (#8260)"), job 127441233361 attempt 1
  `ci / scala_test_wall_clock_time / wall-clock-time (4)`.
- Runtime canton: not relevant (no test failed; not checked).
- Component: infra (CI test step timeout).

## 1. Issue and job

Issue body (verbatim):
```
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42082967872).
Job: wall-clock-time (4) in scala_test
Branch: [refs/heads/release-line-0.9.4](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.9.4)
Workflow: CI post-merge to main or release-line branches
Commit: [dd989ad](https://github.com/canton-network/splice/commit/dd989ad9193153efea4a8fa8b0f50436c68e471a) Backport PR #8219 to release-line-0.9.4 (#8260)
Actor: stephencompall-DA
```
```
export NAME="wall-clock-time (4)"
gh api --paginate "repos/canton-network/splice/actions/runs/42082967872/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
```
```
127441233361 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (4)
```
Only failed job of the run:
```
gh api repos/canton-network/splice/actions/runs/42082967872/jobs --paginate --jq '.jobs[] | select(.conclusion=="failure") | [.id,.name]|@tsv'
```
```
127441233361	ci / scala_test_wall_clock_time / wall-clock-time (4)
```

## 2. Classification: sbt killed by the 40 min timeout, no test report

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11231/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id e951b420-d946-11f1-a026-3725d12e3425): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
```
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11231/job.log | grep -a -nE 'timeout --kill|Killed SBT|exit code 124' | cut -c1-200
```
```
11744:  timeout --kill-after=30s 40m "$***SBT_CMD[@]***" 2>&1 | tee sbt_output || status=$?
11747:      echo "Killed SBT after timeout 40m"
11959:2026-11-05T18:30:00.7069547Z   timeout --kill-after=30s 40m "$***SBT_CMD[@]***" 2>&1 | tee sbt_output || status=$?
11962:2026-11-05T18:30:00.7071016Z       echo "Killed SBT after timeout 40m"
15382:2026-11-05T19:10:03.7742028Z Killed SBT after timeout 40m
15383:2026-11-05T19:10:03.7742603Z Attempt 1 failed with exit code 124, retrying
```

## 3. Classes cache miss and 12 min compile inside the timed step

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11231/job.log | grep -a -n 'Cache not found for input keys: classes-' | cut -c1-200
```
```
10095:2026-11-05T18:29:17.7561591Z Cache not found for input keys: classes-10 branch:release-line-0.9.4 dependencies:2e7b78009d490e27e16b5a4eccceaf7879a8c29834d401694ca862aa3cc10092 rev:dd989ad9193153efea4a8fa8b0f50436c6
```
First compile and last `done compiling` (sbt started 18:30:00):
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11231/job.log | grep -a -E 'compiling [0-9]+ Scala sources|done compiling' | sed -n '1p;$p'
```
```
2026-11-05T18:30:17.2155007Z [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-05T18:42:19.5081612Z [info] done compiling
```
So 12 of the 40 minutes went to compiling; the integration tests had 28 min.

## 4. Tests were progressing, not hung

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11231/job.log | grep -a -oE 'suite name: [A-Za-z]+' | sort | uniq -c
```
```
      1 suite name: ExpiryWithNoVettedAmuletVersionIntegrationTest
      2 suite name: ScanEventHistoryIntegrationTest
      4 suite name: SvStateManagementIntegrationTest
      6 suite name: ValidatorReonboardingIntegrationTest
```
The slow-test notices move through four suites (18:43 SvStateManagement, 18:53 ScanEventHistory, 19:01
ValidatorReonboarding, 19:05 ExpiryWithNoVettedAmuletVersion), the last 4 min before the kill:
```
2026-11-05T19:05:49.2722050Z [info] *** Test still running after 1 minute, 13 seconds: suite name: ExpiryWithNoVettedAmuletVersionIntegrationTest, test name: Amulet expiry ignores parties with no vetted amulet version. 
```

## 5. Family match and fix state

`known-families.md`, 10358 family: `Killed SBT after timeout 40m` with no `Tests:` line after a classes cache miss;
release-line runs never hit the classes cache; 11208 and 11212 (2026-11-05) are release-line instances; fix
splice #8319 covers main only. Confirming grep (sections 2 and 3): `Killed SBT after timeout 40m` and
`Cache not found for input keys: classes-10 branch:release-line-0.9.4`, no `Tests:` line. Parent state:
```
gh issue view 10358 -R DACH-NY/cn-test-failures --json state,title,stateReason --jq '[.state,.stateReason,.title]|@tsv'
```
```
CLOSED	COMPLETED	Killed SBT after timeout 40m
```
#8319 merged after this run and is not on the release line:
```
gh pr view 8319 -R canton-network/splice --json mergeCommit,mergedAt,title --jq '[.mergeCommit.oid,.mergedAt,.title]|@tsv'
for b in release-line-0.9.4 main; do echo "$b: $(gh api repos/canton-network/splice/compare/5c8e6ffcfa3326822d3ef99b9c5166cac0283bf3...$b --jq .status)"; done
gh api "repos/canton-network/splice/contents/.github/actions/tests/scala_test/action.yml?ref=release-line-0.9.4" --jq .content | base64 -d | grep -n 'Compile tests' || echo "no 'Compile tests' step on release-line-0.9.4"
gh api "repos/canton-network/splice/contents/.github/actions/tests/scala_test/action.yml?ref=main" --jq .content | base64 -d | grep -n 'Compile tests'
```
```
5c8e6ffcfa3326822d3ef99b9c5166cac0283bf3	2026-11-05T21:06:37Z	[ci] Compile the tests in their own step before the timed scala test step
release-line-0.9.4: diverged
main: ahead
no 'Compile tests' step on release-line-0.9.4
270:    - name: Compile tests
```
(`diverged` for release-line-0.9.4 means the merge commit is not in its history; `ahead` for main means it is.)
Release-line-0.9.4 head is still the run's sha:
```
gh api "repos/canton-network/splice/commits?sha=release-line-0.9.4&per_page=3" --jq '.[]|[.sha[0:10],.commit.committer.date,(.commit.message|split("\n")[0])]|@tsv'
```
```
dd989ad919	2026-11-05T18:18:27Z	Backport PR #8219 to release-line-0.9.4 (#8260)
196579f20e	2026-11-05T11:39:34Z	Backport PR #8271 to release-line-0.9.4 (#8308)
44854b737c	2026-11-05T08:20:09Z	Backport PR #8248 to release-line-0.9.4 (#8253)
```

## 6. Other release-line-0.9.4 issues since 2026-11-01

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "release-line-0.9.4" created:>=2026-11-01' --jq '.items[]|[.number,.state,.created_at,.title]|@tsv'
```
```
11231	open	2026-11-05T19:11:20Z	GHA Run 42082967872 : job wall-clock-time (4) in scala_test Failed :fire:
11211	open	2026-11-05T09:11:56Z	checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
11207	open	2026-11-05T09:09:10Z	ValidatorReonboardingWithPartiesToMigrateIntegrationTest (0.9 release lines): tap 429 SUBMISSION_ALREADY_IN_FLIGHT, 7909 not backported
11227	closed	2026-11-05T12:38:08Z	GHA Run 42020755850 : job wall-clock-time (5) in scala_test Failed :fire:
11201	closed	2026-11-05T09:05:31Z	GHA Run 41993663804 : job docker-compose (1) in scala_test Failed :fire:
11212	closed	2026-11-05T09:12:10Z	GHA Run 41993663804 : job wall-clock-time (4) in scala_test Failed :fire:
11203	closed	2026-11-05T09:07:54Z	GHA Run 41993663804 : job wall-clock-time (9) in scala_test Failed :fire:
11200	closed	2026-11-05T09:05:20Z	GHA Run 41993663804 : job wall-clock-time (1) in scala_test Failed :fire:
```
Same failure: 11212 (Killed SBT after a cold compile, dup 10358, INDEX). Not the same: 11211 and 11227 (BFT orderer
`Sequencer is unhealthy` WARN; 11227 closed with the comment `Same unignored WARN: Sequencer is unhealthy, so
disconnecting all members. BFT orderer can not connect to database.`), 11207 (tap 429), 11200/11201/11203 (other
families of the 41993663804 burst, not re-checked here).

## Verdict

Duplicate of 10358 (cold compile eats 12 of the 40 sbt minutes; release lines never hit the classes cache). Flake
in CI infra, no test or app bug. Fix: backport splice #8319 (`.github/actions/tests/scala_test/action.yml`, the
`Compile tests` step) to the release lines; not present on release-line-0.9.4 as of dd989ad919. Second release-line-0.9.4
hit of this shape on 2026-11-05 (11212 earlier). Not verified: the normal duration of a passing wall-clock-time (4)
run on release-line-0.9.4 at this sha (no other run of the sha); the 11212 packet recorded 0:25-0:35 total for passing runs.
