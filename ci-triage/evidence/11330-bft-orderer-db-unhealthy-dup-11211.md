# 11330 - checkErrors: BFT orderer wording of the "Sequencer is unhealthy" WARN after a pool timeout (run 42790498517)

DUPLICATE of 11211 (open, unassigned, no fix on main). Post-merge main 6c0fee0505 (#8390), job
`frontend-wall-clock-time (2)`, canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252, 31 tests passed; checkErrors flagged
one line in `canton_before_shutdown.clog`: globalSequencerSv1's `Sequencer is unhealthy, so disconnecting all members.
BFT orderer can't connect to database`, 5 s after its DB pool (8 of 8 active) timed out; storage recovered 12 ms
after the WARN and the sequencer 5 s later. Same shape as 11211, 11227, 11290 and 11310; the first hit on the Canton
3.7.3 snapshot (wording unchanged). Fifth hit in 6 days, third on main in 2 days.

- Run: https://github.com/canton-network/splice/actions/runs/42790498517, main 6c0fee0505 ("add back unused cometbft
  config (#8390)"), job 129742806248 `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`,
  attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (section 3).
- Component: Canton sequencer health (log line), ignore patterns.

## 1. Flagged line

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11330/job.log | grep -a -E 'Tests: succeeded'; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11330/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | cut -c1-300
[info] Tests: succeeded 31, failed 0, canceled 0, ignored 2, pending 0
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-11-10T09:41:34.477Z","message":"Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database","logger_name":"c.d.c.s.s.SequencerRuntime:sequencer=globalSequencerSv1/psid=global-domain::1220fbd295ff::37-0","thread_name":"canton-env-ec-624","level":
Total: 1 lines with problems.
```

## 2. Pool timeout, WARN, recovery (confirming check of 11211)

Artifact `logs-frontend-wall-clock-time-2` (id 13753024717) downloaded with
`gh run download 42790498517 --repo canton-network/splice -n logs-frontend-wall-clock-time-2 -D log/11330/logs-frontend-wall-clock-time-2`.

```
$ zcat log/11330/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-10T09:41:25" && t<="2026-11-10T09:41:50"' | grep -a 'globalSequencerSv1' | grep -a -E 'DB_CONNECTION_LOST\(13,[0-9a-f]+\)|Sequencer is unhealthy|.db-storage. is now in state Ok|.sequencer. is now in state SequencerHealthStatus\(active = true' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//; s/1220[0-9a-f]{60}/../g' | cut -c1-250 | awk '!s[$0]++'
2026-11-10T09:41:29.486Z DB_CONNECTION_LOST(13,7d323b63): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__1220fbd295ff_____37-0-44 - Connection is not available, request timed out after 5001ms (to
2026-11-10T09:41:29.486Z Refreshing state of db-storage from Ok() to Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__1220fbd295ff_____37-0-44 - Connect
2026-11-10T09:41:29.487Z 'db-storage' is now in state Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__1220fbd295ff_____37-0-44 - Connection is not avai
2026-11-10T09:41:34.477Z Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database
2026-11-10T09:41:34.489Z Refreshing state of db-storage from Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__1220fbd295ff_____37-0-44 - Connection is n
2026-11-10T09:41:34.489Z 'db-storage' is now in state Ok(). Previous state was Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__1220fbd295ff_____37-0-44
2026-11-10T09:41:39.478Z 'sequencer' is now in state SequencerHealthStatus(active = true). Previous state was SequencerHealthStatus(active = false, details = BFT orderer can't connect to database).
2026-11-10T09:41:39.479Z 'sequencer' is now in state SequencerHealthStatus(active = true). Previous state was SequencerHealthStatus(active = false, details = BFT orderer can't connect to database).
```
```
$ zcat log/11330/logs-frontend-wall-clock-time-2/canton_before_shutdown.clog.gz | grep -a -F '09:41:29.486Z' | grep -a -o 'total=[^)]*)'
total=8, active=8, idle=0, waiting=0)
total=8, active=8, idle=0, waiting=0)
total=8, active=8, idle=0, waiting=0)
```

## 3. Canton pin and ignore patterns at the run's sha and on main

```
$ git show 6c0fee0505:nix/canton-sources.json | grep -i version
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
$ git show 6c0fee0505:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
$ git show origin/main:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'; git log -1 --format='%h %ci' origin/main
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
1844b95301 2026-11-10 14:17:04 +0100
```

## 4. Parent and hit count

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11211 --jq '"#\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) comments=\(.comments) \(.title)"'
#11211 open assignees= comments=1 checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
```
Earlier hits (INDEX.tsv rows and the tracker):
```
$ awk -F'\t' '$1==11211||$1==11227||$1==11290||$1==11310' ci-triage/evidence/INDEX.tsv | cut -f1-4,7 | cut -c1-200
11211	2026-11-05	41993663804	release-line-0.9.4 44854b737c (Backport PR #8248)	checkErrors, 69 tests passed: globalSequencerSv2 DB_CONNECTION_LOST pool timeout (8/8 active) 08:55:08.439, WARN Sequence
11310	2026-11-09	42646231899	main 5dc875a64f (#8375)	checkErrors canton_before_shutdown.clog: globalSequencerSv1 `Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to dat
11290	2026-11-09	42618450792	main 38a37d9680 (#8368)	checkErrors canton_before_shutdown.clog: globalSequencerSv1 `Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to dat
$ for n in 11227 11290 11310; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:16]) \(.title[0:90])"'; done
#11227 closed/duplicate 2026-11-05T12:38 GHA Run 42020755850 : job wall-clock-time (5) in scala_test Failed :fire:
#11290 closed/duplicate 2026-11-09T13:13 GHA Run 42618450792 : job frontend-wall-clock-time (1) in scala_test Failed :fire:
#11310 closed/duplicate 2026-11-09T16:22 GHA Run 42646231899 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
$ gh api repos/DACH-NY/cn-test-failures/issues/11227/comments --jq '.[] | "-- \(.user.login) \(.created_at) \(.body[0:250])"'
-- rautenrieth-da 2026-11-05T20:49:58Z Same unignored WARN: Sequencer is unhealthy, so disconnecting all members. BFT orderer can not connect to database.
```
Per hit: 11211 (release-line-0.9.4, the parent), 11227 (release-line-0.9.4 run per the 11211 STATE note, closed as the
same WARN), 11290 and 11310 (main, 2026-11-09), 11330 (main, 2026-11-10): 5 hits in 6 days, 3 on main in 2 days.
A full-text search for the exact wording returned nothing (the apostrophe defeats the search index), so hits older
than 11211 or not linked to it may be missing.

## Verdict

Duplicate of 11211: same WARN text, same trigger (a sequencer DB pool timeout with all 8 connections active) and a
recovery within seconds. Not a test failure. The fix proposed on 11211 (extend
`Sequencer is unhealthy, so disconnecting all members. Can't connect to database` in
`project/ignore-patterns/canton_log.ignore.txt` to the BFT wording) is not on main at 1844b95301. The Canton 3.7.3
snapshot did not change the wording. Not verified: what held the 8 connections (the open question behind both
wordings; ignored for the DB-sequencer wording since splice#7643).
