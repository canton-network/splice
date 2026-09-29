# 10238 - TokenStandardMetadataTimeBasedIntegrationTest: two forced ACS snapshots 148 us apart get the same per-snapshot table name (record time truncated to ms, #6515); the second force fails with `relation "acs_snapshot_creates_v1_14_35521001" already exists` (run 36548918726)

NEW, real bug introduced by #6515 (cc4539a9ac "Implement Table per ACS Snapshot", merged 2026-09-29 09:18Z, the
commit right before this run's sha). Since #6515 every ACS snapshot gets its own tables named
`acs_snapshot_{creates,stakeholders}_v1_<historyId>_<targetRecordTime.toEpochMilli>`. Record times have microsecond
resolution, so two distinct snapshots within one millisecond map to the same table name and the second
`create table` fails with SQLSTATE 42P07. In this sim-time test the "rounds are defined" check forces a snapshot (via
`getTotalAmuletBalance`) and the next clue forces another one 108 ms of wall time and 148 us of record time later.
Timing-dependent: the same suite passed in the 4 other post-merge runs that contain #6515.

- Run: https://github.com/canton-network/splice/actions/runs/36548918726, main 0a2f98714e ("Add the ability to keep
  just rejection logs for cloud armor (#7511)"), post-merge CI, job 109342320998 `ci / scala_test_sim_time / simtime (2)`.
  The same run's `docker-canton-simtime (0)` failure is ref 10237 (separate packet).
- Runtime canton: 3.6.0-snapshot.20260928.20326.0.v5616afeb (`nix/canton-sources.json` at 0a2f98714e).
- Component: splice scan app (`AcsSnapshotStore`), not the test.
- 12 tests in 8 suites, 1 failed: `TokenStandardMetadataTimeBasedIntegrationTest / Scan implements token metadata API`
  with `CommandFailure: Command execution failed.`

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10238/job.log | grep -a -B3 -A30 'Scan implements token metadata API \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | sed -n '1,13p'
```
```
[info] - Scan implements token metadata API *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
[info] Run completed in 15 minutes, 46 seconds.
[info] Total number of tests run: 12
[info] Suites: completed 8, aborted 0
[info] Tests: succeeded 11, failed 1, canceled 0, ignored 0, pending 0
[info] *** 1 TEST FAILED ***
[info] TokenStandardMetadataTimeBasedIntegrationTest:
[info]
[info] - Scan implements token metadata API *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
[error] Failed tests:
[error] 	org.lfdecentralizedtrust.splice.integration.tests.TokenStandardMetadataTimeBasedIntegrationTest
```

## 2. Suite timeline

```
zcat log/10238/logs-simtime-2/canton_network_test.clog.gz | grep -a -E "TokenStandardMetadata" | grep -a -E "Starting test suite|Test (succeeded|failed)|Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-260
```
```
2026-09-29T09:48:02.700Z Starting test suite 'TokenStandardMetadataTimeBasedIntegrationTest'...",
2026-09-29T09:48:24.740Z Starting 'TokenStandardMetadataTimeBasedIntegrationTest/Scan implements token metadata API'...",
2026-09-29T09:49:15.771Z Test failed: 'TokenStandardMetadataTimeBasedIntegrationTest/Scan implements token metadata API', message: Command execution failed., location: SeeStackDepthException",
```

## 3. Two forced snapshots in the same millisecond; the second one collides

```
zcat log/10238/logs-simtime-2/canton_network_test.clog.gz | grep -a 'T09:49:1[5-6]' \
  | grep -a -E 'clue:|acs/force|Forcing ACS snapshot|Saved incremental snapshot|SQL state|Test failed' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\(\\n.*//' \
  | sed -E 's/\[([a-z]\.)+([A-Za-z]+)(:[^]]*)?\]/[\2]/' | cut -c12-230
```
```
09:49:15.617Z Finished clue: (check) waiting for open and issuing round automation (should create OpenMiningRound 9, should advance IssuingMiningRounds List(ContractWithState(Contract
09:49:15.617Z Finished clue: (act) Advance rounds to a point where round totals are defined and the tapped amulet", [TokenStandardMetadataTimeBasedIntegrationTest] DEBUG
09:49:15.617Z Running clue: (check) rounds are defined and include tapped amulet", [TokenStandardMetadataTimeBasedIntegrationTest] DEBUG
09:49:15.621Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51834): received request.", [HttpRequestLogger] DEBUG
09:49:15.621Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51834): omitting logging of request entity data.", [HttpRequestLogger] DEBUG
09:49:15.631Z Forcing ACS snapshot at 1970-01-01T09:52:01.001488Z. Last snapshot: None", [HttpScanHandler] INFO
09:49:15.676Z Saved incremental snapshot 8 at 1970-01-01T09:52:01.001488Z with 38 create rows and 48 stakeholder rows. Next snapshot target record time: 1970-01-01T09:52:01.001488Z", [AcsSnapshotStore] DEBUG
09:49:15.724Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51834): Responding with status code: 200 OK", [HttpRequestLogger] DEBUG
09:49:15.724Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51834): Responding with entity data: {\"record_time\":\"1970-01-01T09:52:01.001488Z\",\"migration_id\":0}", [HttpRequestLogger] DEBUG
09:49:15.725Z HTTP client (POST /api/scan/v0/state/acs/force): HTTP request took 105 ms to complete. Received response with status code: 200 OK", [o.l.s.i.EnvironmentDefinition$$anon$43:TokenStandardMetadataTimeBasedInt
09:49:15.734Z Finished clue: (check) rounds are defined and include tapped amulet", [TokenStandardMetadataTimeBasedIntegrationTest] DEBUG
09:49:15.734Z Running clue: Compare direct scan reads to instrument metadata", [TokenStandardMetadataTimeBasedIntegrationTest] DEBUG
09:49:15.735Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51866): received request.", [HttpRequestLogger] DEBUG
09:49:15.735Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51866): omitting logging of request entity data.", [HttpRequestLogger] DEBUG
09:49:15.739Z Forcing ACS snapshot at 1970-01-01T09:52:01.001636Z. Last snapshot: Some(PerTableAcsSnapshot
09:49:15.759Z Detected an SQLException. SQL state: 42P07, error code: 0", [DbStorageSingle] INFO
09:49:15.760Z Request to http://127.0.0.1:5012/api/scan/v0/state/acs/force resulted in an unexpected exception: ERROR: relation \"acs_snapshot_creates_v1_14_35521001\" already exists", [HttpErrorHandler] ERROR
09:49:15.760Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51866): Responding with status code: 500 Internal Server Error", [HttpRequestLogger] DEBUG
09:49:15.760Z HTTP POST /api/scan/v0/state/acs/force from (127.0.0.1:51866): Responding with entity data: {\n  \"error\" : \"An unexpected error occurred.\"\n}", [HttpRequestLogger] DEBUG
09:49:15.761Z HTTP client (POST /api/scan/v0/state/acs/force): HTTP request took 26 ms to complete. Received response with status code: 500 Internal Server Error", [o.l.s.i.EnvironmentDefinition$$anon$43:TokenStandardMe
09:49:15.768Z org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 500 Internal Server Error POST at '/api/scan/v0/state/acs/force' on 127.0.0.1:5012. Command failed, message: An unexpect
09:49:15.769Z Failed clue: Compare direct scan reads to instrument metadata", [TokenStandardMetadataTimeBasedIntegrationTest] ERROR
09:49:15.769Z Failed clue: Once round totals are defined they are served", [TokenStandardMetadataTimeBasedIntegrationTest] ERROR
09:49:15.771Z Test failed: 'TokenStandardMetadataTimeBasedIntegrationTest/Scan implements token metadata API', message: Command execution failed., location: SeeStackDepthException", [LogReporter] ERROR
```

The first force (inside the "rounds are defined" check, via `getTotalAmuletBalance`) saved snapshot 8 at record
time 09:52:01.001488; the second (`forceAcsSnapshotNow()`, 11 ms of wall time after the first response) targets
09:52:01.001636 and fails on `create table`. Both record times are in the same millisecond:

```
python3 -c '
from datetime import datetime,timezone
for t in ["1970-01-01T09:52:01.001488","1970-01-01T09:52:01.001636"]:
    d=datetime.fromisoformat(t).replace(tzinfo=timezone.utc); us=int(d.timestamp())*1_000_000+d.microsecond
    print(t+"Z", "micros", us, "epochMilli", us//1000)'
```
```
1970-01-01T09:52:01.001488Z micros 35521001488 epochMilli 35521001
1970-01-01T09:52:01.001636Z micros 35521001636 epochMilli 35521001
```

`35521001` is exactly the suffix of the colliding table `acs_snapshot_creates_v1_14_35521001` (historyId 14).

## 4. The table name drops the microseconds (introduced by #6515)

```
git show 0a2f98714e:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/AcsSnapshotStore.scala | sed -n '818,827p'
git blame -s -L 819,819 0a2f98714e -- apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/AcsSnapshotStore.scala | cut -c1-60
```
```
    val createsTableName =
      s"acs_snapshot_creates_v1_${historyId}_${snapshot.targetRecordTime.toEpochMilli}"
    val stakeholdersTableName =
      s"acs_snapshot_stakeholders_v1_${historyId}_${snapshot.targetRecordTime.toEpochMilli}"

    for {
      _ <-
        sqlu"create table #$createsTableName (like acs_snapshot_creates_v1_template including all)"
      _ <-
        sqlu"create table #$stakeholdersTableName (like acs_snapshot_stakeholders_v1_template including all)"
cc4539a9ac7 819)       s"acs_snapshot_creates_v1_${historyId
```

`targetRecordTime` is a `CantonTimestamp` (microsecond resolution); `toEpochMilli` truncates. Before #6515 snapshots
were rows keyed by the full record time, so the same two forces did not collide.

## 5. Why the test forces twice so close together

```
F=apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TokenStandardMetadataTimeBasedIntegrationTest.scala
git show 0a2f98714e:$F | sed -n '114,126p'
git show 0a2f98714e:$F | grep -n 'we force snapshots'
```
```
      )(
        "rounds are defined and include tapped amulet",
        _ => {
          val totalBalance =
            sv1ScanBackend
              .getTotalAmuletBalance("Amulet")
          totalBalance should be >= walletUsdToAmulet(99.0)
        },
      )
      clue("Compare direct scan reads to instrument metadata") {
        val forcedSnapshotTime = sv1ScanBackend.forceAcsSnapshotNow()
        advanceTime(Duration.ofSeconds(1L)) // because the sanity plugin will run another snapshot
        // hope: this test won't have created more than Limit.MaxLimit contracts, so they all fit in a single response
```
```
37:        )( // we force snapshots (via getTotalAmuletBalance) half-way through the test
```

No sim-time advance separates the `getTotalAmuletBalance` force from `forceAcsSnapshotNow()`, so only the ledger
events in between move the record time; here 148 us.

## 6. Timing-dependent: 1 of 5 post-#6515 runs

The shard that ran the suite in every post-merge run on main containing cc4539a9ac (passing tests are not printed on
the console, so the shard is identified by its `testOnly` command and its summary):

```
for f in log/10238/other/*.log; do if sed -E 's/\x1b\[[0-9;]*m//g' $f | grep -a -E '^\S+Z\s+cmd: "testOnly ' | grep -a -q 'TokenStandardMetadataTimeBasedIntegrationTest'; then j=$(basename $f .log); echo "job $j runs the suite: $(sed -E 's/\x1b\[[0-9;]*m//g' $f | grep -a -E 'Tests: succeeded|All tests passed' | sed -E 's/^[^Z]*Z //' | tr '\n' ' ')"; fi; done
```
```
job 109340589163 runs the suite: [info] Tests: succeeded 16, failed 0, canceled 0, ignored 0, pending 0 [info] All tests passed.
job 109386005293 runs the suite: [info] Tests: succeeded 8, failed 0, canceled 0, ignored 1, pending 0 [info] All tests passed.
job 109386109267 runs the suite: [info] Tests: succeeded 8, failed 0, canceled 0, ignored 1, pending 0 [info] All tests passed.
job 109386386001 runs the suite: [info] Tests: succeeded 8, failed 0, canceled 0, ignored 1, pending 0 [info] All tests passed.
```

(`log/10238/other/` holds the sim-time job logs of runs 36548278932 cc4539a9ac, 36562243359 ab0ba59509, 36562281828
5af1f5a467 and 36562304394 802b9faea0, fetched with `gh api repos/canton-network/splice/actions/jobs/<id>/logs`.)

## 7. Not the mechanism of 10237 (same run, docker-canton-simtime (0))

```
for f in log/10238/xref-docker-canton-simtime-0/*.clog.gz; do echo "$f: $(zcat $f | grep -a -c 'relation .*already exists')"; done
```
```
log/10238/xref-docker-canton-simtime-0/canton-simtime.clog.gz: 0
log/10238/xref-docker-canton-simtime-0/canton_network_test.clog.gz: 0
```

## Verdict

- New, real regression from #6515 (cc4539a9ac), not a known family. Not a test flake in the usual sense: the product
  cannot store two snapshots whose record times share a millisecond. The test exposes it because it forces two
  snapshots with no time advance in between; it fails when they land in the same ms (1 of 5 post-#6515 runs).
- Fix location: scan app, `AcsSnapshotStore.saveV2IncrementalSnapshot` (and wherever the name is re-derived):
  name the tables from the full-resolution record time (`targetRecordTime.toMicros` instead of `toEpochMilli`), or
  from the snapshot id. The same `toEpochMilli` truncation is in the index names at lines 1506 and 1520
  (`acs_snapshot_stakeholders_${historyId}_${snapshotRecordTime.toEpochMilli}_s_ca_ci` / `_s_tid_ca_ci`,
  `git show 0a2f98714e:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/AcsSnapshotStore.scala | grep -n toEpochMilli`)
  and needs the same change. Owner: #6515 author (Oriol Munoz) / scan. Production changes are left to the owner; no fix
  branch written. A test-side `advanceTime` before `forceAcsSnapshotNow()` would only hide the bug.
- Production exposure: `/v0/state/acs/force` is gated by `enableForcedAcsSnapshots`; the periodic AcsSnapshotTrigger
  snapshots at interval boundaries, so a same-ms pair outside forced snapshots was not found (not searched further).
- Not verified: the record-time gap in the 4 passing runs (artifacts not downloaded); how the read path resolves the
  table names (only the write path at lines 818-827 and the `toEpochMilli` grep were read); nothing compiled or run.
