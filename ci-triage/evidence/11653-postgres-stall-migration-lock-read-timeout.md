# 11653 - wall-clock-time (8): app db-migration lock connections hit the 15 s read timeout during a Postgres stall at an environment start (run 44498711970)

NOT a confirmed duplicate. New surface line of the Postgres stall behind family AG (parent 11141), but the AG
confirming grep (createDatabase retry WARN) finds nothing here, and it is not the 10438 race of family G either (no
NoConnectionAvailable probes). main 7666260d8d, workflow "CI post-merge to main or release-line branches", job
`wall-clock-time (8)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. All 24 tests passed; checkErrors flags 7
WARN lines in `log/canton_network_test.clog`, all from the environment start of
WalletSweepToValidatorOperatorIntegrationTest (7th of 10 suites, 45 minutes into the job): `Failed to check database
lock status ... assuming lost` and `Lock 1036997668 was lost` on splitwellValidator at 10:31:21.265, then `Locked
connection was lost, trying to rebuild` on aliceValidator, bobValidator, splitwellValidator, sv1Scan and sv1Validator
at 10:31:26.579-27.431, all on `connId=db-migration`. The 7 apps of the environment open their migration-lock
connections to the shared database `splice_apps` at 10:31:05.979-06.394. The first statement on each gets no answer
for 15 s. At 10:31:21.264 the five oldest connections fail with `SocketTimeoutException: Read timed out` (the 15 s
network timeout of the locked connection), 2 ms after the two youngest (sv1, providerSplitwellBackend) got their
answer. The Canton nodes, a separate JVM on other databases of the same Postgres, stop logging from 10:31:14 to
10:31:21 and report 6 read-only connection checks pending for 7.5 s at 10:31:20.4-20.9, then resume without a WARN.
All apps rebuild the connection, take the lock in turn and finish the migration check by 10:31:30; the first test
starts at 10:31:46. Why Postgres did not answer in 10:31:06-21 is not in the artifacts (no Postgres log, no host
metrics).

- Run: https://github.com/canton-network/splice/actions/runs/44498711970, main 7666260d8d ("Add syft to nix
  (#8554)"), event push, job 135472921946 `ci / scala_test_wall_clock_time / wall-clock-time (8)`. The run has a
  second failed job, 135472484964 `ci / daml_test / daml_test`, which is ref 11651 (family BL), not this ref.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 7666260d8d).
- Component: infra (CI Postgres service on the runner), surfacing through the Canton `DbLockedConnection` WARNs of
  the app migration lock.

## 1. Flagged lines

`gh api` needs `--allow-escape-sequences` for this job log.
```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135472921946/logs > log/11653/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11653/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 32 minutes, 15 seconds.
[info] Tests: succeeded 24, failed 0, canceled 0, ignored 0, pending 0
```
The flagged lines, as timestamp, message head, logger and level (the first one carries a stack trace):
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11653/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | sed -E 's/\\n\\tat.*//; s/"thread_name".*"level":"([A-Z]+)".*/\1/; s/WalletSweepToValidatorOperatorIntegrationTest\/config=c39536e9\///' | cut -c1-330
```
```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-19T10:31:21.265Z","message":"Failed to check database lock status for 1036997668, assuming lost: FailedToCheckLock(\n  lockId = 1036997668,\n  error = org.postgresql.util.PSQLException: This connection has been closed.
***"@timestamp":"2026-11-19T10:31:21.265Z","message":"Lock 1036997668 was lost","logger_name":"c.d.c.r.DbLockPostgres:validator=splitwellValidator/connId=db-migration/lockId=1036997668/lockId=1036997668",WARN
***"@timestamp":"2026-11-19T10:31:26.579Z","message":"Locked connection was lost, trying to rebuild","logger_name":"c.d.c.r.DbLockedConnection:validator=aliceValidator/connId=db-migration/lockId=1036997668",WARN
***"@timestamp":"2026-11-19T10:31:26.875Z","message":"Locked connection was lost, trying to rebuild","logger_name":"c.d.c.r.DbLockedConnection:validator=bobValidator/connId=db-migration/lockId=1036997668",WARN
***"@timestamp":"2026-11-19T10:31:27.162Z","message":"Locked connection was lost, trying to rebuild","logger_name":"c.d.c.r.DbLockedConnection:validator=splitwellValidator/connId=db-migration/lockId=1036997668",WARN
***"@timestamp":"2026-11-19T10:31:27.391Z","message":"Locked connection was lost, trying to rebuild","logger_name":"c.d.c.r.DbLockedConnection:scan=sv1Scan/connId=db-migration/lockId=1036997668",WARN
***"@timestamp":"2026-11-19T10:31:27.431Z","message":"Locked connection was lost, trying to rebuild","logger_name":"c.d.c.r.DbLockedConnection:validator=sv1Validator/connId=db-migration/lockId=1036997668",WARN
Total: 7 lines with problems.
```
## 2. Which suite was running

Suites of the shard: AutoIgnoreUnresponsivePartiesInMemory, ParticipantKmsIdentities, RecoverExternalParty,
ScheduledPackageVetting, TokenStandardV2Transfer, ValidatorProxy, ValidatorSequencerConnection,
WalletSurviveCantonRestart, WalletSweepToValidatorOperator, offlinekey.SvOfflineRootNamespaceKey.
```
T=log/11653/logs-wall-clock-time-8/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Completed test suite|Starting '" | grep -a 'T10:3[01]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-150 | sed -n '/10:31:02/,$p'
```
```
2026-11-19T10:31:02.890Z Completed test suite 'TokenStandardV2TransferIntegrationTest'.",
2026-11-19T10:31:03.160Z Starting test suite 'WalletSweepToValidatorOperatorIntegrationTest'...",
2026-11-19T10:31:46.204Z Starting 'WalletSweepToValidatorOperatorIntegrationTest/SV1's wallet should not be swept if the balance is under the maximum allowed balance'...",
2026-11-19T10:31:47.335Z Starting 'WalletSweepToValidatorOperatorIntegrationTest/SV1's wallet should be swept if the balance is above the maximum allowed balance'...",
2026-11-19T10:31:52.305Z Starting 'WalletSweepToValidatorOperatorIntegrationTest/SV1's wallet should check that the sweep is completed even if the first offer is not immediately accepted.'...",
```
The flagged lines fall into the environment start (10:31:03-10:31:46), before any test of the suite.

## 3. The migration-lock connections: opened at 10:31:06, first answer or read timeout at 10:31:21.26

Every app runs its Flyway check under the DB lock `db-migration` (Canton `DbMigrations.withDb`, lock counter
NODE_MIGRATIONS), and all 7 apps of the test environment share the database `splice_apps`, so they share lock id
1036997668.
```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/WalletSweepToValidatorOperatorIntegrationTest\/config=c39536e9\///; s/connId=db-migration\/lockId=1036997668/../g; s/\\n.*\)"/ ...)"/'
zcat $T | grep -a -E '"@timestamp":"2026-11-19T10:31:(0[5-9]|1|2)' | grep -a -E 'Creating new DB lock|Failed to get lock owners|Failed to acquire lock, the|Lock successfully acquired|Lock [0-9]+ was lost|Locked connection was lost|Finished setting up database schemas' | sed -E "$F" | sort | cut -c1-210
```
```
2026-11-19T10:31:05.979Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@76f3c41a",[c.d.c.r.DbLockedConnection:validator=aliceValidator/..] DEBUG
2026-11-19T10:31:05.980Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@1e4db731",[c.d.c.r.DbLockedConnection:validator=splitwellValidator/..] DEBUG
2026-11-19T10:31:06.117Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@46c382ae",[c.d.c.r.DbLockedConnection:validator=sv1Validator/..] DEBUG
2026-11-19T10:31:06.191Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@3e88c97b",[c.d.c.r.DbLockedConnection:scan=sv1Scan/..] DEBUG
2026-11-19T10:31:06.191Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@6fe29b95",[c.d.c.r.DbLockedConnection:validator=bobValidator/..] DEBUG
2026-11-19T10:31:06.341Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@2341ecc3",[c.d.c.r.DbLockedConnection:SV=sv1/..] DEBUG
2026-11-19T10:31:06.394Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@4c3be9de",[c.d.c.r.DbLockedConnection:splitwell=providerSplitwellBackend/..] DEBUG
2026-11-19T10:31:21.262Z Failed to acquire lock, the following PIDs already own it: 4054",[c.d.c.r.DbLockedConnection:SV=sv1/..] DEBUG
2026-11-19T10:31:21.264Z Failed to get lock owners for lock 1036997668: FailedToGetLockOwners( ...)",[c.d.c.r.DbLockedConnection:scan=sv1Scan/..] DEBUG
2026-11-19T10:31:21.264Z Failed to get lock owners for lock 1036997668: FailedToGetLockOwners( ...)",[c.d.c.r.DbLockedConnection:validator=aliceValidator/..] DEBUG
2026-11-19T10:31:21.264Z Failed to get lock owners for lock 1036997668: FailedToGetLockOwners( ...)",[c.d.c.r.DbLockedConnection:validator=bobValidator/..] DEBUG
2026-11-19T10:31:21.264Z Failed to get lock owners for lock 1036997668: FailedToGetLockOwners( ...)",[c.d.c.r.DbLockedConnection:validator=splitwellValidator/..] DEBUG
2026-11-19T10:31:21.264Z Failed to get lock owners for lock 1036997668: FailedToGetLockOwners( ...)",[c.d.c.r.DbLockedConnection:validator=sv1Validator/..] DEBUG
2026-11-19T10:31:21.265Z Lock 1036997668 was lost",[c.d.c.r.DbLockPostgres:validator=splitwellValidator/../lockId=1036997668] WARN
2026-11-19T10:31:21.269Z Failed to acquire lock, the following PIDs already own it: 4054",[c.d.c.r.DbLockedConnection:splitwell=providerSplitwellBackend/..] DEBUG
2026-11-19T10:31:21.517Z Failed to acquire lock, the following PIDs already own it: 4090",[c.d.c.r.DbLockedConnection:SV=sv1/..] DEBUG
2026-11-19T10:31:21.518Z Lock successfully acquired, the following PIDs now own it: 4090",[c.d.c.r.DbLockedConnection:splitwell=providerSplitwellBackend/..] DEBUG
...
2026-11-19T10:31:22.558Z Finished setting up database schemas after 17029 milliseconds",[c.d.c.r.DbMigrations:splitwell=providerSplitwellBackend] DEBUG
2026-11-19T10:31:25.506Z Lock successfully acquired, the following PIDs now own it: 4083",[c.d.c.r.DbLockedConnection:SV=sv1/..] DEBUG
2026-11-19T10:31:25.767Z Finished setting up database schemas after 20215 milliseconds",[c.d.c.r.DbMigrations:SV=sv1] DEBUG
2026-11-19T10:31:26.579Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:validator=aliceValidator/..] WARN
2026-11-19T10:31:26.817Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@3764603f",[c.d.c.r.DbLockedConnection:validator=aliceValidator/..] DEBUG
2026-11-19T10:31:26.824Z Lock successfully acquired, the following PIDs now own it: 4120",[c.d.c.r.DbLockedConnection:validator=aliceValidator/..] DEBUG
2026-11-19T10:31:26.875Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:validator=bobValidator/..] WARN
2026-11-19T10:31:27.050Z Finished setting up database schemas after 21512 milliseconds",[c.d.c.r.DbMigrations:validator=aliceValidator] DEBUG
2026-11-19T10:31:27.162Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:validator=splitwellValidator/..] WARN
2026-11-19T10:31:27.180Z Lock successfully acquired, the following PIDs now own it: 4123",[c.d.c.r.DbLockedConnection:validator=splitwellValidator/..] DEBUG
2026-11-19T10:31:27.391Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:scan=sv1Scan/..] WARN
2026-11-19T10:31:27.431Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:validator=sv1Validator/..] WARN
2026-11-19T10:31:27.452Z Finished setting up database schemas after 22072 milliseconds",[c.d.c.r.DbMigrations:validator=splitwellValidator] DEBUG
2026-11-19T10:31:29.899Z Lock successfully acquired, the following PIDs now own it: 4122",[c.d.c.r.DbLockedConnection:validator=bobValidator/..] DEBUG
```
(`...` marks retry lines of sv1 against PID 4090 that were cut by hand.) Between 10:31:06.394 and 10:31:21.262
there is no lock line at all: nobody learns whether it holds the lock. PID 4054, the owner sv1 sees at 21.262, is the
backend of one of the five timed-out connections (the only `Lock ... was lost` is splitwellValidator); it is gone
255 ms later. The second group of WARNs (26.579-27.431) is the periodic connection check (health check period 5 s)
finding the connection the driver closed at 21.264: `Connection ... is NOT valid`, then the rebuild in under 250 ms.

The cause of the five failures is a client-side read timeout:
```
zcat $T | grep -a '"@timestamp":"2026-11-19T10:31:21.26' | grep -a 'Failed to get lock owners' | head -1 | python3 -I -c "
import sys,json
for l in sys.stdin:
    d=json.loads(l); print(d['@timestamp'], d['logger_name'][-70:])
    for x in d['message'].split('\n'):
        if not x.startswith('\tat ') or 'QueryExecutorImpl' in x or 'VisibleBufferedInputStream.readMore' in x: print(x[:160])"
```
```
2026-11-19T10:31:21.264Z 39536e9/validator=aliceValidator/connId=db-migration/lockId=1036997668
Failed to get lock owners for lock 1036997668: FailedToGetLockOwners(
  lockId = 1036997668,
  error = org.postgresql.util.PSQLException: An I/O error occurred while sending to the backend.
	at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:474)
Caused by: java.net.SocketTimeoutException: Read timed out
	at org.postgresql.core.VisibleBufferedInputStream.readMore(VisibleBufferedInputStream.java:192)
	at org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:2383)
	at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:446)
	... 17 more

)
```
`DbLockedConnection.create` sets `connection.setNetworkTimeout(..., healthCheckTimeout)` and
`DbLockedConnectionConfig.healthCheckTimeout` defaults to 15 s; `DbMigrations.withDb` overrides only
`passiveCheckPeriod = 3 s`. Source: the vendored copy `canton/community/base/.../resource/DbLockedConnection.scala`
and `config/DbLockConfig.scala` at origin/main, NOT checked against the 3.7.4-snapshot jar; the log agrees with it:
the five connections are created at 05.979-06.191 and time out together at 21.264, 15.07 to 15.29 s later.

## 4. Canton, on the same Postgres, stalls for the last 7 s of the same window

Log lines per second of the Canton JVM and of the test JVM, 10:31:04 to 10:31:23 (seconds without a line are absent):
```
C=log/11653/logs-wall-clock-time-8/canton_before_shutdown.clog.gz
for f in $C $T; do zcat $f | grep -a -E '^\{"@timestamp":"2026-11-19T10:31:(0[4-9]|1[0-9]|2[0-3])' | awk -F'"@timestamp":"' '{print substr($2,1,19)}' | sort | uniq -c | awk '{printf "%s=%s ", substr($2,18,2), $1} END{print ""}'; done
```
```
04=284 05=126 06=51 07=95 08=294 09=49 10=127 11=86 12=99 13=79 14=13 15=1 17=1 18=1 20=6 21=8227 22=13472 23=14 
04=9 05=128 06=45 07=34 08=34 09=33 10=46 11=41 12=35 13=8 14=2 15=16 17=1 18=1 19=2 21=85 22=72 23=46 
```
The six Canton lines at second 20, the only such lines of the whole shard:
```
zcat $C | grep -a 'connection check read-only still not completed' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | sort | cut -c1-200
```
```
2026-11-19T10:31:20.408Z Task connection check read-only still not completed after 7500 milliseconds. Continue waiting...",[c.d.c.r.DbLockedConnection:participant=aliceParticipant/connId=pool-main] IN
2026-11-19T10:31:20.577Z Task connection check read-only still not completed after 7500 milliseconds. Continue waiting...",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv1/connId=pool-2] INFO
2026-11-19T10:31:20.708Z Task connection check read-only still not completed after 7500 milliseconds. Continue waiting...",[c.d.c.r.DbLockedConnection:sequencer=splitwellSequencer/connId=pool-0] INFO
2026-11-19T10:31:20.768Z Task connection check read-only still not completed after 7500 milliseconds. Continue waiting...",[c.d.c.r.DbLockedConnection:mediator=splitwellUpgradeMediator/connId=pool-1] 
2026-11-19T10:31:20.858Z Task connection check read-only still not completed after 7500 milliseconds. Continue waiting...",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv1/connId=pool-2] INFO
2026-11-19T10:31:20.890Z Task connection check read-only still not completed after 7500 milliseconds. Continue waiting...",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv4/connId=pool-2] INFO
```
So the Canton nodes' DB checks hang from about 10:31:12.9, the nodes go quiet at 10:31:14 and everything resumes in
the same 10 ms as the apps' first answers (21.26). Both JVMs keep logging timers during the gap (BFT view changes at
14.980 and 17.103 in Canton, `Task db-migration still not completed after 10 seconds` at 15.56-15.98 in the test
JVM), so this is not a frozen runner: two independent clients wait on Postgres. The Canton connections survive
because their checks waited 8 s, under the 15 s timeout; the app connections waited the full 15 s. The Canton window
has no WARN or ERROR other than ignored `UserNotFound` lines.

## 5. Not a hit of the known families

AG (11141) signature and 10438 (family G) signature, counted over the whole app log:
```
echo "createDatabase WARN: $(zcat $T | grep -a -c 'DbStorage.createDatabase failed, retrying')"
zcat $T | grep -a 'NoConnectionAvailable' | awk -F'"@timestamp":"' '{print substr($2,1,24)}'
zcat $T | grep -a -E 'Lock [0-9]+ was lost|Locked connection was lost|SocketTimeoutException: Read timed out' | awk -F'"@timestamp":"' '{print substr($2,1,19)}' | sort | uniq -c
```
```
createDatabase WARN: 0
2026-11-19T10:25:01.090Z
      6 2026-11-19T10:31:21
      2 2026-11-19T10:31:26
      3 2026-11-19T10:31:27
```
- AG: no createDatabase retry WARN; the 14 Hikari pools of this environment each connect within 0.45 s (`Starting...`
  10:31:05.169-05.552, last `Added connection` 05.961). Postgres accepted connections and then stopped answering,
  where AG is a slow accept.
- G / 10438: that race is three `NoConnectionAvailable` read-only probes 1-9 ms after a lock check. The only
  `NoConnectionAvailable` of the shard is one probe at 10:25:01, six minutes earlier and without a rebuild. Here the
  connections are closed by a socket read timeout.
- Tracker search (`"Locked connection was lost"`, `"Failed to check database lock status"`, `"was lost"
  db-migration` in DACH-NY/cn-test-failures): 10438, 10302, 11155 (family G, Canton nodes), 9400, 4532, 1283; none on
  `connId=db-migration` of an app.

## Verdict

New surface of a Postgres stall at an environment start; flake, 1 hit. Not confirmed as a duplicate: related to
family AG (parent 11141: Postgres slow for all its clients for a few seconds at an environment start, cause never in
the artifacts), and recorded there as a variant, but the AG confirming grep does not match. Whether it is closed as a
duplicate of 11141 or kept as its own item is the reviewer's call.

Fix location: not in splice test code. The 15 s read timeout and the WARN level are Canton (`DbLockedConnection`,
`DbLockPostgres`), and the migration-lock config is hard-coded in `DbMigrations.withDb`, with no config key reaching
it from an app. The AG test-config fix (`connectionTimeout = 15000` in `storage-postgres.conf`) would not have
helped: the pools connected in time. An ignore pattern is not proposed: `Locked connection was lost` and `Lock ...
was lost` are the only signal of 10438 and of real DB loss. The cause of the stall needs a Postgres log or host
metrics of the runner (the Postgres service container log is not in the artifacts). No fix branch.

Open lead, not verified: the stall ends within 2 ms of the moment the five timed-out clients close their sockets
(21.262 vs 21.264), and it started for the lock connections 7 s before it reached Canton. That fits either a stall
that happened to last 15 s or one that was released by those backends going away (PID 4054 held the advisory lock).
The logs cannot tell these apart.

NOT verified: the Canton source citations against the 3.7.4-snapshot jar; anything on the Postgres side; the runner
node and its co-tenants; whether other shards of the run saw the stall (they passed).
