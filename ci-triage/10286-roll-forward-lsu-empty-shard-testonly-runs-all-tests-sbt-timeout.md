# 10286 - roll-forward-lsu (1) got an empty test list and ran `testOnly` with no suites, which ran every test in the build until the 40 min sbt timeout (run 37434381333)

Same root cause as 10285, other shard. The test split for `scala_test_roll_forward_lsu` put both roll-forward suites
into bucket 0 and nothing into bucket 1, because the cached report times for both suites were 0 (10285 packet,
section 2; `split_tests.ts` since #7591 567cd541b6). Shard 1 therefore built the sbt command `testOnly   -- `. sbt's
`testOnly` without a test name runs all tests of every project, so the shard ran the unit and integration suites of
the whole build (1030 succeeded, 5 failed by the summary lines, 45 FAILED/ABORTED markers, mostly suites that need
infrastructure this job does not provide: CometBFT, docker-compose, S3, browsers). `execute_sbt_command` killed sbt at
its 40 min timeout ("Killed SBT after timeout 40m", exit 124). Not a flake in any test; a CI plumbing failure.

- Run: https://github.com/canton-network/splice/actions/runs/37434381333, main 08e28cbf6a ("Retry
  getPhysicalSynchronizerId in JoiningNodeInitializer (#7638)"), job 112173680751
  `ci / scala_test_roll_forward_lsu / roll-forward-lsu (1)`. Sibling job 112173680724 `roll-forward-lsu (0)` = 10285.
- Runtime canton: 3.6.1 (per the 10285 packet; not re-checked here, no Canton test ran in this shard's intended list).
- Component: CI (`.github/actions/tests/split_tests`, `.github/actions/tests/scala_test/action.yml`).
- Artifact: `logs-roll-forward-lsu-1` exists (270 MB); not downloaded, the job log is sufficient.

## 1. Job and failed step

```
$ gh api repos/canton-network/splice/actions/jobs/112173680751 --jq '"\(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)", (.steps[]|select(.conclusion!="success" and .conclusion!="skipped")|"step \(.number) \(.name) \(.conclusion) \(.started_at) \(.completed_at)")'
failure 2026-10-06T08:15:45Z 2026-10-06T09:00:18Z self-hosted-k8s-x-large-mlgvl-runner-pq7t2
step 4 Run Tests failure 2026-10-06T08:16:10Z 2026-10-06T09:00:14Z

$ gh api repos/canton-network/splice/actions/jobs/112173680751/logs > log/10286/job.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10286/job.log | sed -n '14833,14856p' | grep -a -E 'Killed|Attempt|Exceeded|##\[error\]' | sed -E 's/^[^Z]*Z //'
Killed SBT after timeout 40m
Attempt 1 failed with exit code 124, retrying
Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
##[error]Error: failed to run script step (id abf17250-c15e-11f1-b1ee-0f1f573db7e8): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

The second error annotation of the job (`The path for one of the files in artifact is not valid:
/_github_home/tmp/alice-validator...acs. Contains the following character: Colon :`, job.log:15431) is from the
"Upload runner temp directory" step after the tests and is a side effect of the extra suites writing ACS dumps to the
runner temp; the main `logs-roll-forward-lsu-1` artifact uploaded.

## 2. The shard got an empty test list

The split output (10285 packet section 2, `log/10285/split.log`) ended in `bucket 0: 2 tests, total time: 0` and
`bucket 1: 0 tests, total time: 0`. Shard 1 receives it as `test_names` with `runner_index: 1`:

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10286/job.log | grep -a -m1 -oE 'test_names: \[\[.*' | cut -c1-260; sed -E 's/\x1b\[[0-9;]*m//g' log/10286/job.log | grep -a -m1 -oE 'runner_index: [0-9]+'
test_names: [["org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuIntegrationTest","org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest"],[]]
runner_index: 1

$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10286/job.log | grep -a -n -E 'We are running [0-9]+ tests|cmd: "testOnly|SBT_CMD\+=\("testOnly' | cut -c1-120
10330:We are running 0 tests in this batch:
10412:  cmd: "testOnly   -- "
10468:  SBT_CMD+=("testOnly   -- ")
10683:  SBT_CMD+=("testOnly   -- ")
```

The command is assembled in `.github/actions/tests/scala_test/action.yml` at 08e28cbf6a; nothing checks for an
empty list:

```
$ git show 08e28cbf6a:.github/actions/tests/scala_test/action.yml | sed -n '163,186p' | grep -E 'splitted=|count=|We are running|/tmp/tests'
          splitted=$(echo "${{ toJson(inputs.test_names) }}" | jq -r '.[${{ inputs.runner_index }}].[]')
          count=$(echo "$tests" | wc -w)
          echo "We are running $count tests in this batch:"
          echo "$tests" > /tmp/tests
        echo "RUN_SPLITTED_TESTS_CMD=\"testOnly $(cat /tmp/tests) -- $tags\"" >> "$GITHUB_OUTPUT"
```

## 3. `testOnly` with no suite runs the whole build's tests

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10286/job.log | grep -a -E '^\[info\] Tests: succeeded' | awk '{s+=$4; f+=$6} END {print "succeeded",s,"failed",f}'
succeeded 1030 failed 5
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10286/job.log | grep -a -c -E '\*\*\* (FAILED|ABORTED) \*\*\*'
45
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10286/job.log | grep -a -n -E '\*\*\* (FAILED|ABORTED)|Failed tests:|Error during tests:' | sed -n '1,4p;6,9p;20,22p' | cut -c1-150
11790:[info] - should get the initial network config state *** FAILED ***
11837:[info] - should apply a network config change *** FAILED ***
11884:[info] - should read status *** FAILED ***
11931:[info] - should create dump *** FAILED ***
12054:[info] org.lfdecentralizedtrust.splice.scan.store.bulk.S3UploadTest *** ABORTED ***
12156:[info] org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageCommitFromStagingTest *** ABORTED ***
12211:[info] org.lfdecentralizedtrust.splice.integration.tests.WalletAmuletPriceTimeBasedIntegrationTest *** ABORTED ***
12279:[info] org.lfdecentralizedtrust.splice.integration.tests.runbook.NonDsoNonDevNetPreflightIntegrationTest *** ABORTED ***
12879:[error] Failed tests:
12881:[info] - docker-compose based localnet works for multiple synchronizers *** FAILED ***
12953:[info] - localnet supports configurable protocol versions *** FAILED ***
```

The suite listed under `Failed tests:` (job.log:12880) is `org.lfdecentralizedtrust.splice.sv.cometbft.CometBftClientIntegrationTest`.

Unit tests (`[info] Passed: Total 0` for the Daml projects, `Tests: succeeded 435`), CometBFT, S3/bulk-storage,
docker-compose, preflight, sim-time and frontend suites all ran in one JVM against this job's wall-clock Canton.
The failures are the expected result of running suites outside their own job's environment; none of them is
triaged individually.

## 4. Neither roll-forward suite ran here

The only mentions of the roll-forward suites in the shard-1 log are the split input lines (section 2); both ran
in shard 0 (10285).

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10286/job.log | grep -a -c 'RollForwardLsu'
3
```

## Verdict

- Same root cause as 10285 (split with cached times of 0 since #7591 puts both suites in bucket 0). 10285 is the
  shard that got both suites; 10286 is the shard that got none. Treat them as one incident; 10286 can be closed as a
  duplicate of 10285 if the tracker prefers one issue.
- Not a test flake. Fix location CI, two independent changes (described, not written):
  1. `split_tests.ts`: treat a non-positive cached time as unknown (as in the 10285 verdict), so the split stays
     balanced.
  2. `.github/actions/tests/scala_test/action.yml:163-186`: when `count` is 0, skip the sbt run (or fail fast with a
     clear message) instead of emitting `testOnly   -- `, which runs every test in the build. Without this, any
     shard count larger than the suite count, or any future bad split, silently turns into a 40 min full-build run.
- No fix branch (CI change, not test-side).
- Not verified: why both cached report times were 0 (same open question as 10285); the artifact was not downloaded;
  the 45 FAILED/ABORTED markers were not checked one by one beyond confirming they come from suites outside the
  roll-forward job.
