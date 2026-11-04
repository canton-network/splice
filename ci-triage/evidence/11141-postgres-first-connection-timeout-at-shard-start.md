# 11141 - SvFrontendIntegrationTest startup: 7 app pools time out on their first Postgres connection (run 41461163244)

new (family AG, provisional letter; earlier one-off 10739, closed not planned). main 7052977e68, job
`frontend-wall-clock-time (2)`, Canton 3.7.1. All 31 tests passed; checkErrors flags 7 WARNs in
`log/canton_network_test.clog`, all at 14:39:33.097: `DbStorage.createDatabase failed, retrying in 300ms:
DatabaseCreationFailed(... HikariPool$PoolInitializationException: Failed to initialize pool: Connection attempt timed
out.` for sv1, sv1Scan, sv1Validator, aliceValidator, bobValidator, splitwellValidator and providerSplitwellBackend.
This is the first environment of the shard (test run start 14:39:19.819). All 8 pools start at 14:39:26.819 with
`connectionTimeout 5000` and `initializationFailTimeout 1`; sv2 gets its first connection only after 5.8 s, the other 7
give up at 6.3 s and their retries 300 ms later connect within 13 ms. Postgres (shared with the Canton process) was slow
for all clients in that window: the Canton log thins out to 2 lines at 14:39:29 and 9 Canton lock checks come back
`LockCheckRejected` within 3 ms at 14:39:30.78. Why Postgres stalled is not in the artifacts (no Postgres log, no host
metrics).

- Run: https://github.com/canton-network/splice/actions/runs/41461163244, main 7052977e68 ("Re-enable
  react-hooks/static-components (#8178)"), job 125335853576
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`, attempt 1.
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at 7052977e68).
- Component: infra (CI Postgres responsiveness at shard start), with the 5 s test connect timeout as the trigger.

## 1. Flagged lines

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11141/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in check-sbt-output' | cut -c1-700 | awk '!s[$0]++'
```
```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-02T14:39:33.097Z","message":"Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to database: com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize pool: Connection attempt timed out.\n\tat com.zaxxer.hikari.pool.HikariPool.throwPoolInitializationException(HikariPool.java:584)\n\tat com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:571)\n\tat com.zaxxer.hikari.pool.HikariPool.<init>(HikariPool.java:98)\n\tat com.zaxxer.hikari.HikariDataSource.<init>(HikariDataSource.java:80)\n\tat slick.jdbc.hikaricp.HikariCPJdbcDataSource$.forConfig(HikariC
Total: 7 lines with problems.
```
Canton pin:
```
git show 7052977e68:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.1",
```

## 2. First environment of the shard

Formatter `log/tmp/fmt.py` as in `evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md` section 3.
```
D=log/11141/logs-frontend-wall-clock-time-2
zcat $D/canton_network_test.clog.gz | grep -a -E '^\{"@timestamp":"2026-11-02T14:39:(1[89]|2[0-6])' | python3 -I log/tmp/fmt.py | cut -c1-200 | awk '!s[substr($0,14,60)]++' | head -40
```
```
14:39:19.819 INFO test  | Starting test run...
14:39:20.729 INFO test  | Starting test suite 'SplitwellUpgradeFrontendIntegrationTest'...
14:39:20.750 INFO test  | Entering 'splitwell frontend with upgraded domain'
14:39:20.753 INFO test  | Test ignored: 'SplitwellUpgradeFrontendIntegrationTest/splitwell frontend with upgraded domain should create per domain install contracts'
14:39:20.761 INFO test  | Leaving 'splitwell frontend with upgraded domain'
14:39:20.765 INFO test  | Completed test suite 'SplitwellUpgradeFrontendIntegrationTest'.
14:39:20.804 INFO test  | Starting test suite 'SvFrontendIntegrationTest'...
14:39:20.811 DEBU ConcurrentEnvironmentLimiter$ | org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest: New => Queued after 0s (1 pending)
14:39:24.725 DEBU SvFrontendIntegrationTest  | Starting creating environment for SvFrontendIntegrationTest:
14:39:24.738 DEBU SvFrontendIntegrationTest  | Finished creating environment: Running plugins before
14:39:24.824 INFO 9fc8b1d2  | Initializing open telemetry with trace-exporter=Disabled, metrics-enabled=true
14:39:24.840 INFO OpenTelemetryViews$ | Enabling exponential histograms
14:39:24.856 INFO MetricsRegistry$ | Exposing metrics for Prometheus on port 0.0.0.0:25000
14:39:24.978 DEBU 9fc8b1d2  | sv2:http-api=5214;sv1:http-api=5114;sv4:http-api=5414;sv3:http-api=5314;sv2Scan:http-api=5112;sv3Scan:http-api=5212;sv4Scan:http-api=5312;sv
14:39:24.979 INFO 9fc8b1d2  | Deriving 8 as number of threads from 'sys.runtime.availableProcessors()'. Please use '-Dscala.concurrent.context.numThreads' to override.
14:39:25.319 INFO Slf4jLogger  | Slf4jLogger started
14:39:26.346 INFO 9fc8b1d2  | SpliceEnvironment with config = {
14:39:26.497 INFO 9fc8b1d2  | Starting node aliceValidator
14:39:26.504 INFO 9fc8b1d2  | Starting node bobValidator
14:39:26.504 INFO 9fc8b1d2  | Starting node providerSplitwellBackend
14:39:26.505 INFO 9fc8b1d2  | Starting node splitwellValidator
14:39:26.505 INFO 9fc8b1d2  | Starting node sv1
14:39:26.506 INFO 9fc8b1d2  | Starting node sv1Scan
14:39:26.506 INFO 9fc8b1d2  | Starting node sv1Validator
14:39:26.507 INFO 9fc8b1d2  | Starting node sv2
14:39:26.571 INFO sv1  | Creating storage, num-combined: 8
14:39:26.572 INFO splitwellValidator  | Creating storage, num-combined: 8
14:39:26.572 INFO sv1Scan  | Creating storage, num-combined: 8
14:39:26.572 INFO providerSplitwellBackend  | Creating storage, num-combined: 8
14:39:26.572 INFO sv2  | Creating storage, num-combined: 8
14:39:26.572 INFO bobValidator  | Creating storage, num-combined: 8
14:39:26.572 INFO sv1Validator  | Creating storage, num-combined: 8
14:39:26.572 INFO aliceValidator  | Creating storage, num-combined: 8
14:39:26.719 DEBU DbStorage validator=splitwellValidator | Initializing database storage with config: Config(SimpleConfigObject({"connectionInitSql":"SET client_connection_check_interval TO '5000 ms'
14:39:26.719 DEBU DbStorage SV=sv2 | Initializing database storage with config: Config(SimpleConfigObject({"connectionInitSql":"SET client_connection_check_interval TO '5000 ms'
14:39:26.719 DEBU DbStorage validator=bobValidator | Initializing database storage with config: Config(SimpleConfigObject({"connectionInitSql":"SET client_connection_check_interval TO '5000 ms'
14:39:26.719 DEBU DbStorage scan=sv1Scan | Initializing database storage with config: Config(SimpleConfigObject({"connectionInitSql":"SET client_connection_check_interval TO '5000 ms'
14:39:26.719 DEBU DbStorage validator=aliceValidator | Initializing database storage with config: Config(SimpleConfigObject({"connectionInitSql":"SET client_connection_check_interval TO '5000 ms'
14:39:26.719 DEBU DbStorage SV=sv1 | Initializing database storage with config: Config(SimpleConfigObject({"connectionInitSql":"SET client_connection_check_interval TO '5000 ms'
14:39:26.719 DEBU DbStorage validator=sv1Validator | Initializing database storage with config: Config(SimpleConfigObject({"connectionInitSql":"SET client_connection_check_interval TO '5000 ms'
```
The pool settings logged by the apps (password redacted by the command):
```
zcat $D/canton_network_test.clog.gz | grep -a -m1 'Initializing database storage with config' | python3 -I -c "import sys,json,re; m=json.loads(sys.stdin.readline())['message']; print(re.sub(r'\"password\":\"[^\"]*\"','\"password\":\"<redacted>\"',m)[:1500])" | grep -oE '"(connectionTimeout|initializationFailTimeout|numThreads)":[0-9]+'
```
```
"connectionTimeout":5000
"initializationFailTimeout":1
"numThreads":8
```

## 3. Pool start, timeouts, retry

```
zcat $D/canton_network_test.clog.gz | grep -a 'T14:3[89]' | grep -a -E 'DbStorage|Hikari|createDatabase|Postgres|jdbc' | python3 -I log/tmp/fmt.py | cut -c1-220 | awk '!s[substr($0,14)]++' | sed -n '17,40p'
```
```
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-aliceValidator-2 - Starting...
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv1Validator-5 - Starting...
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv1Scan-7 - Starting...
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-splitwellValidator-3 - Starting...
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-providerSplitwellBackend-4 - Starting...
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv1-1 - Starting...
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-bobValidator-8 - Starting...
14:39:26.819 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv2-6 - Starting...
14:39:32.621 INFO HikariPool  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv2-6 - Added connection org.postgresql.jdbc.PgConnection@3d3f4746
14:39:32.628 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv2-6 - Start completed.
14:39:33.097 WARN providerSplitwellBackend  | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
14:39:33.097 WARN DbStorage validator=aliceValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
14:39:33.097 WARN DbStorage validator=bobValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
14:39:33.097 WARN DbStorage validator=sv1Validator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
14:39:33.097 WARN DbStorage validator=splitwellValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
14:39:33.097 WARN DbStorage scan=sv1Scan | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
14:39:33.097 WARN DbStorage SV=sv1 | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
14:39:33.410 INFO HikariPool  | slick-SvFrontendIntegrationTest-9fc8b1d2-providerSplitwellBackend-4 - Added connection org.postgresql.jdbc.PgConnection@e0eefc5
14:39:33.410 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-providerSplitwellBackend-4 - Start completed.
14:39:33.410 INFO HikariPool  | slick-SvFrontendIntegrationTest-9fc8b1d2-splitwellValidator-3 - Added connection org.postgresql.jdbc.PgConnection@39f2cdef
14:39:33.410 INFO HikariPool  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv1Validator-5 - Added connection org.postgresql.jdbc.PgConnection@732a491b
14:39:33.410 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-splitwellValidator-3 - Start completed.
14:39:33.411 INFO HikariDataSource  | slick-SvFrontendIntegrationTest-9fc8b1d2-sv1Validator-5 - Start completed.
14:39:33.411 INFO HikariPool  | slick-SvFrontendIntegrationTest-9fc8b1d2-bobValidator-8 - Added connection org.postgresql.jdbc.PgConnection@3f6ded43
```

## 4. Postgres was slow for the Canton process too

The Canton JVM (separate process, same Postgres) keeps logging, so it is not a host-wide freeze of both JVMs, but
its rate drops at 14:39:28-29 and its largest gaps are just before 14:39:30.78:
```
for f in canton_network_test.clog.gz canton_before_shutdown.clog.gz; do echo "== $f"; zcat $D/$f | grep -a -oE '^\{"@timestamp":"2026-11-02T14:39:(2[4-9]|3[0-4])' | awk '{c[substr($0,33,2)]++} END {for (k in c) print k":"c[k]}' | sort | tr '\n' ' '; echo; done
for f in canton_before_shutdown.clog.gz canton_network_test.clog.gz; do echo "== $f largest gaps 14:39:20-35"; zcat $D/$f | grep -a -oE '^\{"@timestamp":"2026-11-02T14:39:(2[0-9]|3[0-5])\.[0-9]+' | cut -c32- | awk -F: '{print $2}' | sort -n | awk 'NR>1{g=$1-p; if (g>0.8) printf "%.3f -> %.3f gap %.3f s\n", p, $1, g} {p=$1}'; done
```
```
== canton_network_test.clog.gz
24:9 25:1 26:41 32:3 33:169 34:157 
== canton_before_shutdown.clog.gz
24:63 25:76 26:59 27:37 28:22 29:2 30:166 31:88 32:176 33:80 34:1 
== canton_before_shutdown.clog.gz largest gaps 14:39:20-35
29.047 -> 29.951 gap 0.904 s
29.951 -> 30.782 gap 0.831 s
34.078 -> 35.561 gap 1.483 s
== canton_network_test.clog.gz largest gaps 14:39:20-35
20.814 -> 24.725 gap 3.911 s
25.319 -> 26.346 gap 1.027 s
26.819 -> 32.621 gap 5.802 s
```
(The test JVM gaps at 20.8-24.7 and 26.8-32.6 are the environment limiter queue and the wait for the pools; nothing
else runs in that JVM yet.)

Canton lock checks: 9 of the 10 `LockCheckRejected` in minute 14:39 fall within 3 ms at 14:39:30.783-786, right after
the 0.8-0.9 s silence; elsewhere the rate is 1-10 per minute:
```
zcat $D/canton_before_shutdown.clog.gz | grep -a 'LockCheckRejected' | python3 -I log/tmp/fmt.py | awk '{print $1, $3}' | head -15
zcat $D/canton_before_shutdown.clog.gz | grep -a 'LockCheckRejected' | grep -a -oE '"@timestamp":"[^"]{19}' | cut -c15-30 | awk '{c[$1]++} END {for (k in c) print k, c[k]}' | sort | head -6
```
```
14:35:41.850 957704230
14:37:37.120 652439040
14:37:50.120 760415527
14:38:16.121 999016229
14:38:59.420 957704230
14:39:02.221 760415527
14:39:30.783 593300261
14:39:30.784 889008679
14:39:30.785 593300263
14:39:30.784 889008677
14:39:30.785 855359270
14:39:30.785 760415525
14:39:30.785 1071272998
14:39:30.784 778728743
14:39:30.786 1071272960
2026-11-02T14:35 1
2026-11-02T14:37 2
2026-11-02T14:38 2
2026-11-02T14:39 10
2026-11-02T14:40 2
2026-11-02T14:41 6
```
No Postgres server log or host metrics in either artifact (`logs-frontend-wall-clock-time-2`,
`logs-frontend-wall-clock-time-2-runner`):
```
echo "matches: $(ls $D log/11141/runner | grep -ciE 'postgres|^pg|metric|vmstat|^top')"
```
```
matches: 0
```

## 5. Earlier hit and existing patterns

10739 (closed not planned 2026-10-28, run 40693933620, simtime (1)): the same WARN from the Canton process at startup
(globalSequencerSv2, 14:42:25, before any test), commented as a one-off slow DB connection. No ignore pattern covers the
line:
```
grep -rn -iE 'createDatabase|Connection attempt timed out|PoolInitialization' project/ignore-patterns/ | cut -c1-220
```
```
```
The test storage config sets no connect timeout, so the Canton default of 5000 ms applies:
```
git show 7052977e68:apps/app/src/test/resources/include/storage-postgres.conf | sed -n 1,24p | grep -n -E 'config \{|properties|tcpKeepAlive|max-connections'
```
```
4:    config {
6:      properties = {
15:        tcpKeepAlive = true
21:      max-connections = 8
```

## Verdict

New, family AG (provisional): at shard start, Postgres takes more than the 5 s pool connection timeout to hand out
first connections; Canton's `createDatabase` retry logs a WARN and succeeds 300 ms later. Second hit in a week (10739
on 2026-10-28, Canton process; 11141 on 2026-11-02, app process). Flake, infra-side cause (Postgres stall for 2-6 s;
the reason is not in the artifacts), no functional effect.

Fix options (not written):
1. Test config: raise the pool connection timeout for the apps in
   `apps/app/src/test/resources/include/storage-postgres.conf` (`_shared.storage.config`, add `connectionTimeout = 15000`
   next to `dataSourceClass`), and the same for the Canton node storage in the test Canton configs for the 10739
   variant. A slow first connection is then not a failure; a real outage still fails after the retries.
2. Otherwise `Infrequent / no repro` until it recurs, since the stall itself is outside the test.
No ignore pattern: a createDatabase failure at any other time is real signal.
Not verified: the Postgres-side cause (CPU, IO or connection backlog); whether 15 s would have covered sv2 (5.8 s) and
the stall here (yes by the timestamps, not tested); the exact Canton config path for the Canton node storage.

## 4. Postgres log and node metrics (added 2026-11-04)

Postgres log (GCP export `downloaded-logs-20261104-103714.json`, pod self-hosted-k8s-large-runner-8mrj7-workflow,
container postgres, 14:32:18 to 14:43:22): no ERROR, restart or connection-limit message in 14:39:20-14:39:35; a
time-based checkpoint 14:37:22.769 to 14:41:53.698 (`write=269.303 s, sync=1.571 s, total=270.929 s; sync
files=19786`). Node `gke-cn-splicenet-cn-apps-node-pool-3--eb220c51-m4q7` (us-central1-c; node pool since replaced).
User, from Metrics Explorer: node CPU usage rose from about 3 to 9 cores in the window; other pods on the node (small
runners) below 0.05 cores. So the slowdown is the job's own startup burst (8 apps in the test JVM, which sees 8
processors, plus Canton and the Vite dev servers), not a neighbour. Pod CPU limit utilization (1 min) peaked at 0.945,
never 1; sub-minute CFS throttling not visible (no cAdvisor throttling metrics). Node allocatable cores not checked.
