# 11304, 11307, 11309, 11316 - ValidatorSequencerConnectionIntegrationTest: scan duplicate-sequencer WARN after the suppression ended (runs 42637895044, 42639073931)

New, introduced by #8365 (7d3d5f17da, "Handle duplicate sequencer ids in scan"). Both jobs are `wall-clock-time (2)` on
post-merge main, canton 3.7.2, all 20 tests passed; checkErrors flagged the WARN `Not serving sequencer SEQ::sv2::...
as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4` that #8365 added to `HttpScanHandler`.
The new test case suppresses that WARN only until `sv1Scan` serves all four sequencers again; bobValidator's
`ReconcileSequencerConnectionsTrigger` polls all four scans every second, and `sv3Scan`/`sv4Scan` still saw the
duplicate 1 to 4 s after `sv1Scan` stopped, outside the `assertLogsSeq` block. Test bug; the restore check must wait
for every scan, not only sv1Scan. Further hits, same mechanism (section 7): 11309 (run 42639148146, 92705421fd, wall-clock-time (2)) and 11316
(run 42658360509, 91715b25c0, wall-clock-time (5) after the shard split moved the suite).

- Runs: https://github.com/canton-network/splice/actions/runs/42637895044 (main 7d3d5f17da, "Handle duplicate sequencer
  ids in scan (#8365)"), job 129231318420 `ci / scala_test_wall_clock_time / wall-clock-time (2)` (11304);
  https://github.com/canton-network/splice/actions/runs/42639073931 (main fbc7ad2481, "Make ACS stores track a single
  synchronizer (#8100)"), job 129235461011 `ci / scala_test_wall_clock_time / wall-clock-time (2)` (11307). Attempt 1 both.
- Runtime canton: 3.7.2.
- Component: test (ValidatorSequencerConnectionIntegrationTest, added by #8365).

## 1. Flagged lines

checkErrors flagged only the new WARN, from sv3Scan and sv4Scan (11304) and sv4Scan (11307); 11309 is the same line.

```
$ for R in 11304 11307 11309; do echo "== $R"; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$R/job.log | grep -a -E 'Tests: succeeded'; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$R/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | sed -E 's/1220[0-9a-f]{60}/../g' | cut -c1-330; done
== 11304
[info] Tests: succeeded 20, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-09T15:21:35.395Z","message":"Not serving sequencer SEQ::sv2::..9c8c for synchronizer global-domain::..175b and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrationTest/config=1c1c69b8/scan=s
***"@timestamp":"2026-11-09T15:21:35.395Z","message":"Not serving sequencer SEQ::sv2::..9c8c for synchronizer global-domain::..175b and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrationTest/config=1c1c69b8/scan=s
Total: 2 lines with problems.
== 11307
[info] Tests: succeeded 20, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-09T15:27:43.279Z","message":"Not serving sequencer SEQ::sv2::..23f0 for synchronizer global-domain::..da30 and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrationTest/config=840510fd/scan=s
Total: 1 lines with problems.
== 11309
[info] Tests: succeeded 20, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-09T15:34:54.321Z","message":"Not serving sequencer SEQ::sv2::..cc25 for synchronizer global-domain::..c612 and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrationTest/config=68d7b94f/scan=s
Total: 1 lines with problems.

```

## 2. Which test was running

The WARNs fall into the second test case of the suite, `scan does not serve a sequencer id claimed by multiple SVs ...`,
13 s (11304) and 12 s (11307) before it succeeded.

```
$ for R in 11304 11307; do echo "== $R"; zcat log/$R/logs-wall-clock-time-2/canton_network_test.clog.gz | grep -a 'LogReporter:reporter=scala-test' | grep -a 'ValidatorSequencerConnectionIntegrationTest' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//' | cut -c1-170; done
== 11304
2026-11-09T15:16:52.499Z Starting test suite 'ValidatorSequencerConnectionIntegrationTest'...
2026-11-09T15:16:52.502Z Starting 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL chan
2026-11-09T15:19:57.898Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks U
2026-11-09T15:19:57.899Z Starting 'ValidatorSequencerConnectionIntegrationTest/scan does not serve a sequencer id claimed by multiple SVs and validators connect to the re
2026-11-09T15:21:48.570Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/scan does not serve a sequencer id claimed by multiple SVs and validators connect to
2026-11-09T15:21:48.571Z Completed test suite 'ValidatorSequencerConnectionIntegrationTest'.
== 11307
2026-11-09T15:23:07.342Z Starting test suite 'ValidatorSequencerConnectionIntegrationTest'...
2026-11-09T15:23:07.344Z Starting 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL chan
2026-11-09T15:26:06.620Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks U
2026-11-09T15:26:06.621Z Starting 'ValidatorSequencerConnectionIntegrationTest/scan does not serve a sequencer id claimed by multiple SVs and validators connect to the re
2026-11-09T15:27:55.362Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/scan does not serve a sequencer id claimed by multiple SVs and validators connect to
2026-11-09T15:27:55.362Z Completed test suite 'ValidatorSequencerConnectionIntegrationTest'.

```

## 3. The test suppresses the WARN only until sv1Scan serves all sequencers

The `assertLogsSeq` block ends with an `eventually` on `sv1ScanBackend` alone. The WARN is logged on every
`/v0/dso-sequencers` request a scan answers while its view still has the duplicate.

```
$ git show 7d3d5f17da:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorSequencerConnectionIntegrationTest.scala | sed -n '161,196p'
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          // sv4 claims sv2's sequencer id while publishing its own url
          setSequencerId(sv4Backend, sv2SequencerId.toProtoPrimitive).futureValue

          withClue("Scan should serve neither of the entries claiming sv2's sequencer id") {
            eventually() {
              val servedSequencers = sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers)
              servedSequencers.map(_.svName).toSet shouldBe Set(getSvName(1), getSvName(3))
              servedSequencers.map(_.id) should not contain sv2SequencerId
            }
          }

          bobValidatorBackend.startSync()

          withClue("Validator should connect to the sequencers with a unique id") {
            eventually(120.seconds, 1.second) {
              getSequencerPublicUrls(
                bobValidatorBackend.participantClientWithAdminToken,
                globalSyncAlias,
              ) shouldBe Set(getPublicSequencerUrl(sv1Backend), getPublicSequencerUrl(sv3Backend))
            }
          }

          withClue("Restoring sv4's sequencer id makes scan serve all sequencers again") {
            setSequencerId(sv4Backend, sv4SequencerId.toProtoPrimitive).futureValue
            eventually() {
              sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers).map(_.svName).toSet shouldBe
                Set(getSvName(1), getSvName(2), getSvName(3), getSvName(4))
            }
          }
        },
        entries => {
          entries should not be empty
          forAll(entries)(_.warningMessage should include("as it is claimed by multiple SVs"))
        },

$ git show 7d3d5f17da:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/http/HttpScanHandler.scala | sed -n '641,657p'
        .map(list =>
          list.map { case (synchronizerId, sequencers) =>
            val (uniqueSequencers, duplicates) =
              DsoSequencerDeduplication.partitionUniqueBy(sequencers)(sequencer =>
                (sequencer.synchronizerSerial, sequencer.id)
              )
            duplicates.foreach { case ((serial, sequencerId), duplicatedSequencers) =>
              // the duplicate sequencer ids are important because they are used as aliases for the sequencer connection
              // in the validator and that does not support duplicates
              logger.warn(
                s"Not serving sequencer $sequencerId for synchronizer $synchronizerId and serial ${serial
                    .getOrElse("<none>")} as it is claimed by multiple SVs: ${duplicatedSequencers
                    .map(_.svName)
                    .mkString(", ")}"
              )
            }
            synchronizerId -> uniqueSequencers.filter { sequencer =>

```

## 4. sv3Scan and sv4Scan kept serving the duplicate after sv1Scan stopped

Per scan, suppressed and flagged occurrences with first and last timestamp. sv1Scan's last occurrence is 15:21:31.410
(11304) and 15:27:40.941 (11307); the other scans warned until 15:21:35.395 and 15:27:43.279. Every flagged line is
later than every suppressed one, so the suppression ended between the last suppressed line and the flagged one.

```
$ for R in 11304 11307; do echo "== $R"; zcat log/$R/logs-wall-clock-time-2/canton_network_test.clog.gz | grep -a 'claimed by multiple SVs' | grep -a HttpScanHandler | jq -r '"\(if (.message|startswith("Suppressed")) then "suppressed" else "FLAGGED" end) \(.logger_name|sub(".*scan=";"")) \(."@timestamp")"' | awk '{k=$1" "$2; if(!(k in f)){f[k]=$3; o[++n]=k} l[k]=$3; c[k]++} END{for(i=1;i<=n;i++){k=o[i]; print k, c[k], f[k], "..", l[k]}}'; done
== 11304
suppressed sv1Scan 6 2026-11-09T15:21:25.002Z .. 2026-11-09T15:21:31.410Z
suppressed sv4Scan 8 2026-11-09T15:21:25.428Z .. 2026-11-09T15:21:34.151Z
suppressed sv3Scan 6 2026-11-09T15:21:25.429Z .. 2026-11-09T15:21:34.153Z
suppressed sv2Scan 4 2026-11-09T15:21:28.025Z .. 2026-11-09T15:21:34.153Z
FLAGGED sv3Scan 1 2026-11-09T15:21:35.395Z .. 2026-11-09T15:21:35.395Z
FLAGGED sv4Scan 1 2026-11-09T15:21:35.395Z .. 2026-11-09T15:21:35.395Z
== 11307
suppressed sv1Scan 6 2026-11-09T15:27:33.390Z .. 2026-11-09T15:27:40.941Z
suppressed sv2Scan 9 2026-11-09T15:27:33.809Z .. 2026-11-09T15:27:42.293Z
suppressed sv3Scan 6 2026-11-09T15:27:34.607Z .. 2026-11-09T15:27:42.293Z
suppressed sv4Scan 6 2026-11-09T15:27:35.654Z .. 2026-11-09T15:27:42.294Z
FLAGGED sv4Scan 1 2026-11-09T15:27:43.279Z .. 2026-11-09T15:27:43.279Z

```

## 5. The caller is bobValidator's ReconcileSequencerConnectionsTrigger

The flagged requests carry the trace id of bobValidator's `ReconcileSequencerConnectionsTrigger` polling loop (1 s
interval), started by `bobValidatorBackend.startSync()` inside the block. Its poll at 15:21:35.39 still hit the
duplicate on sv3Scan and sv4Scan; the next one at 15:21:36.32 no longer did.

```
$ T=log/11304/logs-wall-clock-time-2/canton_network_test.clog.gz; zcat $T | grep -a 'claimed by multiple SVs' | grep -a -v Suppressed | grep -a HttpScanHandler | jq -r '"\(."@timestamp") \(.logger_name|sub(".*scan=";"")) trace-id=\(."trace-id")"'; zcat $T | grep -a bd6a4d572e4c25241aaa3f48265f223c | grep -a -m1 'Starting trigger polling loop' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name":"([^"]*)".*/[\1]/; s/ValidatorSequencerConnectionIntegrationTest\/config=[0-9a-f]+\///' | cut -c1-230
2026-11-09T15:21:35.395Z sv3Scan trace-id=bd6a4d572e4c25241aaa3f48265f223c
2026-11-09T15:21:35.395Z sv4Scan trace-id=bd6a4d572e4c25241aaa3f48265f223c
2026-11-09T15:21:27.095Z Starting trigger polling loop, ConfigSummary(pollingInterval = 1 second, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)",[o.l.s.v.a.ReconcileSequencerConnectionsTrigger:validator=bobValidator]

$ T=log/11304/logs-wall-clock-time-2/canton_network_test.clog.gz; zcat $T | grep -a bd6a4d572e4c25241aaa3f48265f223c | grep -a -E 'T15:21:3[56]' | grep -a -E 'received request|claimed by multiple' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name":"([^"]*)".*/[\1]/; s/ValidatorSequencerConnectionIntegrationTest\/config=[0-9a-f]+\///; s/1220[0-9a-f]{60}/../g' | cut -c1-200
2026-11-09T15:21:35.394Z HTTP GET /api/scan/v0/dso-sequencers from (127.0.0.1:51890): received request.",[o.l.s.a.a.HttpRequestLogger:scan=sv3Scan]
2026-11-09T15:21:35.394Z HTTP GET /api/scan/v0/dso-sequencers from (127.0.0.1:40982): received request.",[o.l.s.a.a.HttpRequestLogger:scan=sv1Scan]
2026-11-09T15:21:35.395Z HTTP GET /api/scan/v0/dso-sequencers from (127.0.0.1:49492): received request.",[o.l.s.a.a.HttpRequestLogger:scan=sv4Scan]
2026-11-09T15:21:35.395Z Not serving sequencer SEQ::sv2::..9c8c for synchronizer global-domain::..175b and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4",[o.l.s.s
2026-11-09T15:21:35.395Z Not serving sequencer SEQ::sv2::..9c8c for synchronizer global-domain::..175b and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4",[o.l.s.s
2026-11-09T15:21:36.323Z HTTP GET /api/scan/v0/dso-sequencers from (127.0.0.1:51934): received request.",[o.l.s.a.a.HttpRequestLogger:scan=sv3Scan]
2026-11-09T15:21:36.323Z HTTP GET /api/scan/v0/dso-sequencers from (127.0.0.1:41040): received request.",[o.l.s.a.a.HttpRequestLogger:scan=sv1Scan]
2026-11-09T15:21:36.323Z HTTP GET /api/scan/v0/dso-sequencers from (127.0.0.1:54982): received request.",[o.l.s.a.a.HttpRequestLogger:scan=sv2Scan]

$ T=log/11307/logs-wall-clock-time-2/canton_network_test.clog.gz; TR=$(zcat $T | grep -a 'claimed by multiple SVs' | grep -a -v Suppressed | grep -a HttpScanHandler | jq -r '."trace-id"'); echo "trace-id=$TR"; zcat $T | grep -a "$TR" | grep -a -m1 'Starting trigger polling loop' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name":"([^"]*)".*/[\1]/; s/ValidatorSequencerConnectionIntegrationTest\/config=[0-9a-f]+\///' | cut -c1-230
trace-id=969a3dff25970f1955900eaeb8a5f8d0
2026-11-09T15:27:34.599Z Starting trigger polling loop, ConfigSummary(pollingInterval = 1 second, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)",[o.l.s.v.a.ReconcileSequencerConnectionsTrigger:validator=bobValidator]

```

## 6. Hit rate and fix state

`wall-clock-time (2)` failed on 3 of the 4 post-merge runs that contain #8365 and passed on the run before it. The
test file has no change on main after #8365.

```
$ git log --format='%h %ad %an %s' --date=short origin/main -2 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorSequencerConnectionIntegrationTest.scala
7d3d5f17da 2026-11-09 Nicu Reut Handle duplicate sequencer ids in scan (#8365)
9eea7b2cab 2026-09-17 Nicu Reut No longer set the deprecated sequencer connections in the dso rules (#5932)

$ for s in dbcb0c14ca 7d3d5f17da fbc7ad2481 92705421fd 5dc875a64f; do git merge-base --is-ancestor 7d3d5f17da $s && echo "$s has #8365" || echo "$s lacks #8365"; done
dbcb0c14ca lacks #8365
7d3d5f17da has #8365
fbc7ad2481 has #8365
92705421fd has #8365
5dc875a64f has #8365

$ for RUN in 42634636103 42637895044 42639073931 42639148146 42646231899; do gh api "repos/canton-network/splice/actions/runs/$RUN" --jq '"\(.id) \(.head_sha[0:10])"' | tr '\n' ' '; gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?per_page=100" --jq '.jobs[] | select(.name|endswith("/ wall-clock-time (2)")) | .conclusion'; done
42634636103 dbcb0c14ca success
42637895044 7d3d5f17da failure
42639073931 fbc7ad2481 failure
42639148146 92705421fd failure
42646231899 5dc875a64f success

$ git show 7d3d5f17da:nix/canton-sources.json | grep -m1 version
  "version": "3.7.2",

```

## 7. 11309 and 11316: same mechanism

Flagged lines in both jobs, then per scan the suppressed and flagged occurrences: every flagged line comes after
sv1Scan's last suppressed one, from the trace of bobValidator's `ReconcileSequencerConnectionsTrigger`.

```
$ for R in 11316; do sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$R/job.log | grep -a -E 'Tests: succeeded'; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$R/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | sed -E 's/1220[0-9a-f]{60}/../g' | cut -c1-330; done
[info] Tests: succeeded 23, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-09T17:20:54.390Z","message":"Not serving sequencer SEQ::sv2::..91c6 for synchronizer global-domain::..ebc5 and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrationTest/config=4898b984/scan=s
***"@timestamp":"2026-11-09T17:20:54.391Z","message":"Not serving sequencer SEQ::sv2::..91c6 for synchronizer global-domain::..ebc5 and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrationTest/config=4898b984/scan=s
Total: 2 lines with problems.
```
```
$ for R in '11309 wall-clock-time-2' '11316 wall-clock-time-5'; do set -- $R; T=log/$1/logs-$2/canton_network_test.clog.gz; echo "== $1"; zcat $T | grep -a 'claimed by multiple SVs' | grep -a HttpScanHandler | jq -r '"\(if (.message|startswith("Suppressed")) then "suppressed" else "FLAGGED" end) \(.logger_name|sub(".*scan=";"")) \(."@timestamp")"' | awk '{k=$1" "$2; if(!(k in f)){f[k]=$3; o[++n]=k} l[k]=$3; c[k]++} END{for(i=1;i<=n;i++){k=o[i]; print k, c[k], f[k], "..", l[k]}}'; for TR in $(zcat $T | grep -a 'claimed by multiple SVs' | grep -a -v Suppressed | grep -a HttpScanHandler | jq -r '."trace-id"' | sort -u); do echo "trace-id=$TR"; zcat $T | grep -a "$TR" | grep -a -m1 'Starting trigger polling loop' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name":"([^"]*)".*/[\1]/; s/ValidatorSequencerConnectionIntegrationTest\/config=[0-9a-f]+\///' | cut -c1-200; done; done
== 11309
suppressed sv1Scan 15 2026-11-09T15:34:43.105Z .. 2026-11-09T15:34:53.974Z
suppressed sv4Scan 6 2026-11-09T15:34:44.443Z .. 2026-11-09T15:34:53.031Z
suppressed sv3Scan 6 2026-11-09T15:34:44.444Z .. 2026-11-09T15:34:51.677Z
suppressed sv2Scan 5 2026-11-09T15:34:48.228Z .. 2026-11-09T15:34:53.033Z
FLAGGED sv3Scan 1 2026-11-09T15:34:54.321Z .. 2026-11-09T15:34:54.321Z
trace-id=129e40b27108782c30183c9a5b6af370
2026-11-09T15:34:46.196Z Starting trigger polling loop, ConfigSummary(pollingInterval = 1 second, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)",[o.l.s.v.a.ReconcileSequencerConnectionsTrigger:
== 11316
suppressed sv1Scan 6 2026-11-09T17:20:44.125Z .. 2026-11-09T17:20:52.850Z
suppressed sv3Scan 7 2026-11-09T17:20:44.765Z .. 2026-11-09T17:20:51.547Z
suppressed sv2Scan 4 2026-11-09T17:20:44.765Z .. 2026-11-09T17:20:52.845Z
suppressed sv4Scan 7 2026-11-09T17:20:46.364Z .. 2026-11-09T17:20:52.849Z
FLAGGED sv2Scan 1 2026-11-09T17:20:54.390Z .. 2026-11-09T17:20:54.390Z
FLAGGED sv4Scan 1 2026-11-09T17:20:54.391Z .. 2026-11-09T17:20:54.391Z
trace-id=e56c2e34510ff155ad08becdea3220f9
2026-11-09T17:20:46.353Z Starting trigger polling loop, ConfigSummary(pollingInterval = 1 second, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)",[o.l.s.v.a.ReconcileSequencerConnectionsTrigger:
```
```
$ gh api 'repos/canton-network/splice/actions/runs/42658360509' --jq '"\(.id) \(.head_sha[0:10])"'; git merge-base --is-ancestor 7d3d5f17da 91715b25c0 && echo '91715b25c0 has #8365'
42658360509 91715b25c0
91715b25c0 has #8365
```

All ten wall-clock-time shards passed on the 5dc875a64f run, and only wall-clock-time (5), which held the suite, failed
on the 91715b25c0 run: 4 of the 5 post-merge runs with #8365 failed on this WARN.

```
$ for RUN in 42646231899 42658360509; do echo "== $RUN"; gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?per_page=100" --jq '.jobs[] | select(.name|test("/ wall-clock-time \\(")) | "\(.name|sub(".*/ ";"")) \(.conclusion)"' | sort; done
== 42646231899
wall-clock-time (0) success
wall-clock-time (1) success
wall-clock-time (2) success
wall-clock-time (3) success
wall-clock-time (4) success
wall-clock-time (5) success
wall-clock-time (6) success
wall-clock-time (7) success
wall-clock-time (8) success
wall-clock-time (9) success
== 42658360509
wall-clock-time (0) success
wall-clock-time (1) success
wall-clock-time (2) success
wall-clock-time (3) success
wall-clock-time (4) success
wall-clock-time (5) failure
wall-clock-time (6) success
wall-clock-time (7) success
wall-clock-time (8) success
wall-clock-time (9) success
```

## Verdict

New, test bug introduced by #8365 (author nicu-da). Not a flake of the product change: scan behaves as intended, and the
four scans stop reporting the duplicate a few seconds apart (section 4). The suppression window closes on sv1Scan's view
while the validator under test still polls all four scans.

Fix (test, `ValidatorSequencerConnectionIntegrationTest.scala`, the `Restoring sv4's sequencer id ...` clue): wait until
every scan (`sv1ScanBackend` to `sv4ScanBackend`) serves all four sequencers before leaving the `assertLogsSeq` block,
for example `forAll(Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend))(scan =>
scan.listDsoSequencers().flatMap(_.sequencers).map(_.svName).toSet shouldBe Set(...))` inside the `eventually`. That
also covers the first clue (`Scan should serve neither ...`), which only checks sv1Scan, but there the suppression is
still active, so it cannot flag.

Not verified: why sv3Scan and sv4Scan converge later than sv1Scan (store ingestion of sv4's restored SvNodeState); the
fix does not depend on it.
