# 11533 - ScanIntegrationTest: splitwellValidator pool times out on its first Postgres connection at an environment start (run 44021473992)

DUPLICATE of 11141 (family AG), with one difference in shape: not the first environment of the shard but the 8th
(ScanIntegrationTest creates one environment per test). main dbf2ab6c8f, scheduled workflow "Integration tests against
mainnet Daml version", job `wall-clock-time (3)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. All 20 tests
passed; checkErrors flags one WARN in `log/canton_network_test.clog`, at 05:36:56.388:
`DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(... HikariPool$PoolInitializationException:
Failed to initialize pool: Connection attempt timed out.` from splitwellValidator. The environment for the test
'respect rate limit' starts 7 app pools at 05:36:50.381-382 with `connectionTimeout 5000` and
`initializationFailTimeout 1`. Three connect within 12 ms, sv1 after 1.2 s, bobValidator after 2.2 s, sv1Scan after
2.5 s, and splitwellValidator gives up after 6.0 s; its retry 300 ms later connects in 8 ms. These three are the only
first connections above 0.5 s among the 296 app pools of the shard (median 8 ms). Why Postgres was slow to accept
connections for those 6 s is not in the artifacts (no Postgres log, no host metrics).

- Run: https://github.com/canton-network/splice/actions/runs/44021473992, main dbf2ab6c8f ("Pause the reward triggers
  in ScanNewNetworkBackfillingTimeBasedIntegrationTest (#8420)"), event schedule, job 133832627487
  `build / scala_test_wall_clock_time / wall-clock-time (3)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at dbf2ab6c8f).
- Component: infra (CI Postgres responsiveness), with the 5 s test connect timeout as the trigger.
- The other failed jobs of the run (`simtime (1)` = 11532, `docker-no-canton (0)`) are not part of this ref.

## 1. Flagged line

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11533/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 26 minutes, 45 seconds.
[info] Tests: succeeded 20, failed 0, canceled 0, ignored 0, pending 0
```
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11533/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-330
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11533/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -a -oE '"(logger_name|level)":"[^"]*"'
```
```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-17T05:36:56.388Z","message":"Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to database: com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize pool: Connection attempt timed out.\n\tat com.zaxxer.hikari.po
Total: 1 lines with problems.
"logger_name":"c.d.c.r.DbStorage:ScanIntegrationTest/config=2e07ec4d/validator=splitwellValidator"
"level":"WARN"
```
Canton pin:
```
git show dbf2ab6c8f:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 2. Which test was running

Formatter `log/tmp/fmt.py` as in `evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md` section 3. The artifact
is `logs-wall-clock-time-3` (id 14303562452). On macOS `zcat < file`.
```
D=log/11533/logs-wall-clock-time-3; T=$D/canton_network_test.clog.gz
zcat < $T | grep -a -E '"message":"(Starting test run|Starting test suite|Completed test suite)' | python3 -I log/tmp/fmt.py | cut -c1-120 | head -4
zcat < $T | grep -a -E '"message":"Starting creating environment for ' | python3 -I log/tmp/fmt.py | cut -c1-150 | head -9
```
```
05:30:03.697 INFO test  | Starting test run...
05:30:04.628 INFO test  | Starting test suite 'ScanIntegrationTest'...
05:38:28.638 INFO test  | Completed test suite 'ScanIntegrationTest'.
05:38:28.700 INFO test  | Starting test suite 'SvOfflineRootNamespaceKeyIntegrationTest'...
05:30:08.053 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'getAcsSnapshot respects custom timeout':
05:32:55.277 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'return dso info same as the sv app':
05:33:35.027 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'returns expected splice instance names':
05:34:13.427 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'getUpdateHistory should return 400 for invalid a
05:34:53.943 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'reject request arrays longer than maxItems with 
05:35:32.125 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'serve RewardCouponV2 created event blobs via get
05:36:09.760 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'return bft sequencers':
05:36:50.173 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'respect rate limit':
05:37:40.647 DEBU ScanIntegrationTest  | Starting creating environment for ScanIntegrationTest, test 'accept invalid headers':
```
The WARN (config 2e07ec4d) belongs to the environment of 'respect rate limit', the 8th environment of the shard, 6 m 52 s
after the test run started.

## 3. Confirming grep of family AG: pool start, timeout, retry

```
zcat < $T | grep -a 'T05:3[67]' > log/tmp/11533-0536.jsonl
grep -a -E 'Hikari|createDatabase' log/tmp/11533-0536.jsonl | python3 -I log/tmp/fmt.py | cut -c1-200 | awk '$1>="05:36:50.37" && $1<="05:36:56.71"' | grep -E 'Starting|Added connection|WARN' | grep -E -- '-(99|100|101|102|103|104|105) |WARN'
```
```
05:36:50.381 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-aliceValidator-99 - Starting...
05:36:50.381 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-sv1-104 - Starting...
05:36:50.381 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-bobValidator-100 - Starting...
05:36:50.381 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-sv1Scan-103 - Starting...
05:36:50.381 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-providerSplitwellBackend-101 - Starting...
05:36:50.382 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-splitwellValidator-102 - Starting...
05:36:50.382 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-sv1Validator-105 - Starting...
05:36:50.391 INFO HikariPool  | slick-ScanIntegrationTest-2e07ec4d-aliceValidator-99 - Added connection org.postgresql.jdbc.PgConnection@c1c8e9d
05:36:50.392 INFO HikariPool  | slick-ScanIntegrationTest-2e07ec4d-providerSplitwellBackend-101 - Added connection org.postgresql.jdbc.PgConnection@16ef3b43
05:36:50.393 INFO HikariPool  | slick-ScanIntegrationTest-2e07ec4d-sv1Validator-105 - Added connection org.postgresql.jdbc.PgConnection@6de685f4
05:36:51.562 INFO HikariPool  | slick-ScanIntegrationTest-2e07ec4d-sv1-104 - Added connection org.postgresql.jdbc.PgConnection@1960a29
05:36:52.583 INFO HikariPool  | slick-ScanIntegrationTest-2e07ec4d-bobValidator-100 - Added connection org.postgresql.jdbc.PgConnection@7b1a6926
05:36:52.911 INFO HikariPool  | slick-ScanIntegrationTest-2e07ec4d-sv1Scan-103 - Added connection org.postgresql.jdbc.PgConnection@7dd3661
05:36:56.388 WARN DbStorage validator=splitwellValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
05:36:56.689 INFO HikariDataSource  | slick-ScanIntegrationTest-2e07ec4d-splitwellValidator-102 - Starting...
05:36:56.697 INFO HikariPool  | slick-ScanIntegrationTest-2e07ec4d-splitwellValidator-102 - Added connection org.postgresql.jdbc.PgConnection@1bcbc788
```
Pool start to WARN 6.006 s (family: at most about 6.3 s); retry starts 301 ms after the WARN and connects 8 ms later
(309 ms after the WARN; the family text says "within 300 ms", which is the retry delay itself). It is the only
createDatabase WARN of the shard:
```
zcat < $T | grep -a -c 'createDatabase failed'
```
```
1
```
Pool settings of the flagged node:
```
grep -a 'Initializing database storage with config' log/tmp/11533-0536.jsonl | grep -a 'validator=splitwellValidator' | grep -a -m1 'T05:36:50' | grep -oE '"(connectionTimeout|initializationFailTimeout|numThreads)":[0-9]+'
```
```
"connectionTimeout":5000
"initializationFailTimeout":1
"numThreads":8
```

## 4. The stall is confined to this one environment start

First-connection latency (HikariDataSource `Starting...` to HikariPool `Added connection`) of every app pool of the
shard, slowest first:
```
zcat < $T | grep -a -E 'HikariDataSource|HikariPool' | grep -a -E 'Starting\.\.\.|Added connection' | python3 -I -c "
import sys,json,datetime,statistics
st={}; res=[]
for l in sys.stdin:
    j=json.loads(l); m=j['message']; t=datetime.datetime.fromisoformat(j['@timestamp'].replace('Z','+00:00'))
    p=m.split(' - ')[0]
    if 'Starting' in m: st[p]=t
    elif p in st: res.append(((t-st.pop(p)).total_seconds(),j['@timestamp'][11:23],p))
print(len(res),'pools')
for d,ts,p in sorted(res,reverse=True)[:5]: print('%.3f'%d,ts,p)
print('median %.3f'%statistics.median([r[0] for r in res]),'over 1 s:',sum(1 for r in res if r[0]>1))"
```
```
296 pools
2.530 05:36:52.911 slick-ScanIntegrationTest-2e07ec4d-sv1Scan-103
2.202 05:36:52.583 slick-ScanIntegrationTest-2e07ec4d-bobValidator-100
1.181 05:36:51.562 slick-ScanIntegrationTest-2e07ec4d-sv1-104
0.412 05:30:10.398 slick-ScanIntegrationTest-b0073d8-sv1Validator-4
0.412 05:30:10.398 slick-ScanIntegrationTest-b0073d8-sv1Scan-5
median 0.008 over 1 s: 3
```
(The splitwellValidator pool 102 appears here with its retry, 8 ms; its first attempt never connected.) Both the test
JVM and Canton log less in those seconds; lines per second in `canton_network_test.clog`:
```
awk -F'"@timestamp":"' '{print substr($2,12,8)}' log/tmp/11533-0536.jsonl | awk '$1>="05:36:48" && $1<="05:37:00"' | uniq -c
```
```
    669 05:36:48
    697 05:36:49
   2338 05:36:50
    189 05:36:51
    176 05:36:52
     81 05:36:53
     20 05:36:54
     23 05:36:55
     55 05:36:56
     77 05:36:57
     96 05:36:58
    237 05:36:59
    268 05:37:00
```
The Canton log (`canton_before_shutdown.clog.gz`) has no WARN or ERROR in 05:36:40-05:37:02 other than the usual
`PERMISSION_DENIED ... Could not resolve is_deactivated status` for the users of the new environment (ignored by the
log check), and 99 to 435 lines per second in 05:36:51-05:36:56 against 1359 to 2037 at 05:36:40-43.

## Verdict

Duplicate of 11141 (family AG; 11141 is open, 10739 and 11523 are closed as duplicates). Flake, log check only: the
retry connects and nothing else is affected. New for the family: the stall hit a later environment start (8th of the
shard, 7 minutes in), not the first one, so "at shard start" is too narrow; the trigger is any environment start
whose first connection takes longer than the 5 s `connectionTimeout`.

Fix location: test config in splice, as proposed in 11141 (`connectionTimeout = 15000` in
`apps/app/src/test/resources/include/storage-postgres.conf` and the Canton test storage config); not on main as of
02b5a51214 (`git grep -i connectionTimeout origin/main -- apps/app/src/test/resources/` prints nothing). No ignore
pattern. Nothing was changed for this ref because it is a duplicate.

Not verified: why Postgres accepted connections slowly in 05:36:50-05:36:56 (no Postgres log or host metrics in the
artifact; the GCP container log of the runner pod was not requested).
