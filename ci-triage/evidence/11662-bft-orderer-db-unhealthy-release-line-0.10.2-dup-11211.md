# 11662 - checkErrors on release-line-0.10.2: BFT orderer wording of the "Sequencer is unhealthy" WARN (run 44526571007)

DUPLICATE of 11211 (closed as completed 2026-11-12, fixed on main by splice#8425; also family E, missing backport).
Post-merge release-line-0.10.2 cc14d2c66a ("Backport PR #8581 to release-line-0.10.2 (#8583)"), job
`frontend-wall-clock-time (2)`, Canton 3.7.3, 34 tests passed, 0 failed; checkErrors flagged one line in
`canton_before_shutdown.clog`: splitwellSequencer's `Sequencer is unhealthy, so disconnecting all members. BFT orderer
can't connect to database` at 13:11:41.444, 0.2 s after its DB health check timed out (5005 ms), 44 s before the first
suite started; storage and sequencer healthy again 5 s later. The ignore line was extended to the BFT wording on main by
splice#8425 (f1e385ffac); that commit is on neither release-line-0.10.2 nor release-line-0.10.1. The
`custom container implementation failed` annotation is only the wrapper of the exit code; the log has no
`*** FAILED ***`.

- Run: https://github.com/canton-network/splice/actions/runs/44526571007, release-line-0.10.2 cc14d2c66a ("Backport PR
  #8581 to release-line-0.10.2 (#8583)"), job 135569046720
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`, the only failed job of the run.
- Runtime canton: 3.7.3 (section 3).
- Component: Canton sequencer health (log line), ignore patterns; missing backport.

## 1. Flagged line

All tests pass; the one flagged line is the BFT wording of the WARN.

```
$ sed -E 's/\x1b\[[0-9;]*m//g' log/11662/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-400
##[error]Error: failed to run script step (id bafe5d20-e41e-11f1-ac0b-c9d641d2a09d): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[info] All tests passed.
[info] Run completed in 27 minutes, 37 seconds.
[info] Tests: succeeded 34, failed 0, canceled 0, ignored 0, pending 0
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11662/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in check-sbt-output' | cut -c1-1800
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-11-19T13:11:41.444Z","message":"Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database","logger_name":"c.d.c.s.s.SequencerRuntime:sequencer=splitwellSequencer/psid=splitwell::12200a420522::36-0","thread_name":"canton-env-ec-226","level":"WARN","trace-id":"a872a69faa223bc267b0702e1ae958ba","span-name":"health-check","span-id":"3a699ccfc738b4b9"***
Total: 1 lines with problems.
```

## 2. Health-check timeout, WARN, recovery (confirming check of 11211)

Artifact `logs-frontend-wall-clock-time-2` (id 14536870784) downloaded with
`gh run download 44526571007 --repo canton-network/splice -n logs-frontend-wall-clock-time-2 -D log/11662/logs-frontend-wall-clock-time-2`.
Same sequence as the earlier hits: DB_CONNECTION_LOST from the DB health check, the WARN, db-storage Ok and the
sequencer active again within 5 s. The WARN occurs once in the log.

```
$ zcat log/11662/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-19T13:11:25" && t<="2026-11-19T13:12:00"' | grep -a 'splitwellSequencer' | grep -a -E 'DB_CONNECTION_LOST\(13,[0-9a-f]+\)|Sequencer is unhealthy|.db-storage. is now in state Ok|.sequencer. is now in state SequencerHealthStatus\(active = true' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//; s/1220[0-9a-f]{60}/../g' | cut -c1-250 | awk '!s[$0]++'
2026-11-19T13:11:41.253Z DB_CONNECTION_LOST(13,42049e39): Database health check failed to establish a valid connection: slick-splitwellSequencer-splitwell__12200a420522_____36-0-40 - Connection is not available, request timed out after 5005ms (total=
2026-11-19T13:11:41.254Z Refreshing state of db-storage from Ok() to Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-splitwellSequencer-splitwell__12200a420522_____36-0-40 - Connection 
2026-11-19T13:11:41.255Z 'db-storage' is now in state Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-splitwellSequencer-splitwell__12200a420522_____36-0-40 - Connection is not availabl
2026-11-19T13:11:41.444Z Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database
2026-11-19T13:11:46.259Z Refreshing state of db-storage from Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-splitwellSequencer-splitwell__12200a420522_____36-0-40 - Connection is not a
2026-11-19T13:11:46.259Z 'db-storage' is now in state Ok(). Previous state was Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-splitwellSequencer-splitwell__12200a420522_____36-0-40 - C
2026-11-19T13:11:46.453Z 'sequencer' is now in state SequencerHealthStatus(active = true). Previous state was SequencerHealthStatus(active = false, details = BFT orderer can't connect to database).
2026-11-19T13:11:46.454Z 'sequencer' is now in state SequencerHealthStatus(active = true). Previous state was SequencerHealthStatus(active = false, details = BFT orderer can't connect to database).
$ zcat log/11662/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | grep -a -c "Sequencer is unhealthy, so disconnecting"
1
```

Difference to the earlier hits (pool 8 of 8 active): here the pool reports idle connections at the timeout, on the
splitwell sequencer instead of a global one.

```
$ zcat log/11662/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | grep -a 'splitwellSequencer' | grep -a 'DB_CONNECTION_LOST(13' | grep -a -o -E 'total=[^)]*\)' | awk '!s[$0]++'
total=6, active=2, idle=4, waiting=0)
```

The WARN falls into Canton startup, before the first suite, in a stretch where the Canton log slows down for about
7 s (13:11:34 to 13:11:40) and then catches up.

```
$ zcat log/11662/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | awk '$1>="2026-11-19T13:05" && $1<="2026-11-19T13:14"'
2026-11-19T13:12:25.538Z Starting test suite 'SvFrontendIntegrationTest'...",
$ zcat log/11662/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,19); if (t>="2026-11-19T13:11:30" && t<="2026-11-19T13:11:50") c[t]++} END{for(k in c) print k, c[k]}' | sort
2026-11-19T13:11:30 306
2026-11-19T13:11:31 397
2026-11-19T13:11:32 230
2026-11-19T13:11:33 197
2026-11-19T13:11:34 61
2026-11-19T13:11:35 70
2026-11-19T13:11:36 103
2026-11-19T13:11:37 90
2026-11-19T13:11:38 94
2026-11-19T13:11:39 161
2026-11-19T13:11:40 96
2026-11-19T13:11:41 762
2026-11-19T13:11:42 544
2026-11-19T13:11:43 433
2026-11-19T13:11:44 300
2026-11-19T13:11:45 438
2026-11-19T13:11:46 329
2026-11-19T13:11:47 499
2026-11-19T13:11:48 355
2026-11-19T13:11:49 349
2026-11-19T13:11:50 442
```

## 3. Canton pin, ignore patterns and backport state

The ignore line at the run's sha has only the DB-sequencer wording; main has both since f1e385ffac (#8425), which is
on neither active 0.10 release line.

```
$ git show cc14d2c66a:nix/canton-sources.json | grep -m1 version
  "version": "3.7.3",
$ git show cc14d2c66a:project/ignore-patterns/canton_log.ignore.txt | grep -n -i "connect to database"
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
$ git show origin/main:project/ignore-patterns/canton_log.ignore.txt | grep -n -i "connect to database"
163:Sequencer is unhealthy, so disconnecting all members. (Can't|BFT orderer can't) connect to database
$ git log -1 --format='%h %ci %s' f1e385ffac
f1e385ffac 2026-11-12 20:46:04 +0000 Ignore BFT orderer wording of the 'Sequencer is unhealthy' WARN in checkErrors (#8425)
$ for b in release-line-0.10.1 release-line-0.10.2; do git merge-base --is-ancestor f1e385ffac origin/$b && echo "$b present" || echo "$b MISSING"; git show origin/$b:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'; done; git log -1 --format='%h %ci' origin/release-line-0.10.2
release-line-0.10.1 MISSING
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
release-line-0.10.2 MISSING
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
76246c657b 2026-11-19 13:03:19 +0000
$ git merge-base cc14d2c66a origin/main | xargs git log -1 --format='%h %ad %s' --date=short
4db185089a 2026-11-12 Bump Canton to 3.7.3 (#8453)
```

## 4. Parent

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11211 --jq '"#\(.number) \(.state)/\(.state_reason // "-") closed=\(.closed_at) \(.title)"'
#11211 closed/completed closed=2026-11-12T20:46:06Z checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "BFT orderer" created:>=2026-11-12' --jq '.items[]|"#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:100])"'
#11402 closed/duplicate 2026-11-12 GHA Run 43303565707 : job simtime (1) in scala_test Failed :fire:
#11393 closed/duplicate 2026-11-12 GHA Run 43233107446 : job wall-clock-time (2) in scala_test Failed :fire:
```
Earlier hits pointing to 11211 (INDEX.tsv): 11227, 11290, 11310, 11330, 11393. 11211 is closed as completed (fixed),
not as a duplicate.

## Verdict

Duplicate of 11211: same WARN text, same trigger (the sequencer's DB health check times out, DB_CONNECTION_LOST) and
recovery within 5 s; no test affected (the WARN is 44 s before the first suite starts). Flake, log check only. The
fix exists on main (splice#8425, f1e385ffac, one line in `project/ignore-patterns/canton_log.ignore.txt`) but the
release lines 0.10.1 and 0.10.2 branched before it; the resolution is a backport of #8425 to release-line-0.10.2 (and
0.10.1), `git cherry-pick -x -s f1e385ffac` on a branch off the release line. No fix branch written (duplicate).
No open PR that mentions #8425 was found
(`gh pr list -R canton-network/splice --search "8425 in:title,body" --state all` printed nothing). Not verified: why
the health check timed out with 4 of 6 pool connections idle (the earlier hits had the pool exhausted, 8 of 8
active); the 7 s log slowdown before the WARN suggests a stall of the runner or its Postgres rather than pool
exhaustion. Whether the cherry-pick applies cleanly (line 167 on the release line versus 163 on main) was not tried.
