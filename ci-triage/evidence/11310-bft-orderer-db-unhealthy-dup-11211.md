# 11310 - checkErrors: BFT orderer wording of the "Sequencer is unhealthy" WARN after a pool timeout (run 42646231899)

DUPLICATE of 11211 (open, unassigned, no fix PR). Post-merge main 5dc875a64f (#8375), job `frontend-wall-clock-time (2)`,
canton 3.7.2, 30 tests passed; checkErrors flagged one line in `canton_before_shutdown.clog`: globalSequencerSv1's
`Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database`, 5 s after its DB pool
(8 of 8 active) timed out, recovered 6 s later. The DB-sequencer wording of the same WARN is ignored, the BFT wording is
not; 11211 proposes extending the ignore line. Since #8234 every test runs DA BFT, so every such pool timeout produces
this wording.

- Run: https://github.com/canton-network/splice/actions/runs/42646231899, main 5dc875a64f ("Fix backports: don't
  JSON-encode the commit SHA (#8375)"), job 129263943605 `ci / scala_test_frontend_wall_clock_time /
  frontend-wall-clock-time (2)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: Canton sequencer health (log line), ignore patterns.

## 1. Flagged line

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11310/job.log | grep -a -E 'Tests: succeeded'; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11310/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | cut -c1-300
[info] Tests: succeeded 30, failed 0, canceled 0, ignored 2, pending 0
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-11-09T15:57:39.530Z","message":"Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database","logger_name":"c.d.c.s.s.SequencerRuntime:sequencer=globalSequencerSv1/psid=global-domain::12205a28e396::37-0","thread_name":"canton-env-ec-238","level":
Total: 1 lines with problems.
```

## 2. Pool timeout, WARN, recovery

```
$ zcat log/11310/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-09T15:57:34" && t<="2026-11-09T15:57:46"' | grep -a 'globalSequencerSv1' | grep -a -E 'DB_CONNECTION_LOST\(13,4bfb6501\)|Sequencer is unhealthy|.db-storage. is now in state Ok|.sequencer. is now in state SequencerHealthStatus\(active = true' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//; s/1220[0-9a-f]{60}/../g' | cut -c1-250 | awk '!s[$0]++'
2026-11-09T15:57:34.659Z DB_CONNECTION_LOST(13,4bfb6501): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__12205a28e396_____37-0-44 - Connection is not available, request timed out after 5000ms (to
2026-11-09T15:57:39.530Z Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database
2026-11-09T15:57:43.664Z 'db-storage' is now in state Ok(). Previous state was Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__12205a28e396_____37-0-44
2026-11-09T15:57:45.717Z 'sequencer' is now in state SequencerHealthStatus(active = true). Previous state was SequencerHealthStatus(active = false, details = BFT orderer can't connect to database).
```
```
$ zcat log/11310/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | grep -a -F '15:57:34.659Z' | grep -a -o 'total=[^)]*)'
total=8, active=8, idle=0, waiting=0)
total=8, active=8, idle=0, waiting=0)
```

## 3. Ignore patterns at the run's sha and on main

```
$ git show 5dc875a64f:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'; git fetch -q origin main; git show origin/main:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
```

## 4. Parent

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11211 --jq '"#\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) \(.title)"'
#11211 open assignees= checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
```

## Verdict

Duplicate of 11211: same WARN text, same trigger (a sequencer DB pool timeout with all 8 connections active) and a
recovery within seconds. Not a test failure; the fix proposed on 11211 (extend
`Sequencer is unhealthy, so disconnecting all members. Can't connect to database` in
`project/ignore-patterns/canton_log.ignore.txt` to the BFT wording) is not on main. Not verified: what held the 8
connections (the pool exhaustion itself is the open question behind both wordings; it was ignored for the DB-sequencer
wording since splice#7643).
