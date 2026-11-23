# 11641 - wall-clock-time (1): 5 of 7 app pools time out on the first Postgres connection at the 8th suite of the shard (run 44406720503)

DUPLICATE of 11141 (family AG), in the mid-shard shape of 11533. main e59381448e, workflow "CI post-merge to main or
release-line branches", job `wall-clock-time (1)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. All 19 tests
passed; checkErrors flags 5 WARNs in `log/canton_network_test.clog`, all at 22:36:03.906:
`DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(... HikariPool$PoolInitializationException:
Failed to initialize pool: Connection attempt timed out.`, one per app of the environment of
MultiHostValidatorOperatorIntegrationTest (sv1, aliceValidator, bobValidator, splitwellValidator,
providerSplitwellBackend), the 8th and last suite of the shard, 22 minutes after the test run started. The 7 pools of
that environment start at 22:35:57.897-898 with `connectionTimeout 5000` and `initializationFailTimeout 1`; sv1Scan
and sv1Validator connect after 2.7 s, the other five give up after 6.009 s; their retries start at 22:36:04.207 and
connect within 7 to 8 ms. Why Postgres was slow to accept connections in 22:35:57-22:36:04 is not in the artifacts
(no Postgres log, no host metrics).

- Run: https://github.com/canton-network/splice/actions/runs/44406720503, main e59381448e ("Revert "implement
  participant admin API auth support in clusters (#8216)" (#8571)"), event push, job 135164320224
  `ci / scala_test_wall_clock_time / wall-clock-time (1)`. The run has a second non-green job, 135164307339
  `ci / scala_test_docker_compose / docker-compose (1)` (cancelled), which is not part of this ref.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at e59381448e).
- Component: infra (CI Postgres responsiveness), with the 5 s test connect timeout as the trigger.

## 1. Flagged lines

`gh api` needs `--allow-escape-sequences` for this job log.
```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135164320224/logs > log/11641/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11641/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 24 minutes, 4 seconds.
[info] Tests: succeeded 19, failed 0, canceled 0, ignored 0, pending 0
```
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11641/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-330 | sed -n '1,2p;$p'
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11641/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -a -oE '"@timestamp":"[^"]*"|"(logger_name|level)":"[^"]*"' | paste - - -
```
```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-18T22:36:03.906Z","message":"Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to database: com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize pool: Connection attempt timed out.\n\tat com.zaxxer.hikari.po
Total: 5 lines with problems.
"@timestamp":"2026-11-18T22:36:03.906Z"	"logger_name":"c.d.c.r.DbStorage:MultiHostValidatorOperatorIntegrationTest/config=1e4711e6/splitwell=providerSplitwellBackend"	"level":"WARN"
"@timestamp":"2026-11-18T22:36:03.906Z"	"logger_name":"c.d.c.r.DbStorage:MultiHostValidatorOperatorIntegrationTest/config=1e4711e6/validator=aliceValidator"	"level":"WARN"
"@timestamp":"2026-11-18T22:36:03.906Z"	"logger_name":"c.d.c.r.DbStorage:MultiHostValidatorOperatorIntegrationTest/config=1e4711e6/validator=splitwellValidator"	"level":"WARN"
"@timestamp":"2026-11-18T22:36:03.906Z"	"logger_name":"c.d.c.r.DbStorage:MultiHostValidatorOperatorIntegrationTest/config=1e4711e6/validator=bobValidator"	"level":"WARN"
"@timestamp":"2026-11-18T22:36:03.906Z"	"logger_name":"c.d.c.r.DbStorage:MultiHostValidatorOperatorIntegrationTest/config=1e4711e6/SV=sv1"	"level":"WARN"
```
Canton pin:
```
git show e59381448e:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 2. Which suite was running

Formatter `log/tmp/fmt.py` as in `evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md` section 3. The artifact
is `logs-wall-clock-time-1` (id 14479035899). On macOS `zcat < file`.
```
D=log/11641/logs-wall-clock-time-1; T=$D/canton_network_test.clog.gz
zcat < $T | grep -a -E '"message":"(Starting test run|Starting test suite|Completed test suite)' | python3 -I log/tmp/fmt.py | cut -c1-130
zcat < $T | grep -a 'T22:3[56]' > log/tmp/11641-2235.jsonl
grep -a -E '"message":"Starting creating environment for ' log/tmp/11641-2235.jsonl | python3 -I log/tmp/fmt.py | cut -c1-170
```
```
22:13:30.482 INFO test  | Starting test run...
22:13:31.137 INFO test  | Starting test suite 'AnsIntegrationTest'...
22:24:23.361 INFO test  | Completed test suite 'AnsIntegrationTest'.
22:24:23.411 INFO test  | Starting test suite 'TestTokenV2SettlementIntegrationTest'...
22:26:47.587 INFO test  | Completed test suite 'TestTokenV2SettlementIntegrationTest'.
22:26:47.596 INFO test  | Starting test suite 'ValidatorProxyIntegrationTest'...
22:29:21.182 INFO test  | Completed test suite 'ValidatorProxyIntegrationTest'.
22:29:21.193 INFO test  | Starting test suite 'UnclaimedSvRewardsScriptIntegrationTest'...
22:31:12.238 INFO test  | Completed test suite 'UnclaimedSvRewardsScriptIntegrationTest'.
22:31:12.246 INFO test  | Starting test suite 'UpdateHistoryIntegrationTest'...
22:32:31.117 INFO test  | Completed test suite 'UpdateHistoryIntegrationTest'.
22:32:31.124 INFO test  | Starting test suite 'SvReconcileSynchronizerConfigIntegrationTest'...
22:34:45.808 INFO test  | Completed test suite 'SvReconcileSynchronizerConfigIntegrationTest'.
22:34:45.817 INFO test  | Starting test suite 'WalletExpirationsIntegrationTest'...
22:35:57.498 INFO test  | Completed test suite 'WalletExpirationsIntegrationTest'.
22:35:57.504 INFO test  | Starting test suite 'MultiHostValidatorOperatorIntegrationTest'...
22:37:34.368 INFO test  | Completed test suite 'MultiHostValidatorOperatorIntegrationTest'.
22:35:57.691 DEBU MultiHostValidatorOperatorIntegrationTest  | Starting creating environment for MultiHostValidatorOperatorIntegrationTest:
```
The WARNs (config 1e4711e6) belong to the environment start of the last suite, 0.4 s after the previous suite
(WalletExpirationsIntegrationTest) completed.

## 3. Confirming grep of family AG: pool start, timeout, retry

```
grep -a -E 'Hikari|createDatabase' log/tmp/11641-2235.jsonl | python3 -I log/tmp/fmt.py | cut -c1-200 | awk '$1>="22:35:55" && $1<="22:36:06"' | grep -E 'Starting|Added connection|WARN'
```
```
22:35:57.897 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1Validator-240 - Starting...
22:35:57.898 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-providerSplitwellBackend-241 - Starting...
22:35:57.897 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-bobValidator-237 - Starting...
22:35:57.897 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-aliceValidator-239 - Starting...
22:35:57.897 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-splitwellValidator-238 - Starting...
22:35:57.898 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1Scan-243 - Starting...
22:35:57.898 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1-242 - Starting...
22:36:00.603 INFO HikariPool  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1Scan-243 - Added connection org.postgresql.jdbc.PgConnection@166b6833
22:36:00.605 INFO HikariPool  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1Validator-240 - Added connection org.postgresql.jdbc.PgConnection@2c175234
22:36:00.608 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1Scan-244 - Starting...
22:36:00.608 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1Validator-245 - Starting...
22:36:03.906 WARN providerSplitwellBackend  | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
22:36:03.906 WARN DbStorage validator=aliceValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
22:36:03.906 WARN DbStorage validator=splitwellValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
22:36:03.906 WARN DbStorage validator=bobValidator | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
22:36:03.906 WARN DbStorage SV=sv1 | Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying in 300ms: DatabaseCreationFailed(Failed to connect to d
22:36:04.207 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-providerSplitwellBackend-241 - Starting...
22:36:04.207 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1-242 - Starting...
22:36:04.207 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-bobValidator-237 - Starting...
22:36:04.207 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-aliceValidator-239 - Starting...
22:36:04.207 INFO HikariDataSource  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-splitwellValidator-238 - Starting...
22:36:04.214 INFO HikariPool  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-bobValidator-237 - Added connection org.postgresql.jdbc.PgConnection@1cf5679a
22:36:04.214 INFO HikariPool  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-providerSplitwellBackend-241 - Added connection org.postgresql.jdbc.PgConnection@25
22:36:04.215 INFO HikariPool  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-sv1-242 - Added connection org.postgresql.jdbc.PgConnection@47e6c7cc
22:36:04.215 INFO HikariPool  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-aliceValidator-239 - Added connection org.postgresql.jdbc.PgConnection@1cc66d8d
22:36:04.215 INFO HikariPool  | slick-MultiHostValidatorOperatorIntegrationTest-1e4711e6-splitwellValidator-238 - Added connection org.postgresql.jdbc.PgConnection@1e424112
```
Pool start to WARN 6.009 s (family: at most about 6.3 s). The retries start 301 ms after the WARN and connect 7 to
8 ms later, so "Added connection for the same pool on the first retry" holds. The second pools of sv1Scan and
sv1Validator (244, 245; started 22:36:00.608) also needed 4.0 s for their first connection (22:36:04.601), so the
slow window covers 22:35:57.9 to about 22:36:04.2 for every client of that environment. These are the only
createDatabase WARNs of the shard; the two minutes hold one other WARN, a TopupMemberTrafficTrigger transient-failure
line of the previous suite at 22:35:09.663, 48 s earlier and unrelated to the environment start:
```
zcat < $T | grep -a -c 'createDatabase failed'
grep -a -E '"level":"(WARN|ERROR)"' log/tmp/11641-2235.jsonl | grep -a -v createDatabase | python3 -I log/tmp/fmt.py | cut -c1-160
```
```
5
22:35:09.663 WARN TopupMemberTrafficTrigger validator=splitwellValidator | Encountered 4 consecutive transient failures (polling interval 500 milliseconds).
```
Pool settings:
```
grep -a 'Initializing database storage' log/tmp/11641-2235.jsonl | grep MultiHost | head -1 | grep -oiE '.{3}(connectionTimeout|initializationFailTimeout|numThreads).{0,12}' | sort -u
```
```
,\"connectionTimeout\":5000,\"da
,\"initializationFailTimeout\":1,\"keepa
,\"numThreads\":8,\"poolN
```

## 4. State of the parent and of the proposed fix

```
gh issue view 11141 -R DACH-NY/cn-test-failures --json number,state,title --jq '[.number,.state,.title]|@tsv'
git -C ~/Projects/splice grep -n -i 'connectionTimeout' origin/main -- apps/app/src/test/resources | head
```
```
11141	OPEN	Shard start: app DB pools time out on the first Postgres connection (5 s) while the runner is slow, createDatabase retry WARN
```
The second command prints nothing (origin/main fetched 2026-11-19): the test storage config still does not raise
`connectionTimeout`, so the family's proposed fix is not on main.

## Verdict

Duplicate of 11141 (family AG), confirmed by the family's grep: pools `Starting...` 6.009 s before the WARN, `Added
connection` for the same pools on the first retry, nothing else fails, all tests green. Flake, infra-triggered (CI
Postgres slow to accept connections for about 6 s at an environment start), tripped by the 5 s test connect timeout.
Third mid-shard or shard-start hit in three days (11533, 11536, 11641). Fix location per the family: test config
(`connectionTimeout = 15000` in `apps/app/src/test/resources/include/storage-postgres.conf` and the Canton test
storage config); no ignore pattern. Nothing fixed here, no fix branch. Not verified: the cause of the Postgres stall
(no Postgres log or host metrics in the artifacts); the cancelled docker-compose (1) job of the same run was not
looked at.
