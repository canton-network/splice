# 11365 - wall-clock-time (5) on release-line-0.9.4: Killed SBT after timeout 40m after a cold compile (run 43043473742)

DUPLICATE of 10358 (family J, release-line cold compile; also family E, missing backport). release-line-0.9.4
62f3512 ("Manual Backport #7723 to 0.9.4 (#8438)"), job wall-clock-time (5), Canton 3.6.4. No test failed and no
`Tests:` line: the precompiled-classes cache missed (as on every release-line run), sbt compiled from 15:08:38 to
15:23:25 (about 15 min), the tests then ran normally and sbt was killed at the 40 min `timeout` at 15:48:20 while the
shard was still running suites. The fix on main, splice#8319 (Test/compile in its own step before the timed step), is
not on release-line-0.9.4. The run's `cancelled` conclusion comes from another job (check_canton_consistency hit its
15 min job timeout in Setup Nix), not from this one. Third such hit on release-line-0.9.4 (11212, 11231, 11365).

- Run: https://github.com/canton-network/splice/actions/runs/43043473742, release-line-0.9.4 62f3512 ("Manual Backport
  #7723 to 0.9.4 (#8438)"), job 130601863599 `ci / scala_test_wall_clock_time / wall-clock-time (5)`, attempt 1.
- Runtime canton: 3.6.4.
- Component: infra (CI timeout budget on release lines).

## 0. Issue and job resolution

```
gh api repos/DACH-NY/cn-test-failures/issues/11365 --jq '"\(.state) \(.title)\n\(.body)"'
```
```
open GHA Run 43043473742 : job wall-clock-time (5) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43043473742).
Job: wall-clock-time (5) in scala_test
Branch: [refs/heads/release-line-0.9.4](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.9.4)
Workflow: CI post-merge to main or release-line branches
Commit: [62f3512](https://github.com/canton-network/splice/commit/62f35124256f69fe1f55df05c9ecf1f768780c7d) Manual Backport #7723 to 0.9.4 (#8438)
Actor: pasindutennage-da
```
```
RUN=43043473742; export NAME='wall-clock-time (5)'; for p in 1 2 3 4; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select(.name == env.NAME or (.name | endswith(" / " + env.NAME))) | "\(.id) attempt=\(.run_attempt) \(.conclusion) \(.name) | started=\(.started_at) done=\(.completed_at) runner=\(.runner_name) | failedsteps=\([.steps[]|select(.conclusion=="failure" or .conclusion=="cancelled")|.name]|join(","))"'
git show 62f35124256f69fe1f55df05c9ecf1f768780c7d:nix/canton-sources.json | grep -m1 version
```
```
130601863599 attempt=1 failure ci / scala_test_wall_clock_time / wall-clock-time (5) | started=2026-11-11T14:58:35Z done=2026-11-11T15:49:21Z runner=self-hosted-k8s-large-runner-v4vb7 | failedsteps=Run Tests
  "version": "3.6.4",
```

## 1. Classification: timeout kill, no test result

Job log (ANSI stripped) at `log/inbox-1111b/130601863599.log`.
```
L=log/inbox-1111b/130601863599.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]|Killed SBT after' $L | sed -E 's/^[^Z]*Z //' | grep -v echo | sort -u
grep -a -E 'Cache not found for input keys: classes|compiling 9 Scala sources to .*project/target|compiling 234 Scala sources to .*apps/app/target/scala-2.13/test-classes|No tests to run for tools|Run completed in|Tests: succeeded|Killed SBT' $L | grep -v echo | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-140
```
```
##[error]Error: failed to run script step (id b99cff10-dde1-11f1-82a6-891e9393bed3): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
Killed SBT after timeout 40m
```
```
2026-11-11T15:07:32.635 Cache not found for input keys: classes-10 branch:release-line-0.9.4 dependencies:2e7b78009d490e27e16b5a4eccceaf7879
2026-11-11T15:08:38.932 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-11T15:22:03.229 [info] compiling 234 Scala sources to /__w/splice/splice/apps/app/target/scala-2.13/test-classes ...
2026-11-11T15:23:25.745 [info] No tests to run for tools / Test / testOnly
2026-11-11T15:48:20.934 Killed SBT after timeout 40m
```
The sbt command started at 15:08:17 (`execute_sbt_command`), compiled until 15:23:25, and was killed at 15:48:20.

## 2. The tests were progressing until the kill

```
sed -n '12842,$p' $L | sed -n '2518,2560p' | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | grep -a -E 'done compiling|still running after 1 minute, ([0-9]|[12][0-9]) seconds|Killed SBT|Attempt'
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' $L | grep -a -oE 'tests\.[A-Za-z0-9.]+$' | sed 's/tests\.//' | sort -u
```
```
2026-11-11T15:23:22.719 [info] done compiling
2026-11-11T15:23:25.745 [info] done compiling
2026-11-11T15:24:55.398 [info] *** Test still running after 1 minute, 28 seconds: suite name: ValidatorSequencerConnectionIntegrationTest, test name: validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers. 
2026-11-11T15:30:55.280 [info] *** Test still running after 1 minute, 20 seconds: suite name: SvOfflineRootNamespaceKeyIntegrationTest, test name: start svs with offline root namespace keys. 
2026-11-11T15:35:55.250 [info] *** Test still running after 1 minute, 24 seconds: suite name: BootstrapPackageConfigDarUploadWithUnsupportedVersionsIntegrationTest, test name: Bootstrap with specific versions and check that no newer DARs have been uploaded. 
2026-11-11T15:39:24.707 [info] *** Test still running after 1 minute, 9 seconds: suite name: BootstrapPackageConfigDarUploadIntegrationTest, test name: Bootstrap with specific versions and check that no newer DARs have been uploaded. 
2026-11-11T15:41:54.606 [info] *** Test still running after 1 minute, 9 seconds: suite name: ExternallySignedPartyOnboardingTest, test name: a ccsp provider should should be able to onboard a party with externally signed topology transactions. 
2026-11-11T15:48:20.934 Killed SBT after timeout 40m
2026-11-11T15:48:20.934 Attempt 1 failed with exit code 124, retrying
```
```
BatchedFeaturedAppActivityMarkerIntegrationTest
BootstrapPackageConfigDarUploadIntegrationTest
BootstrapPackageConfigDarUploadWithUnsupportedVersionsIntegrationTest
ExternallySignedPartyOnboardingTest
TokenStandardCliIntegrationTest
ValidatorSequencerConnectionIntegrationTest
WalletMetricsTest
WalletTransferOfferStatusIntegrationTest
connectivity.SvNoSynchronizerConnectivityIntegrationTest
offlinekey.SvOfflineRootNamespaceKeyIntegrationTest
```
New suites kept starting (one every few minutes) up to 15:41:54; no stuck test (the longest "still running" was
ValidatorSequencerConnectionIntegrationTest's 6 min test, ended before 15:30).

## 3. Passing runs of the same shard on release-line-0.9.4 had no margin either

Passing wall-clock-time (5) jobs on 0.9.4 runs of 2026-11-05 (job ids from the run's job list):
```
for RUN in 42082967872 41993663804; do for p in 1 2; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select(.name|test("/ wall-clock-time \\(5\\)$")) | "\(.id) \(.conclusion)"'; done
for J in 127441233355 127132238784; do gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/$J/logs | sed -E 's/\x1b\[[0-9;]*m//g' > log/11365/wct5-$J.log; echo "== $J"; grep -a -E 'Cache not found for input keys: classes|compiling 9 Scala sources to .*project/target|No tests to run for tools|Run completed in|Tests: succeeded|Killed SBT' log/11365/wct5-$J.log | grep -v echo | sed -E 's/^([0-9T:.-]+)[0-9]{4}Z /\1 /' | cut -c1-140; done
```
```
127441233355 success
127132238784 success
== 127441233355
2026-11-05T18:27:38.734 Cache not found for input keys: classes-10 branch:release-line-0.9.4 dependencies:2e7b78009d490e27e16b5a4eccceaf7879
2026-11-05T18:28:37.864 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-05T18:40:42.797 [info] No tests to run for tools / Test / testOnly
2026-11-05T19:03:40.986 [info] Run completed in 22 minutes, 59 seconds.
2026-11-05T19:03:40.986 [info] Tests: succeeded 16, failed 0, canceled 0, ignored 0, pending 0
== 127132238784
2026-11-05T08:29:36.655 Cache not found for input keys: classes-10 branch:release-line-0.9.4 dependencies:2e7b78009d490e27e16b5a4eccceaf7879
2026-11-05T08:30:35.205 [info] compiling 9 Scala sources to /__w/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-11-05T08:43:05.523 [info] No tests to run for tools / Test / testOnly
2026-11-05T09:09:28.162 [info] Run completed in 26 minutes, 23 seconds.
2026-11-05T09:09:28.163 [info] Tests: succeeded 29, failed 0, canceled 0, ignored 0, pending 0
```
Passing runs: compile about 12 min plus tests 23 to 26 min, i.e. 35 and 39 min of the 40 min budget. This run's
compile took about 15 min (15:08:38 to 15:23:25), leaving 25 min for tests.

## 4. The fix on main is not on release-line-0.9.4

```
git fetch --no-tags -q https://github.com/canton-network/splice.git release-line-0.9.4:refs/remotes/origin/release-line-0.9.4 main:refs/remotes/https-origin/main
git log --oneline -1 origin/release-line-0.9.4
git show 62f35124256f69fe1f55df05c9ecf1f768780c7d:.github/actions/tests/scala_test/action.yml | grep -n -E 'name: Compile tests'
git show https-origin/main:.github/actions/tests/scala_test/action.yml | grep -n -E 'name: Compile tests'
gh api repos/canton-network/splice/pulls/8319 --jq '"\(.title) merged=\(.merged_at) \(.merge_commit_sha)"'
git merge-base --is-ancestor 5c8e6ffcfa3326822d3ef99b9c5166cac0283bf3 origin/release-line-0.9.4 && echo present || echo MISSING
gh api -X GET search/issues -f q='repo:canton-network/splice is:pr "Compile the tests in their own step"' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title)"'
```
```
62f3512425 Manual Backport #7723 to 0.9.4 (#8438)
270:    - name: Compile tests
[ci] Compile the tests in their own step before the timed scala test step merged=2026-11-05T21:06:37Z 5c8e6ffcfa3326822d3ef99b9c5166cac0283bf3
MISSING
8319 closed 2026-11-05 [ci] Compile the tests in their own step before the timed scala test step
```
The release line head is the run's sha; its action.yml has no `Compile tests` step (no grep output), main has it at
line 270; no backport PR of #8319 exists.

## 5. Why the run is `cancelled`

```
for p in 1 2 3; do gh api "repos/canton-network/splice/actions/runs/43043473742/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select(.conclusion != "success" and .conclusion != "skipped") | "\(.id) \(.conclusion) \(.started_at[11:19])-\(.completed_at[11:19]) \(.name)"'
gh api repos/canton-network/splice/actions/jobs/130601139864 --jq '.steps[]|"\(.name) \(.conclusion) \(.started_at[11:19])-\(.completed_at[11:19])"' | sed -n 4p
git show 62f35124256f69fe1f55df05c9ecf1f768780c7d:.github/workflows/build.static_tests.yml | grep -n -A6 'check_canton_consistency:' | grep -E 'check_canton|timeout'
grep -a -E 'Received SIGINT' log/inbox-1111b/130601139864.log
```
```
130601139864 cancelled 14:57:31-15:12:36 ci / static_tests / check_canton_consistency
130601863599 failure 14:58:35-15:49:21 ci / scala_test_wall_clock_time / wall-clock-time (5)
Setup Nix failure 14:57:55-15:12:32
18:  check_canton_consistency:
20-    timeout-minutes: 15
2026-11-11T15:12:31.9506706Z ##[warning]Received SIGINT, terminating
```
check_canton_consistency was cancelled by its 15 min job timeout in Setup Nix; that is unrelated to this shard
(not investigated further, no issue filed for it).

## 6. Duplicate check

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "release-line-0.9.4" created:>=2026-11-01' --jq '.items[]|"\(.number) \(.state)/\(.state_reason) \(.created_at[0:10]) \(.title[0:90])"'
awk -F'\t' '$1==11208||$1==11212' ci-triage/evidence/INDEX.tsv | cut -f1,4,7,8
```
(Output of the search, then the two INDEX rows.)
```
11211 open/null 2026-11-05 checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
11207 open/null 2026-11-05 ValidatorReonboardingWithPartiesToMigrateIntegrationTest (0.9 release lines): tap 429 SUBM
11365 open/null 2026-11-11 GHA Run 43043473742 : job wall-clock-time (5) in scala_test Failed :fire:
11231 closed/duplicate 2026-11-05 GHA Run 42082967872 : job wall-clock-time (4) in scala_test Failed :fire:
11227 closed/duplicate 2026-11-05 GHA Run 42020755850 : job wall-clock-time (5) in scala_test Failed :fire:
11201 closed/duplicate 2026-11-05 GHA Run 41993663804 : job docker-compose (1) in scala_test Failed :fire:
11212 closed/duplicate 2026-11-05 GHA Run 41993663804 : job wall-clock-time (4) in scala_test Failed :fire:
11203 closed/duplicate 2026-11-05 GHA Run 41993663804 : job wall-clock-time (9) in scala_test Failed :fire:
11200 closed/duplicate 2026-11-05 GHA Run 41993663804 : job wall-clock-time (1) in scala_test Failed :fire:
11311 closed/completed 2026-11-09 ciperiodic deploys: canton-network/splice#8315 rejects the per-SV cometbft config that the
11208	release-line-0.9.x cf21adf939 (Backport PR #8248)	Killed SBT after timeout 40m at 09:08:51, no Tests line; classes cache miss (every release-line run), compile 08:28:47-08:41:23; normal 0:30-0:35 total; burst load.	10358
11212	release-line-0.9.4 44854b737c (Backport PR #8248)	Killed SBT after timeout 40m at 09:10:55, no Tests line; classes cache miss, compile 08:30:51-08:43:36; normal 0:25-0:35 total; burst load.	10358
```
Same failure: 11212 and 11231 on release-line-0.9.4, 11208 on release-line-0.9.x (all `Killed SBT after timeout 40m`
after a classes cache miss, closed as dup of 10358; 11231 per round 2026-11-09T084535Z-nightly, g4-rl094). The other
0.9.4 hits (11207, 11211, 11227, 11200, 11201, 11203, 11311) are different failures or not checked here. Parent 10358
is closed (completed by #8319 on main).

## Verdict

Duplicate of 10358 (family J cold compile, release line). Not a test failure: a 40 min sbt budget that a release-line
shard with a cold compile (12 to 15 min) plus 23 to 26 min of tests does not fit. Fix: backport splice#8319
(5c8e6ffcfa, `Compile tests` step in `.github/actions/tests/scala_test/action.yml`) to release-line-0.9.4 (and the
other active release lines, family J note of 2026-11-06), which needs a decision by the user: nobody tracks it since
10358 closed (open question from 2026-11-09). Not verified: why this compile took 15 min instead of 12 (runner load).
