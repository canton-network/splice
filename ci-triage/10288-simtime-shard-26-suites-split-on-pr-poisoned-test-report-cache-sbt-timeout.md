# 10288 - simtime (0) killed at the 40 min sbt timeout: the split gave it 26 of 32 suites because a failing PR run had just overwritten the shared test-report cache with 0.2 s suite times (run 37449833406)

New (CI infra). Same class as 10285 / 10286 (bad cached suite times in `/cache/test-reports` unbalance the test
split), here with the writer identified. main 2eb5b5360e, job 112223866626, canton 3.6.1. No test failed: the
shard completed 23 of its 26 suites with no FAILED / ABORTED line and was killed by `timeout 40m` at 11:11:36. The
sim-time split job parsed `/cache/test-reports` at 10:28:00.67; 23 of the 24 sub-second times it read had been
copied there between 10:23:08 and 10:27:44 by the four failing `simtime (N)` shards of PR run 37447204670
(julien/7626-start-consuming-37-snapshot, "Start consuming 3.7"), whose suites aborted or failed within
fractions of a second. The same 23 suites had a summed time of 1991.7 s in the split of the previous main run
(37447049151, 10:03); in this split they summed to 18.5 s, so one bucket took 26 suites.

- Run: https://github.com/canton-network/splice/actions/runs/37449833406, main 2eb5b5360e ("Ignore 'Can't connect
  to database' in test logs (#7643)"), job 112223866626 `ci / scala_test_sim_time / simtime (0)`. Only failed job.
- Runtime canton: 3.6.1 (`git show 2eb5b5360e:nix/canton-sources.json | grep -m1 '"version"'`).
- Component: CI infra (`.github/actions/sbt/post_sbt/action.yml:53-62` writes every job's reports, PR or not, into
  the shared `/cache/test-reports`; `.github/actions/tests/split_tests/src/split_tests.ts:7-51` trusts them).
- Artifact: `logs-simtime-0` (433 MB) not downloaded: the job log and the split logs carry the whole mechanism.

## 1. Classification: sbt killed at 40 min, no failing test

```
$ gh api repos/canton-network/splice/actions/jobs/112223866626 --jq '"\(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)"'
failure 2026-10-06T10:28:21Z 2026-10-06T11:12:30Z self-hosted-k8s-large-4p78d-runner-rp9hg

$ gh api repos/canton-network/splice/actions/jobs/112223866626/logs > log/10288/job.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10288/job.log | grep -a -E 'FAILED \*\*\*|ABORTED|Tests: succeeded|contains problems|Killed SBT|^\S+ We are running' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++'
We are running 26 tests in this batch:
Killed SBT after timeout 40m
```

No `Tests:` summary, no FAILED / ABORTED. The sbt command runs under `timeout --kill-after=30s 40m`
(`.github/actions/sbt/execute_sbt_command/action.yml:26-29,71-74`). The shard was progressing to the end; the
"still running" notices walk through nine different suites, the last at 11:10:19:

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10288/job.log | grep -a -E 'Test still running|Killed SBT' | sed -E 's/^(\S+)Z /\1 /' | cut -c12-23,24-190 | awk '{t=$1; $1=""; print t $0}' | cut -c1-150
10:36:19.647 [info] *** Test still running after 1 minute, 29 seconds: suite name: TimeBasedTreasuryIntegrationTestWithoutMerging, test name: rewards from older
10:47:49.536 [info] *** Test still running after 1 minute, 9 seconds: suite name: TimeBasedTestNetPreviewIntegrationTest, test name: TestNet initializes correctl
10:49:19.022 [info] *** Test still running after 1 minute, 2 seconds: suite name: ScanWithGradualStartsTimeBasedIntegrationTest, test name: initialize a scan app
10:50:49.495 [info] *** Test still running after 1 minute, 23 seconds: suite name: NonZeroRoundBootstrapBftTimeBasedIntegrationTest, test name: SV triggers comp
10:55:49.503 [info] *** Test still running after 1 minute, 18 seconds: suite name: TrafficBasedRewardsDryRunTimeBasedIntegrationTest, test name: CIP-104 reward
11:07:49.711 [info] *** Test still running after 1 minute, 4 seconds: suite name: WalletRewardsTimeBasedIntegrationTest, test name: A wallet should list and au
11:10:19.128 [info] *** Test still running after 1 minute, 21 seconds: suite name: ValidatorFaucetCapZeroTimeBasedIntegrationTest, test name: system works with
11:11:36.428 Killed SBT after timeout 40m
```

(three repeated notices for the same tests omitted by the cut). 23 suites wrote a report before the kill:

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10288/job.log | grep -a -c -E "\.xml' -> '/cache"
23
```

The three without a report are SvTimeBasedOnboardingIntegrationTest, SvTimeBasedAmuletPriceIntegrationTest and
WalletAppRewardsTimeBasedIntegrationTest, the last three in the bucket (section 2).

## 2. The split: 26 suites in bucket 0, 2 in each other bucket

```
$ gh api repos/canton-network/splice/actions/jobs/112223440745/logs > log/10288/split.log    # ci / scala_test_sim_time / Split the tests into parallel runs, 10:27:13-10:28:04
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10288/split.log | grep -a -E 'test_reports_dir|bucket [0-9]+: [0-9]+ tests'
  test_reports_dir: /cache/test-reports
bucket 0: 26 tests, total time: 439.8809999999999
bucket 1: 2 tests, total time: 454.32500000000005
bucket 2: 2 tests, total time: 502.61199999999997
bucket 3: 2 tests, total time: 452.521
```

Per-suite estimate = difference between consecutive `added ... to bucket 0, total time:` lines (script in the
session; output abridged to bucket 0):

```
TimeBasedTreasuryIntegrationTestWithoutMerging  421.443
SvTimeBasedBootstrappingRoundIntegrationTest     12.958
SvExpiredRewardsCollectionTimeBasedIntegrationTest 0.473
SvTimeBasedRewardCouponIntegrationTest            0.452
... 20 more between 0.000 and 0.449 ...
WalletAppRewardsTimeBasedIntegrationTest          0.000
```

The splitter is greedy on the estimate (`split_tests.ts:54-64`), so after the one 421 s suite every 0.2 s suite
goes to bucket 0 until its total passes the next bucket's single real suite. Earlier main splits were balanced:

```
$ for R in 37315295211 37351458092 37385323284 37447049151 37449833406; do J=$(gh api "repos/canton-network/splice/actions/runs/$R/jobs?per_page=100" --jq '.jobs[]|select(.name=="ci / scala_test_sim_time / Split the tests into parallel runs")|.id'); echo "== $R"; gh api repos/canton-network/splice/actions/jobs/$J/logs | sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' | grep -a -E 'bucket [0-9]+: [0-9]+ tests'; done
== 37315295211          (2026-10-05 13:14)
bucket 0: 7 tests, total time: 900.2650000000001
bucket 1: 8 tests, total time: 899.3879999999999
bucket 2: 8 tests, total time: 899.68
bucket 3: 9 tests, total time: 894.589
== 37351458092          (2026-10-05 17:50)
bucket 0: 8 tests, total time: 873.9129999999999
...
== 37385323284          (2026-10-05 22:53)
bucket 0: 9 tests, total time: 884.925
...
== 37447049151          (2026-10-06 10:02, split 10:02:46-10:03:33)
bucket 0: 7 tests, total time: 852.79
bucket 1: 8 tests, total time: 847.633
bucket 2: 8 tests, total time: 847.099
bucket 3: 9 tests, total time: 845.057
== 37449833406          (this run, split 10:27:13-10:28:04)
bucket 0: 26 tests, total time: 439.8809999999999
...
```

Same suites, cached time in the 10:03 split versus this one (sub-second ones only):

```
suite                                                      10:03 split  this split
TrafficBasedRewardsDryRunTimeBasedIntegrationTest              171.040       0.212
TimeBasedTreasuryIntegrationTest                               141.940       0.207
WalletTimeBasedIntegrationTest                                 122.885       0.190
TimeBasedTestNetPreviewIntegrationTest                         119.904       0.449
WalletMintingDelegationTimeBasedIntegrationTest                108.115       0.219
ScanWithGradualStartsTimeBasedIntegrationTest                   97.463       0.440
ValidatorFaucetCapZeroTimeBasedIntegrationTest                  95.210       0.174
NonZeroRoundBootstrapBftTimeBasedIntegrationTest                87.316       0.407
SvTimeBasedOnboardingIntegrationTest                            82.771       0.019
WalletRewardsTimeBasedIntegrationTest                           72.635       0.181
TokenStandardCliTestDataTimeBasedIntegrationTest                59.960       0.315
TokenStandardMetadataTimeBasedIntegrationTest                   54.954       0.183
FollowAmuletConversionRateFeedTimeBasedIntegrationTest          47.944       0.208
WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest 47.789      0.178
SvTimeBasedAmuletPriceIntegrationTest                           43.690       0.013
SvExpiredRewardsCollectionTimeBasedIntegrationTest              43.673       0.473
ExternallySignedTxsTimeBasedIntegrationTest                     37.894       0.207
WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest        36.263       0.207
SvTimeBasedRewardCouponIntegrationTest                          35.995       0.452
DisabledWalletTimeBasedIntegrationTest                          34.847       0.172
FeaturedAppRightSwitchOverTimeBasedIntegrationTest               7.148       0.169
SvTimeBasedRewardCouponMissingPartyIntegrationTest               6.120       0.205
WalletAmuletPriceTimeBasedIntegrationTest                        0.008       0.200
WalletAppRewardsTimeBasedIntegrationTest                         0.000       0.000
b0 sum: same 26 suites 1991.7 s in the 10:03 split, 439.9 s in this one
```

At about 1.5x wall time per estimated second (normal shards: ~880 s estimate, 16-25 min), 1991.7 s does not fit
in a 40 min sbt budget; 23 of 26 suites did.

## 3. Who wrote the 0.2 s times: the failing sim-time shards of PR run 37447204670

Every scala_test job, PR runs included, copies its reports into the shared cache with plain `cp`
(no `if:` on the step):

```
$ git show 2eb5b5360e:.github/actions/sbt/post_sbt/action.yml | sed -n 53,62p
    - name: Collect test reports
      shell: bash
      run: |
        sudo mkdir -p /cache/test-reports
        sudo chown $(whoami):$(whoami) "/cache/test-reports"
        for subproject in `find ${{ inputs.splice_root }} -path "*/target/test-reports" | sed -e 's/^\.\///' -e 's/\/target\/test-reports$//'`
        do
          # `|| true` to avoid failing if there are no test reports
          cp -v "$subproject"/target/test-reports/TEST-*.xml /cache/test-reports/ || true
        done
```

Sim-time jobs of any workflow that finished between the good split (10:03:33) and this one:

```
$ gh api "repos/canton-network/splice/actions/runs?created=2026-10-06T07:00..2026-10-06T10:27&per_page=100" --jq '.workflow_runs[]|"\(.id) \(.name)"' | grep -v -E 'Static checks|Notify|Deploy|backport|Trigger a cluster' | while read R N; do gh api "repos/canton-network/splice/actions/runs/$R/jobs?per_page=100" --jq ".jobs[]|select(.completed_at!=null and .completed_at>=\"2026-10-06T10:03:00Z\" and .completed_at<=\"2026-10-06T10:28:00Z\" and (.name|test(\"simtime \\\\(\")))|\"$R \(.completed_at) \(.conclusion) \(.name) [$N]\""; done | sort -k2
37444264799 2026-10-06T10:03:51Z success ci / scala_test_sim_time / simtime (1) [CI post-merge to main or release-line branches]
37444264799 2026-10-06T10:06:58Z success ci / scala_test_sim_time / simtime (0) [CI post-merge to main or release-line branches]
37447204670 2026-10-06T10:23:22Z failure ci / scala_test_sim_time / simtime (1) [CI on PRs (Splice Contributors)]
37447204670 2026-10-06T10:26:10Z failure ci / scala_test_sim_time / simtime (0) [CI on PRs (Splice Contributors)]
37447049151 2026-10-06T10:27:15Z success ci / scala_test_sim_time / simtime (0) [CI post-merge to main or release-line branches]
37447204670 2026-10-06T10:27:51Z failure ci / scala_test_sim_time / simtime (2) [CI on PRs (Splice Contributors)]
37447204670 2026-10-06T10:28:00Z failure ci / scala_test_sim_time / simtime (3) [CI on PRs (Splice Contributors)]

$ gh api repos/canton-network/splice/actions/runs/37447204670 --jq '"\(.head_branch) \(.head_sha[0:10]) \(.display_title) \(.event)"'
julien/7626-start-consuming-37-snapshot b35bc0bd95 Start consuming 3.7 pull_request
```

The PR shards fail every test quickly (two of them shown):

```
$ for J in 112215199079 112215199283; do gh api repos/canton-network/splice/actions/jobs/$J/logs > log/10288/window/pr-$J.log; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10288/window/pr-$J.log | grep -a -E '^We are running|Suites: completed|Tests: succeeded' | awk '!s[$0]++'; done
We are running 7 tests in this batch:
[info] Suites: completed 5, aborted 2
[info] Tests: succeeded 0, failed 8, canceled 0, ignored 0, pending 0
We are running 6 tests in this batch:
[info] Suites: completed 4, aborted 2
[info] Tests: succeeded 0, failed 10, canceled 0, ignored 0, pending 0
```

Matching each sub-second suite of this split to the PR shard that copied its report (copy timestamp from the
`cp -v` line), against the split's parse time:

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10288/split.log | grep -a -E 'Parsing xml report' | sed -n '1p;$p' | cut -c1-28
2026-10-06T10:28:00.6732534Z
2026-10-06T10:28:00.7051129Z
$ for J in 112215199079 112215199283 112215199044 112215199058; do sed -E 's/\x1b\[[0-9;]*m//g' log/10288/window/pr-$J.log | grep -a -E "tests\.[A-Za-z]+\.xml' -> '/cache" | sed -E "s/^([0-9T:.-]+)Z.*tests\.([A-Za-z]+)\.xml.*/\1 \2/" | sed "s/^/$J /"; done > log/10288/pr-copies.txt   # then joined with the bucket-0 table
DisabledWalletTimeBasedIntegrationTest                          0.172 10:25:56.455 simtime (0) 112215199283
ExternallySignedTxsTimeBasedIntegrationTest                     0.207 10:27:35.070 simtime (2) 112215199044
FeaturedAppRightSwitchOverTimeBasedIntegrationTest              0.169 10:23:08.822 simtime (1) 112215199079
FollowAmuletConversionRateFeedTimeBasedIntegrationTest          0.208 10:27:44.394 simtime (3) 112215199058
NonZeroRoundBootstrapBftTimeBasedIntegrationTest                0.407 10:25:56.455 simtime (0) 112215199283
ScanWithGradualStartsTimeBasedIntegrationTest                   0.440 10:27:44.394 simtime (3) 112215199058
SvExpiredRewardsCollectionTimeBasedIntegrationTest              0.473 10:27:35.071 simtime (2) 112215199044
SvTimeBasedAmuletPriceIntegrationTest                           0.013 10:23:08.822 simtime (1) 112215199079
SvTimeBasedOnboardingIntegrationTest                            0.019 10:27:44.394 simtime (3) 112215199058
SvTimeBasedRewardCouponIntegrationTest                          0.452 10:27:44.394 simtime (3) 112215199058
SvTimeBasedRewardCouponMissingPartyIntegrationTest              0.205 10:27:35.071 simtime (2) 112215199044
TimeBasedTestNetPreviewIntegrationTest                          0.449 10:27:44.395 simtime (3) 112215199058
TimeBasedTreasuryIntegrationTest                                0.207 10:27:44.395 simtime (3) 112215199058
TokenStandardCliTestDataTimeBasedIntegrationTest                0.315 10:27:44.395 simtime (3) 112215199058
TokenStandardMetadataTimeBasedIntegrationTest                   0.183 10:25:56.455 simtime (0) 112215199283
TrafficBasedRewardsDryRunTimeBasedIntegrationTest               0.212 10:23:08.823 simtime (1) 112215199079
ValidatorFaucetCapZeroTimeBasedIntegrationTest                  0.174 10:23:08.823 simtime (1) 112215199079
WalletAmuletPriceTimeBasedIntegrationTest                       0.200 10:27:44.396 simtime (3) 112215199058
WalletMintingDelegationTimeBasedIntegrationTest                 0.219 10:27:35.071 simtime (2) 112215199044
WalletRewardsTimeBasedIntegrationTest                           0.181 10:23:08.823 simtime (1) 112215199079
WalletTimeBasedIntegrationTest                                  0.190 10:27:44.396 simtime (3) 112215199058
WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest        0.207 10:27:35.072 simtime (2) 112215199044
WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest 0.178 10:25:56.456 simtime (0) 112215199283
23 of 23 (WalletAppRewardsTimeBasedIntegrationTest excluded: it is 0.000 in the good split too)
```

Every collapsed time was written by the PR's failing shards, the last 16 s before the split read the directory.
The release-line 0.9.x shards that finished in the same window ran their suites normally (16 and 22 tests, 18 and
21 min) and are not the source.

## 4. Relation to 10285 / 10286

10285 / 10286 (run 37434381333, split 08:12) are the same failure class: wrong cached times for the
`roll-forward-lsu` suites (0) put both suites in one shard, and the empty shard ran every test. The sim-time
split of that run was also skewed (16 / 5 / 5 / 6 suites, 13 suites under 1 s, different values from this run),
so the cache was polluted then too; the writer for 08:12 was not identified here.

## Verdict

New ref, CI infra, flake (the outcome depends on which jobs wrote `/cache/test-reports` last). Not a test or
product failure: 23 suites ran clean. Not a duplicate of the earlier families; same class as 10285 / 10286, with the
writer identified this time: failing PR runs overwrite the shared timing cache that post-merge splits read.

Fix (CI, described, not written):
1. `.github/actions/sbt/post_sbt/action.yml:53-62`: only collect reports from post-merge main runs (or from suites
   that passed), so PR runs cannot write the cache main reads; or keep a per-branch cache directory.
2. `split_tests.ts:37-51`: ignore implausible times (e.g. below a floor, or a report whose suite failed / aborted;
   the JUnit XML carries `failures` / `errors` counts) and fall back to the max known time, as for unknown suites.
3. Independently of the cause, a split whose bucket totals differ by more than a factor (here 26 vs 2 suites) could
   fall back to round-robin by suite count.
Resolution for this run: rerun. No fix branch (CI change, not test-side).

Not verified: the contents of the XML files (`/cache/test-reports` is not visible; times are inferred from the
splitter's log and the `cp -v` lines); whether `/cache` is one shared volume or per node (the timing match within
16 s argues for shared); who polluted the 08:12 split of 10285 / 10286; that a rerun splits cleanly (it depends on
the cache state at that time).
