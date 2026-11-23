# 11651 - daml_test: splitwell-test `Failed to create script context`, gRPC Deadline Exceeded (run 44498711970)

New, no family matched (now family BL). main 7666260d8d ("Add syft to nix (#8554)"), job `ci / daml_test / daml_test`.
`sbt damlTest` failed after 591 s with `damlc test failed /__w/splice/splice/daml/splitwell-test`: the only test
module of that package, `daml/Splice/Scripts/TestSplitwell.daml`, never ran because damlc could not create the script
context in its script service within the gRPC deadline (`StatusDeadlineExceeded`), 75 s after the test process
started. All other packages passed. The same module takes 16-18 s end to end in the two passing runs compared below.
In the failing run its start overlapped the damlc test runs of splice-amulet-test and splice-wallet-test, a third
long damlc test run and one DAR build; in the passing runs it started after those had finished. No Daml or test code
is involved; the same signature was seen once before (6724, closed as duplicate of the catch-all 6681 "Random CI job
things are very slow", closed completed 2026-02-11). Why the script service of this one process needed more than
60 s is not shown by the job log (no runner CPU or memory data read here).

- Run: https://github.com/canton-network/splice/actions/runs/44498711970, main 7666260d8d ("Add syft to nix (#8554)"),
  job 135472484964 `ci / daml_test / daml_test`, runner `self-hosted-k8s-small-runner-p9cnw`.
- Runtime canton: not involved (damlc via dpm, `sdk-version: 3.5.2` in `daml/splitwell-test/daml.yaml`).
- Component: infra / build tooling (damlc script service under concurrent damlc test processes on the small runner).
- Not in the tuple: the same run also failed `ci / scala_test_wall_clock_time / wall-clock-time (8)` (job
  135472921946); not looked at, no ref given for it.

Setup for all commands (in the triage checkout, `log/11651/` is gitignored):

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135472484964/logs > log/11651/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11651/job.log > log/11651/job.clean.log
C=log/11651/job.clean.log
```

## 1. Failing step

The sbt task fails on one package only.

```
grep -a -n -E '^\S+ \[error\]|Exceeded maximum retries' $C | sed -E 's/^([0-9]+:)[^Z]*Z /\1/'
```
```
39508:[error] damlc test failed /__w/splice/splice/daml/splitwell-test:
39509:[error] (splitwell-test-daml / Test / damlTest) damlc test failed /__w/splice/splice/daml/splitwell-test:
39510:[error] Total time: 591 s (0:09:51.0), completed Nov 19, 2026, 9:55:58 AM
39512:Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
```

The damlc diagnostic behind it: the script context could not be created, the gRPC call ran into its deadline.

```
sed -n '36394,36404p' $C | cut -c1-330
```
```
2026-11-19T09:52:06.1072636Z [info] stderr: File:     daml/Splice/Scripts/TestSplitwell.daml
2026-11-19T09:52:06.1117555Z [info] stderr: Hidden:   no
2026-11-19T09:52:06.1279946Z [info] stderr: Range:    1:1-2:1
2026-11-19T09:52:06.1280629Z [info] stderr: Source:   compiler
2026-11-19T09:52:06.1281111Z [info] stderr: Severity: DsError
2026-11-19T09:52:06.1281761Z [info] stderr: Message: 
2026-11-19T09:52:06.1283194Z [info] stderr: ScriptBackendException {scriptNote = "Failed to create script context", scriptBackendError = BErrorClient (ClientIOError (GRPCIOBadStatusCode StatusDeadlineExceeded (StatusDetails {unStatusDetails = "Deadline Exceeded"}) fromList []))}
2026-11-19T09:52:06.1284662Z [info] stderr: 
2026-11-19T09:52:06.1285107Z [info] stderr: 2026-11-19 09:52:05.86 [DEBUG]  [test] 
2026-11-19T09:52:06.1285843Z [info] stderr: Finishing shakeRun (took 1m10s, completed)
2026-11-19T09:52:06.1286870Z [info] 
```

## 2. Timing of the splitwell-test process against two passing runs

Passing jobs: 135500360620 (run 44506784043, main cddeb86170, 10:33) and 135292659221 (run 44444328126, main
1eafba3113, 02:26), logs fetched and cleaned the same way to `log/11651/pass-<job>.log`. The command prints the DAR
builds of the last packages, the start of the splitwell test process (`Set files of interest`), its first test result
and every damlc `shakeRun` longer than 10 s.

```
for f in pass-135500360620 pass-135292659221 job.clean; do echo "## $f"; grep -a -E 'Total time|Compiling splitwell-test to a DAR|Compiling splice-dso-governance-test to a DAR|Compiling splice-amulet-name-service-test to a DAR' log/11651/$f.log | cut -c1-140; grep -a -m1 'NormalizedFilePath "daml/Splice/Scripts/TestSplitwell.daml"' log/11651/$f.log | cut -c1-140; grep -a -m1 -E 'TestSplitwell.daml:[A-Za-z_0-9]+: ok' log/11651/$f.log | cut -c1-140; echo "shakeRuns over 10s:"; grep -a -E 'Finishing shakeRun \(took ([0-9]{2}\.[0-9]+s|[0-9]+m)' log/11651/$f.log | cut -c1-110; done
```
```
## pass-135500360620
2026-11-19T10:42:56.7033329Z [info] Compiling splice-amulet-name-service-test to a DAR.
2026-11-19T10:42:59.7299309Z [info] Compiling splitwell-test to a DAR.
2026-11-19T10:43:57.5909882Z [info] Compiling splice-dso-governance-test to a DAR.
2026-11-19T10:45:11.4775736Z [success] Total time: 460 s (0:07:40.0), completed Nov 19, 2026, 10:45:10 AM
2026-11-19T10:43:39.3740209Z [info] stderr: Set files of interest to: [NormalizedFilePath "daml/Splice/Scripts/TestSplitwell.daml"]
2026-11-19T10:43:57.5895488Z [info] daml/Splice/Scripts/TestSplitwell.daml:test: ok, 49 active contracts, 50 transactions.
shakeRuns over 10s:
2026-11-19T10:40:05.9495540Z [info] stderr: Finishing shakeRun (took 21.58s, completed)
2026-11-19T10:40:20.1565876Z [info] stderr: Finishing shakeRun (took 12.04s, completed)
2026-11-19T10:40:41.3928079Z [info] stderr: Finishing shakeRun (took 15.68s, completed)
2026-11-19T10:41:20.9277069Z [info] stderr: Finishing shakeRun (took 25.15s, completed)
2026-11-19T10:41:25.9911722Z [info] stderr: Finishing shakeRun (took 13.85s, completed)
2026-11-19T10:42:13.8437754Z [info] stderr: Finishing shakeRun (took 24.34s, completed)
2026-11-19T10:42:29.2370594Z [info] stderr: Finishing shakeRun (took 31.20s, completed)
2026-11-19T10:42:56.6897427Z [info] stderr: Finishing shakeRun (took 59.13s, completed)
2026-11-19T10:43:28.2238579Z [info] stderr: Finishing shakeRun (took 1m19s, completed)
2026-11-19T10:43:33.2938709Z [info] stderr: Finishing shakeRun (took 26.76s, completed)
2026-11-19T10:43:51.4988535Z [info] stderr: Finishing shakeRun (took 12.98s, completed)
2026-11-19T10:43:56.5758931Z [info] stderr: Finishing shakeRun (took 13.42s, completed)
2026-11-19T10:45:10.4623151Z [info] stderr: Finishing shakeRun (took 31.81s, completed)
## pass-135292659221
2026-11-19T02:35:46.4422880Z [info] Compiling splice-amulet-name-service-test to a DAR.
2026-11-19T02:35:52.7212587Z [info] Compiling splitwell-test to a DAR.
2026-11-19T02:36:45.8677999Z [info] Compiling splice-dso-governance-test to a DAR.
2026-11-19T02:37:44.5255063Z [success] Total time: 437 s (0:07:17.0), completed Nov 19, 2026, 2:37:44 AM
2026-11-19T02:36:32.6375230Z [info] stderr: Set files of interest to: [NormalizedFilePath "daml/Splice/Scripts/TestSplitwell.daml"]
2026-11-19T02:36:48.9082835Z [info] daml/Splice/Scripts/TestSplitwell.daml:test: ok, 49 active contracts, 50 transactions.
shakeRuns over 10s:
2026-11-19T02:32:42.8229738Z [info] stderr: Finishing shakeRun (took 15.35s, completed)
2026-11-19T02:33:06.3621186Z [info] stderr: Finishing shakeRun (took 23.24s, completed)
2026-11-19T02:33:41.1219726Z [info] stderr: Finishing shakeRun (took 23.58s, completed)
2026-11-19T02:33:49.3086921Z [info] stderr: Finishing shakeRun (took 22.70s, completed)
2026-11-19T02:34:49.8601953Z [info] stderr: Finishing shakeRun (took 35.84s, completed)
2026-11-19T02:34:49.8647801Z [info] stderr: Finishing shakeRun (took 26.24s, completed)
2026-11-19T02:35:23.8311223Z [info] stderr: Finishing shakeRun (took 58.85s, completed)
2026-11-19T02:35:54.7836837Z [info] stderr: Finishing shakeRun (took 25.88s, completed)
2026-11-19T02:36:18.4082126Z [info] stderr: Finishing shakeRun (took 32.43s, completed)
2026-11-19T02:36:26.5461283Z [info] stderr: Finishing shakeRun (took 1m23s, completed)
2026-11-19T02:36:40.8015903Z [info] stderr: Finishing shakeRun (took 12.52s, completed)
2026-11-19T02:36:48.9077981Z [info] stderr: Finishing shakeRun (took 11.54s, completed)
2026-11-19T02:37:44.5097654Z [info] stderr: Finishing shakeRun (took 24.43s, completed)
## job.clean
2026-11-19T09:50:12.8563904Z [info] Compiling splitwell-test to a DAR.
2026-11-19T09:50:43.3009479Z [info] Compiling splice-amulet-name-service-test to a DAR.
2026-11-19T09:54:19.5806750Z [info] Compiling splice-dso-governance-test to a DAR.
2026-11-19T09:55:59.0754107Z [error] Total time: 591 s (0:09:51.0), completed Nov 19, 2026, 9:55:58 AM
2026-11-19T09:50:50.4473248Z [info] stderr: Set files of interest to: [NormalizedFilePath "daml/Splice/Scripts/TestSplitwell.daml"]
shakeRuns over 10s:
2026-11-19T09:48:09.5113278Z [info] stderr: Finishing shakeRun (took 23.46s, completed)
2026-11-19T09:48:12.6402804Z [info] stderr: Finishing shakeRun (took 21.25s, completed)
2026-11-19T09:48:23.8671299Z [info] stderr: Finishing shakeRun (took 17.30s, completed)
2026-11-19T09:48:24.8801851Z [info] stderr: Finishing shakeRun (took 21.27s, completed)
2026-11-19T09:49:16.5763104Z [info] stderr: Finishing shakeRun (took 21.94s, completed)
2026-11-19T09:49:34.0948664Z [info] stderr: Finishing shakeRun (took 16.11s, completed)
2026-11-19T09:49:50.5300870Z [info] stderr: Finishing shakeRun (took 19.44s, completed)
2026-11-19T09:50:17.9268384Z [info] stderr: Finishing shakeRun (took 47.72s, completed)
2026-11-19T09:51:47.5706718Z [info] stderr: Finishing shakeRun (took 1m50s, completed)
2026-11-19T09:52:06.1285843Z [info] stderr: Finishing shakeRun (took 1m10s, completed)
2026-11-19T09:52:12.7582917Z [info] stderr: Finishing shakeRun (took 1m31s, completed)
2026-11-19T09:52:33.9114917Z [info] stderr: Finishing shakeRun (took 21.13s, completed)
2026-11-19T09:53:32.4839186Z [info] stderr: Finishing shakeRun (took 19.46s, completed)
2026-11-19T09:54:15.5403197Z [info] stderr: Finishing shakeRun (took 42.60s, completed)
2026-11-19T09:55:58.0423505Z [info] stderr: Finishing shakeRun (took 31.90s, completed)
```

Reading:
- Passing: the splitwell test process starts at 10:43:39 / 02:36:32 and reports `test: ok` 18 s / 16 s later,
  including the script service start; the runs of over a minute (1m19s, 1m23s) had ended before it started.
- Failing: start 09:50:50, no result, diagnostic at 09:52:05.86 with `took 1m10s`. No `TestSplitwell.daml:... ok`
  line exists in the log (`grep -a -c -E 'TestSplitwell.daml:[A-Za-z_0-9]+: ok' $C` prints 0).
- Failing: three runs of over a minute overlap each other, 1m50s (09:49:57-09:51:47), 1m10s (splitwell,
  09:50:55-09:52:05) and 1m31s (09:50:41-09:52:12); the passing runs have one such run each. sbt total 591 s against
  460 s and 437 s.

## 3. What ran next to it

The damlc test processes that started shortly before splitwell-test, by their `Set files of interest` lines:
splice-amulet-test (09:49:54), splice-wallet-test (09:50:38), then splitwell-test (09:50:50). The DAR build of
splice-amulet-name-service-test completed at 09:50:43 (section 2).

```
grep -a -n -E 'Set files of interest to' $C | awk -F'Z ' '{print $1" "$2}' | cut -c1-230 | awk '$0 ~ /T09:(49:[3-5]|50|51)/'
```
```
27310:2026-11-19T09:49:54.5811323 [info] stderr: Set files of interest to: [NormalizedFilePath "daml/Splice/Scripts/UnitTests/Amulet/CryptoHash.daml",NormalizedFilePath "daml/Splice/Scripts/TestRewardComputation.daml",NormalizedFi
32838:2026-11-19T09:50:38.2145355 [info] stderr: Set files of interest to: [NormalizedFilePath "daml/Splice/Scripts/Wallet/TestTokenStandardV2.daml",NormalizedFilePath "daml/Splice/Scripts/Wallet/TestSubscriptions.daml",Normalized
33798:2026-11-19T09:50:50.4473248 [info] stderr: Set files of interest to: [NormalizedFilePath "daml/Splice/Scripts/TestSplitwell.daml"]
```

Up to four damlc test processes run at once, each with its own script service JVM; the limit was chosen for the
large executor, the job runs on the small runner (splice main at 7666260d8d).

```
grep -n -B3 -A0 'damlTestTag, 4' project/BuildCommon.scala; grep -n 'runs-on' .github/workflows/build.daml_test.yml
```
```
203-      // Limit the number of concurrent damlTest tasks as they are pretty heavy on the RAM
204-      // Number chosen to work well in CI on our Large executor, and our dev machines
205-      // See #9400 for more details.
206:      Global / concurrentRestrictions += Tags.limit(damlTestTag, 4),
11:    runs-on: self-hosted-k8s-small
```

The job log has no diagnostics from the script service of the splitwell process between start and deadline: the
filter below (everything in the window except per-script chatter, restricted to script service, JVM, memory and
error terms) leaves only the start line and the diagnostic.

```
sed -n '33780,36400p' $C | grep -a -v -E 'SCRIPT SERVICE STDOUT: (Connection started|Script started|Script finished)|semaphore|stderr: *$|DEBUG\]  \[test( ScriptService)?\] *$|: ok, ' | grep -a -i -E 'script|service|jvm|java|port|context|gc|heap|warn|error|kill|oom|memory' | cut -c1-200
```
```
2026-11-19T09:50:50.4473248Z [info] stderr: Set files of interest to: [NormalizedFilePath "daml/Splice/Scripts/TestSplitwell.daml"]
2026-11-19T09:52:06.1072636Z [info] stderr: File:     daml/Splice/Scripts/TestSplitwell.daml
2026-11-19T09:52:06.1281111Z [info] stderr: Severity: DsError
2026-11-19T09:52:06.1283194Z [info] stderr: ScriptBackendException {scriptNote = "Failed to create script context", scriptBackendError = BErrorClient (ClientIOError (GRPCIOBadStatusCode StatusDeadlineExceeded (StatusDetails {unStatusDetails
```

## 4. Earlier occurrences

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Failed to create script context"' --jq '.total_count, (.items[]|"\(.number) \(.state) \(.state_reason) \(.created_at[0:10]) \(.title[0:110])")'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "job daml_test Failed"' --jq '.items[]|"\(.number) \(.state) \(.state_reason) \(.created_at[0:10]) \(.title[0:110])"'
```
```
1
6681 closed completed 2025-12-19 Random CI job things are very slow and get killed or time out
11651 open null 2026-11-19 GHA Run 44498711970 : job daml_test Failed :fire:
9172 closed completed 2026-07-10 GHA Run 29123269220 : job daml_test Failed :fire:
7205 closed completed 2026-02-09 GHA Run 21833150863 : job daml_test Failed :fire:
7172 closed not_planned 2026-02-03 GHA Run 21636972426 : job daml_test Failed :fire:
6724 closed duplicate 2026-01-07 GHA Run 20783192549 : job daml_test Failed :fire:
5229 closed not_planned 2025-07-29 GHA Run 16600708745 : job daml_test Failed :fire:
5195 closed not_planned 2025-07-29 GHA Run 16600708745 : job daml_test Failed :fire:
6681 closed completed 2025-12-19 Random CI job things are very slow and get killed or time out
```

- 6724 (2026-01-07) is the same signature on the same module: a comment on 6681 of 2026-01-07 quotes
  `File: daml/Splice/Scripts/TestSplitwell.daml` with the identical `ScriptBackendException {scriptNote = "Failed to
  create script context" ... StatusDeadlineExceeded`, and notes that the pod CPU "maxes out at the times of the
  StatusDeadlineExceeded errors" while memory stayed well below the limit. 6681 was a catch-all for slow CI jobs,
  closed as completed on 2026-02-11 ("didn't seem to happen for 2 weeks") without a change attributed to it.
- 9172, 7205, 7172 are network failures of the runner (github.com unreachable, ETIMEDOUT), not this shape.
- So: second known occurrence in about 10 months, first since 6681 was closed. The daml_test jobs of the neighbouring
  main runs passed (jobs 135546615192, 135500360620, 135292659221, 135234178646, 135163793957).

## Verdict

New family BL; flake, one-off so far. Not a duplicate in the tracker sense: the only matching issue chain (6724 ->
6681) is closed as completed nine months ago, and no family with a confirming grep existed. Cause as far as the log
shows: the script service of the splitwell-test damlc process did not answer the context creation within the gRPC
deadline while two heavy damlc test runs, a third long one and a DAR build ran on the same small runner. Fix location:
build tooling or runner sizing, an owner decision, nothing written:
- lower `Tags.limit(damlTestTag, 4)` in `project/BuildCommon.scala` on the small runner (slower job), or
- move daml_test to a larger runner (cost), or
- raise the script service gRPC timeout for the Daml test packages (hides a starved runner; not checked that dpm
  3.5.2 honours a `script-service: grpc-timeout` entry in `daml.yaml`; `dpm test --help` has no flag for it), or
- nothing, at one occurrence in about 10 months (Status `Infrequent / no repro` after two weeks without a duplicate).

NOT verified: runner pod CPU and memory at 09:50-09:52 (Grafana not read); that the deadline is the damlc default of
60 s (inferred from 1m10s of the run less startup, not read from the damlc source); which package the 1m50s run
belongs to; whether a fresh run at the same sha passes (no rerun). No sbt run, no local reproduction.
