# 11536 - frontend-simtime (0): all 7 app pools time out on the first Postgres connection at shard start (run 44056333812)

DUPLICATE of 11141 (family AG), in the original shape of 11141: the first environment of the shard. main 69b922dca8,
workflow "CI post-merge to main or release-line branches", job `frontend-simtime (0)`, Canton
3.7.4-snapshot.20261112.20915.0.v9e248620. All 13 tests passed; checkErrors flags 7 WARNs in
`log/canton_network_test.clog`, all at 09:51:41.022:
`DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(... HikariPool$PoolInitializationException:
Failed to initialize pool: Connection attempt timed out.`, one per app of the first environment
(DevelopmentFundFrontendTimeBasedIntegrationTest: sv1, sv1Scan, sv1Validator, aliceValidator, bobValidator,
splitwellValidator, providerSplitwellBackend). The 7 pools start at 09:51:34.734 with `connectionTimeout 5000` and
`initializationFailTimeout 1`, 7.7 s after the test run started; none connects, all give up after 6.288 s; the retries
start at 09:51:41.325 and all connect at 09:51:41.684-685. The next 7 pools (09:51:42.484) connect within 10 ms.
Why Postgres did not accept connections in 09:51:34-09:51:41 is not in the artifacts (no Postgres log, no host
metrics).

- Run: https://github.com/canton-network/splice/actions/runs/44056333812, main 69b922dca8 ("Download the sequencer
  onboarding state from the sponsor as a file (#7795)"), event push, job 133946990212
  `ci / scala_test_frontend_simtime / frontend-simtime (0)`, the only failed job of the run.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 69b922dca8).
- Component: infra (CI Postgres responsiveness), with the 5 s test connect timeout as the trigger.

## 1. Flagged lines

`gh api` needs `--allow-escape-sequences` for this job log (it refuses otherwise and writes an empty file).
```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/133946990212/logs > log/11536/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11536/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 9 minutes, 16 seconds.
[info] Tests: succeeded 13, failed 0, canceled 0, ignored 0, pending 0
```
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11536/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-330 | sed -n '1,2p;$p'
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11536/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -a -oE '"@timestamp":"[^"]*"|"(logger_name|level)":"[^"]*"' | paste - - -
```
```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-17T09:51:41.022Z","message":"Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to database: com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize pool: Connection attempt timed out.\n\tat com.zaxxer.hikari.po
Total: 7 lines with problems.
"@timestamp":"2026-11-17T09:51:41.022Z"	"logger_name":"c.d.c.r.DbStorage:DevelopmentFundFrontendTimeBasedIntegrationTest/config=b425ff32/validator=sv1Validator"	"level":"WARN"
"@timestamp":"2026-11-17T09:51:41.022Z"	"logger_name":"c.d.c.r.DbStorage:DevelopmentFundFrontendTimeBasedIntegrationTest/config=b425ff32/scan=sv1Scan"	"level":"WARN"
"@timestamp":"2026-11-17T09:51:41.022Z"	"logger_name":"c.d.c.r.DbStorage:DevelopmentFundFrontendTimeBasedIntegrationTest/config=b425ff32/SV=sv1"	"level":"WARN"
"@timestamp":"2026-11-17T09:51:41.022Z"	"logger_name":"c.d.c.r.DbStorage:DevelopmentFundFrontendTimeBasedIntegrationTest/config=b425ff32/splitwell=providerSplitwellBackend"	"level":"WARN"
"@timestamp":"2026-11-17T09:51:41.022Z"	"logger_name":"c.d.c.r.DbStorage:DevelopmentFundFrontendTimeBasedIntegrationTest/config=b425ff32/validator=aliceValidator"	"level":"WARN"
"@timestamp":"2026-11-17T09:51:41.022Z"	"logger_name":"c.d.c.r.DbStorage:DevelopmentFundFrontendTimeBasedIntegrationTest/config=b425ff32/validator=splitwellValidator"	"level":"WARN"
"@timestamp":"2026-11-17T09:51:41.022Z"	"logger_name":"c.d.c.r.DbStorage:DevelopmentFundFrontendTimeBasedIntegrationTest/config=b425ff32/validator=bobValidator"	"level":"WARN"
```
Canton pin:
```
git show 69b922dca8:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 2. Which suite was running

Formatter `log/tmp/fmt.py` as in `evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md` section 3. The artifact
is `logs-frontend-simtime-0` (id 14318431498). On macOS `zcat < file`.
```
D=log/11536/logs-frontend-simtime-0; T=$D/canton_network_test.clog.gz
zcat < $T | grep -a -E '"message":"(Starting test run|Starting test suite|Completed test suite)' | python3 -I log/tmp/fmt.py | cut -c1-130
zcat < $T | grep -a -E '"message":"Starting creating environment for ' | python3 -I log/tmp/fmt.py | cut -c1-170
```
```
09:51:27.003 INFO test  | Starting test run...
09:51:27.966 INFO test  | Starting test suite 'DevelopmentFundFrontendTimeBasedIntegrationTest'...
09:56:45.824 INFO test  | Completed test suite 'DevelopmentFundFrontendTimeBasedIntegrationTest'.
09:56:45.896 INFO test  | Starting test suite 'WalletFrontendTimeBasedIntegrationTest'...
09:58:16.056 INFO test  | Completed test suite 'WalletFrontendTimeBasedIntegrationTest'.
09:58:16.063 INFO test  | Starting test suite 'WalletTransactionHistoryFrontendTimeBasedIntegrationTest'...
09:59:27.693 INFO test  | Completed test suite 'WalletTransactionHistoryFrontendTimeBasedIntegrationTest'.
09:59:27.700 INFO test  | Starting test suite 'ScanFrontendTimeBasedIntegrationTest'...
10:00:42.905 INFO test  | Completed test suite 'ScanFrontendTimeBasedIntegrationTest'.
09:51:31.701 DEBU DevelopmentFundFrontendTimeBasedIntegrationTest  | Starting creating environment for DevelopmentFundFrontendTimeBasedIntegrationTest:
09:56:46.189 DEBU WalletFrontendTimeBasedIntegrationTest  | Starting creating environment for WalletFrontendTimeBasedIntegrationTest:
09:58:16.346 DEBU WalletTransactionHistoryFrontendTimeBasedIntegrationTest  | Starting creating environment for WalletTransactionHistoryFrontendTimeBasedIntegrationTest:
09:59:27.987 DEBU ScanFrontendTimeBasedIntegrationTest  | Starting creating environment for ScanFrontendTimeBasedIntegrationTest:
```
The WARNs (config b425ff32) belong to the first environment of the shard, 14 s after the
test run started.

## 3. Confirming grep of family AG: pool start, timeout, retry

```
zcat < $T | grep -a 'T09:5[12]' > log/tmp/11536-0951.jsonl
grep -a -E 'Hikari|createDatabase' log/tmp/11536-0951.jsonl | python3 -I log/tmp/fmt.py | cut -c1-200 | awk '$1>="09:51:30" && $1<="09:51:42"' | grep -E 'Starting|Added connection|WARN'
```
```
09:51:34.734 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-providerSplitwellBackend-4 - Starting...
09:51:34.734 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1-5 - Starting...
09:51:34.734 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1Validator-1 - Starting...
09:51:34.734 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1Scan-2 - Starting...
09:51:34.734 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-aliceValidator-3 - Starting...
09:51:34.734 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-bobValidator-7 - Starting...
09:51:34.734 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-splitwellValidator-6 - Starting...
09:51:41.022 WARN DbStorage validator=sv1Validator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
09:51:41.022 WARN DbStorage scan=sv1Scan | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
09:51:41.022 WARN DbStorage SV=sv1 | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
09:51:41.022 WARN providerSplitwellBackend  | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
09:51:41.022 WARN DbStorage validator=aliceValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
09:51:41.022 WARN DbStorage validator=splitwellValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
09:51:41.022 WARN DbStorage validator=bobValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
09:51:41.325 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1Scan-2 - Starting...
09:51:41.325 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-splitwellValidator-6 - Starting...
09:51:41.325 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-aliceValidator-3 - Starting...
09:51:41.325 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1-5 - Starting...
09:51:41.325 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-providerSplitwellBackend-4 - Starting...
09:51:41.325 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1Validator-1 - Starting...
09:51:41.326 INFO HikariDataSource  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-bobValidator-7 - Starting...
09:51:41.684 INFO HikariPool  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1-5 - Added connection org.postgresql.jdbc.PgConnection@23af2cca
09:51:41.685 INFO HikariPool  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1Scan-2 - Added connection org.postgresql.jdbc.PgConnection@3168eaf3
09:51:41.685 INFO HikariPool  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-sv1Validator-1 - Added connection org.postgresql.jdbc.PgConnection@21af2f78
09:51:41.684 INFO HikariPool  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-splitwellValidator-6 - Added connection org.postgresql.jdbc.PgConnection@2652
09:51:41.684 INFO HikariPool  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-bobValidator-7 - Added connection org.postgresql.jdbc.PgConnection@590c118e
09:51:41.684 INFO HikariPool  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-aliceValidator-3 - Added connection org.postgresql.jdbc.PgConnection@3929563
09:51:41.684 INFO HikariPool  | slick-DevelopmentFundFrontendTimeBasedIntegrationTest-b425ff32-providerSplitwellBackend-4 - Added connection org.postgresql.jdbc.PgConnectio
```
Pool start to WARN 6.288 s (family: at most about 6.3 s). The retries start 303 ms after the WARN and connect 359 ms
later, 662 ms after the WARN (family text: "within 300 ms"; 11141 300 ms, 11533 309 ms), so the first connections of
the retry were still slow, and the next batch of pools at 09:51:42.484 connects in 7 to 10 ms. These are the only
createDatabase WARNs of the shard, and the minute has no other WARN or ERROR:
```
zcat < $T | grep -a -c 'createDatabase failed'
grep -a -E '"level":"(WARN|ERROR)"' log/tmp/11536-0951.jsonl | grep -a -v -c createDatabase
```
```
7
0
```
Pool settings:
```
grep -a 'Initializing database storage' log/tmp/11536-0951.jsonl | head -1 | grep -oiE '.{3}(connectionTimeout|initializationFailTimeout|numThreads).{0,12}' | sort -u
```
```
,\"connectionTimeout\":5000,\"da
,\"initializationFailTimeout\":1,\"keepa
,\"numThreads\":8,\"poolN
```

## 4. Around the stall

Lines per second in `canton_network_test.clog` (the test JVM only waits) and in `canton-simtime_before_shutdown.clog`
(Canton thins out from 09:51:35 and recovers at 09:51:40-41):
```
awk -F'"@timestamp":"' '{print substr($2,12,8)}' log/tmp/11536-0951.jsonl | awk '$1>="09:51:33" && $1<="09:51:43"' | uniq -c | tr '\n' ';'
zcat < $D/canton-simtime_before_shutdown.clog.gz | grep -a 'T09:51:[234]' | awk -F'"@timestamp":"' '{print substr($2,12,8)}' | awk '$1>="09:51:33" && $1<="09:51:43"' | sort | uniq -c | tr '\n' ';'
```
```
      2 09:51:33;     69 09:51:34;     18 09:51:35;     32 09:51:36;     32 09:51:37;     32 09:51:38;     32 09:51:39;     32 09:51:40;     89 09:51:41;    155 09:51:42;    173 09:51:43;
    236 09:51:33;    265 09:51:34;     70 09:51:35;    100 09:51:36;    119 09:51:37;    127 09:51:38;    127 09:51:39;    204 09:51:40;    249 09:51:41;    334 09:51:42;    223 09:51:43;
```
The Canton log has no WARN or ERROR in 09:51:20-09:51:49:
```
zcat < $D/canton-simtime_before_shutdown.clog.gz | grep -a 'T09:51:[234]' | grep -a -c -E '"level":"(WARN|ERROR)"'
```
```
0
```

## Verdict

Duplicate of 11141 (family AG; 11141 is open, 10739, 11523 and 11533 are closed as duplicates). Flake, log check
only: the retry connects, all 13 tests pass, nothing else is affected. Same shape as 11141 itself (first environment
of a frontend shard, every app pool of the environment).

Fix location: test config in splice, as proposed in 11141 (`connectionTimeout = 15000` in
`apps/app/src/test/resources/include/storage-postgres.conf` and the Canton test storage config); not on main as of
02b5a51214 (`git grep -i connectionTimeout origin/main -- apps/app/src/test/resources/` prints nothing). No ignore
pattern. Nothing was changed for this ref because it is a duplicate. Third hit in two days (11523 on 2026-11-16,
11533 and 11536 on 2026-11-17), which argues for writing the 11141 fix.

Not verified: why Postgres accepted no connection in 09:51:34-09:51:41 (no Postgres log or host metrics in the
artifact; the GCP container log of the runner pod was not requested).
