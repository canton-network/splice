# 11155 - splitwellMediator `Locked connection was lost` at Canton startup (run 41610142743)

DUPLICATE of 10438 / 10302 (family G, DbLockedConnection read-only probe vs lock check race). main 8fd9921238,
job `wall-clock-time (3)`, Canton 3.7.1. All 59 tests passed; checkErrors flags exactly one WARN in
`log/canton_before_shutdown.clog`: `Locked connection was lost, trying to rebuild` on splitwellMediator pool-2 at
09:26:50.058, 2 min after Canton started and before the first test suite. Three read-only probes of the same
KeepAliveConnection fail with `NoConnectionAvailable` 1-7 ms after a `DbLockPostgres: checking lock` on the same node;
the connection is rebuilt 99 ms later. No functional effect.

- Run: https://github.com/canton-network/splice/actions/runs/41610142743, main 8fd9921238 ("[ci] Bump react and
  @types/react in /apps (#8053)"), job 125830351196 `ci / scala_test_wall_clock_time / wall-clock-time (3)`, attempt 1.
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at 8fd9921238).
- Component: Canton (DbLockedConnection / DbLockPostgres).

## 1. Flagged lines

The only problem line (the console masks `{}` as `***`).
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11155/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in check-sbt-output' | cut -c1-700 | awk '!s[$0]++'
```
```
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-11-03T09:26:50.058Z","message":"Locked connection was lost, trying to rebuild","logger_name":"c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2","thread_name":"canton-env-ec-67","level":"WARN"***
Total: 1 lines with problems.
```

Canton pin:
```
git show 8fd9921238:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.1",
```

## 2. Timeline: Canton startup, before any test

Canton starts at 09:24:20; the first suite starts at 09:28:35, so no test was running at 09:26:50.
```
T=log/11155/logs-wall-clock-time-3/canton_before_shutdown.clog.gz; zcat $T | head -c 300
zcat log/11155/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): " | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-140 | awk '$1<"2026-11-03T09:29"' | tail -4
```
```
{"@timestamp":"2026-11-03T09:24:20.534Z","message":"Starting Canton version 3.7.1","logger_name":"c.d.canton.CantonCommunityApp$","thread_name":"main","level":"INFO"}
2026-11-03T09:28:35.739Z Starting test suite 'GovernanceLockIntegrationTest'...",
```

## 3. Family G confirming check: each probe failure pairs with a lock check

Probe failures, the WARN and the rebuild on splitwellMediator:
```
zcat $T | grep -a 'mediator=splitwellMediator' | grep -a -E 'Failed to check if connection|Locked connection was lost|Successfully rebuilt' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-03T09:24:27.513Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=db-migration/lockId=760415524] INFO
2026-11-03T09:24:29.778Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-main] INFO
2026-11-03T09:24:29.983Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-wait] INFO
2026-11-03T09:24:30.495Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-0] INFO
2026-11-03T09:24:30.496Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-1] INFO
2026-11-03T09:24:30.497Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] INFO
2026-11-03T09:26:38.641Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@66c1e40 is read-only: Failed to check new connection for read-only: com.digitalasset.canton.
2026-11-03T09:26:44.040Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@66c1e40 is read-only: Failed to check new connection for read-only: com.digitalasset.canton.
2026-11-03T09:26:50.047Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@66c1e40 is read-only: Failed to check new connection for read-only: com.digitalasset.canton.
2026-11-03T09:26:50.058Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] WARN
2026-11-03T09:26:50.157Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] INFO
```
The lock check right before each probe failure (1 ms, 6 ms, 9 ms earlier):
```
zcat $T | grep -a 'mediator=splitwellMediator' | grep -a -E 'T09:26:(38\.6[0-4]|44\.0[0-4])' | grep -a -E 'checking lock|checking connection|Failed to check' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
zcat $T | grep -a 'mediator=splitwellMediator' | grep -a 'T09:26:4[5-9]\|T09:26:5' | grep -aiE 'lock|connection|NoConnection|probe|keep' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | sed -n 9,19p
```
```
2026-11-03T09:26:38.639Z Running queued action: com.digitalasset.canton.resource.DbLockedConnection: checking connection",[c.d.c.t.WallClock:mediator=splitwellMediator] D
2026-11-03T09:26:38.640Z Running queued action: com.digitalasset.canton.resource.DbLockedConnection: checking connection",[c.d.c.t.WallClock:mediator=splitwellMediator] D
2026-11-03T09:26:38.640Z Running queued action: com.digitalasset.canton.resource.DbLockPostgres: checking lock",[c.d.c.t.WallClock:mediator=splitwellMediator] DEBUG
2026-11-03T09:26:38.641Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@66c1e40 is read-only: Failed to check new connection for read-
2026-11-03T09:26:44.031Z Running queued action: com.digitalasset.canton.resource.DbLockedConnection: checking connection",[c.d.c.t.WallClock:mediator=splitwellMediator] D
2026-11-03T09:26:44.034Z Running queued action: com.digitalasset.canton.resource.DbLockPostgres: checking lock",[c.d.c.t.WallClock:mediator=splitwellMediator] DEBUG
2026-11-03T09:26:44.040Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@66c1e40 is read-only: Failed to check new connection for read-
2026-11-03T09:26:50.038Z Running queued action: com.digitalasset.canton.resource.DbLockPostgres: checking lock",[c.d.c.t.WallClock:mediator=splitwellMediator] DEBUG
2026-11-03T09:26:50.039Z Running queued action: com.digitalasset.canton.resource.DbLockedConnection: checking connection",[c.d.c.t.WallClock:mediator=splitwellMediator] DEBUG
2026-11-03T09:26:50.047Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@66c1e40 is read-only: Failed to check new connection for read-only: com.digitalasset.canton.resource.DbStorage$NoConnectionAvailable: No free connection
2026-11-03T09:26:50.058Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] WARN
2026-11-03T09:26:50.060Z Trying to close defunct connection com.digitalasset.canton.resource.KeepAliveConnection@66c1e40 before recovery",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] DEBUG
2026-11-03T09:26:50.062Z Aborting scheduled task 'com.digitalasset.canton.resource.DbLockPostgres: checking lock' due to shutdown",[c.d.c.t.WallClock:mediator=splitwellMediator] DEBUG
2026-11-03T09:26:50.155Z Succeeded to reconnect to database",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] DEBUG
2026-11-03T09:26:50.155Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@7c5a3458",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] DEBUG
2026-11-03T09:26:50.156Z Trying to become active..",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] DEBUG
2026-11-03T09:26:50.157Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=splitwellMediator/connId=pool-2] INFO
```

## Verdict

Duplicate of 10438 (family G, `Locked connection was lost`; same mechanism as 10302: three read-only probes collide
with the lock check on the single KeepAliveConnection, then a rebuild in under 100 ms). Canton race, flake, no
functional effect. Per family G no ignore pattern. Not verified: the current state of 10438 and 10302 (both older
than the issues export of 2026-11-03, which starts 2026-10-20); whether Canton 3.7.1 changed the probe or the lock check
(no jar comparison done).
