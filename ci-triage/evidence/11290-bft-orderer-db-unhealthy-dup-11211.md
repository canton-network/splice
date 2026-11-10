# 11290 - checkErrors: BFT orderer wording of the "Sequencer is unhealthy" WARN after a pool timeout (run 42618450792)

DUPLICATE of 11211 (open, unassigned, no fix PR). Post-merge main 38a37d9680 (#8368), job `frontend-wall-clock-time (1)`,
canton 3.7.2, 22 tests passed; checkErrors flagged one line in `canton_before_shutdown.clog`: globalSequencerSv1's
`Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database` at 12:55:17.025, 3 s
after its DB pool (8 of 8 active) timed out, recovered 5 s later. The DB-sequencer wording of the same WARN is ignored,
the BFT wording is not; 11211 proposes extending the ignore line, which is still not on main. 4th hit in 5 days
(11211, 11227, 11310, 11290).

- Run: https://github.com/canton-network/splice/actions/runs/42618450792, main 38a37d9680 ("Double encode parentheses
  in Cloud Armor alert log links (#8368)"), job 129164498577 `ci / scala_test_frontend_wall_clock_time /
  frontend-wall-clock-time (1)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: Canton sequencer health (log line), ignore patterns.

## 0. Issue and job

The issue names run 42618450792, job `frontend-wall-clock-time (1) in scala_test`, main 38a37d9; the run has one
attempt and exactly one failed job matching that name (the other failure is `simtime (3)`, its own issue).

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11290 --jq '"#\(.number) \(.state) \(.created_at) \(.title)\n\(.body)"'
#11290 open 2026-11-09T13:13:18Z GHA Run 42618450792 : job frontend-wall-clock-time (1) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42618450792).
Job: frontend-wall-clock-time (1) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [38a37d9](https://github.com/canton-network/splice/commit/38a37d968049d92130d4eb3a6a467ef7310066af) Double encode parentheses in Cloud Armor alert log links (#8368)
Actor: nicu-da
```
```
$ gh api repos/canton-network/splice/actions/runs/42618450792/attempts/1/jobs --paginate --jq '.jobs[] | select(.conclusion=="failure") | "\(.id) \(.run_attempt) \(.name)"'; gh api repos/canton-network/splice/actions/runs/42618450792 --jq '"\(.head_sha) \(.run_attempt) \(.created_at) \(.display_title)"'
129164498577 1 ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (1)
129164505315 1 ci / scala_test_sim_time / simtime (3)
38a37d968049d92130d4eb3a6a467ef7310066af 1 2026-11-09T12:36:41Z Double encode parentheses in Cloud Armor alert log links (#8368)
```
```
$ git show 38a37d9680:nix/canton-sources.json | grep -i version
  "version": "3.7.2",
```

## 1. Flagged line

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11290/job.log | grep -a -E 'Tests: succeeded'; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11290/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | cut -c1-300
[info] Tests: succeeded 22, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-11-09T12:55:17.025Z","message":"Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database","logger_name":"c.d.c.s.s.SequencerRuntime:sequencer=globalSequencerSv1/psid=global-domain::12209dee4ff0::37-0","thread_name":"canton-env-ec-41","level":"
Total: 1 lines with problems.
```

## 2. Which test was running

WalletAuth0FrontendIntegrationTest (started 12:52:17, first test succeeded 12:56:24); no test failed.

```
$ zcat log/11290/logs-frontend-wall-clock-time-1/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): " | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | awk '$1 < "2026-11-09T12:56:25"' | tail -3 | cut -c1-200
2026-11-09T12:52:17.266Z Starting test suite 'WalletAuth0FrontendIntegrationTest'...",
2026-11-09T12:56:24.213Z Test succeeded: 'WalletAuth0FrontendIntegrationTest/A wallet UI with a backend configured to accept auth0 tokens should allow login via auth0 and persist user name on refresh'
```

## 3. Pool timeout, WARN, recovery

Artifact `logs-frontend-wall-clock-time-1` (id 13677775841, created 2026-11-09T13:13:06Z, the only attempt), downloaded
with `gh run download 42618450792 --repo canton-network/splice -n logs-frontend-wall-clock-time-1 -D log/11290/logs-frontend-wall-clock-time-1`.

```
$ zcat log/11290/logs-frontend-wall-clock-time-1/canton_before_shutdown.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-09T12:55:10" && t<="2026-11-09T12:55:25"' | grep -a 'globalSequencerSv1' | grep -a -E 'DB_CONNECTION_LOST\(13,239937c6\)|Sequencer is unhealthy|.db-storage. is now in state Ok|.sequencer. is now in state SequencerHealthStatus\(active = true' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//; s/1220[0-9a-f]{60}/../g' | cut -c1-250 | awk '!s[$0]++'
2026-11-09T12:55:13.753Z DB_CONNECTION_LOST(13,239937c6): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__12209dee4ff0_____37-0-44 - Connection is not available, request timed out after 5001ms (to
2026-11-09T12:55:17.025Z Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database
2026-11-09T12:55:18.756Z 'db-storage' is now in state Ok(). Previous state was Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-globalSequencerSv1-global-domain__12209dee4ff0_____37-0-44
2026-11-09T12:55:22.308Z 'sequencer' is now in state SequencerHealthStatus(active = true). Previous state was SequencerHealthStatus(active = false, details = BFT orderer can't connect to database).
2026-11-09T12:55:22.309Z 'sequencer' is now in state SequencerHealthStatus(active = true). Previous state was SequencerHealthStatus(active = false, details = BFT orderer can't connect to database).
```
```
$ zcat log/11290/logs-frontend-wall-clock-time-1/canton_before_shutdown.clog.gz | grep -a -F '12:55:13.753Z' | grep -a -o 'total=[^)]*)'
total=8, active=8, idle=0, waiting=0)
total=8, active=8, idle=0, waiting=0)
```

## 4. Ignore patterns at the run's sha and on main

Only the DB-sequencer wording is ignored, at the run's sha and on origin/main (885cd84668, 2026-11-09T19:55:07Z); no
ignore file mentions the BFT wording, and the ignore file has no commits since the run's sha.

```
$ git show 38a37d9680:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'; git fetch -q origin main; git rev-parse --short origin/main; git log -1 --format='%cI %s' origin/main; git show origin/main:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'; git log --oneline 38a37d9680..origin/main -- project/ignore-patterns/canton_log.ignore.txt
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
885cd84668
2026-11-09T19:55:07Z Upgrade Canton to 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (#8381)
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
```
```
$ git grep -n "BFT orderer can" origin/main -- project/ignore-patterns/ ; echo "exit=$?"
exit=1
```

## 5. Parent and hits in the last week

```
$ for n in 11211 11227 11310 11290; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"#\(.number) \(.state) \(.state_reason // "-") \(.created_at) " + ((.body // "") | capture("Branch: \\[(?<b>[^\\]]*)\\]").b // "?") + " " + ((.body // "") | capture("Job: (?<j>[^\\n]*)").j // "?")'; done
#11211 open - 2026-11-05T09:11:56Z refs/heads/release-line-0.9.4 wall-clock-time (0) in scala_test
#11227 closed duplicate 2026-11-05T12:38:08Z refs/heads/release-line-0.9.4 wall-clock-time (5) in scala_test
#11310 closed duplicate 2026-11-09T16:22:08Z refs/heads/main frontend-wall-clock-time (2) in scala_test
#11290 open - 2026-11-09T13:13:18Z refs/heads/main frontend-wall-clock-time (1) in scala_test
```
```
$ gh api repos/DACH-NY/cn-test-failures/issues/11211 --jq '"#\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) \(.title)"'
#11211 open assignees= checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
```

Searches (open and closed, created since 2026-11-02), per hit:

```
$ for q in '"BFT orderer"' '"Sequencer is unhealthy"' '"connect to database"' '"8 of 8"' '"DB pool"' '"globalSequencerSv1"'; do echo "== $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q created:>=2026-11-02" --jq '.items[] | "#\(.number) \(.state) \(.created_at[:10]) \(.title)"'; done
== "BFT orderer"
#11211 open 2026-11-05 checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
#11227 closed 2026-11-05 GHA Run 42020755850 : job wall-clock-time (5) in scala_test Failed :fire:
== "Sequencer is unhealthy"
#11211 open 2026-11-05 checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
#11227 closed 2026-11-05 GHA Run 42020755850 : job wall-clock-time (5) in scala_test Failed :fire:
== "connect to database"
#11211 open 2026-11-05 checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
#11141 open 2026-11-02 Shard start: app DB pools time out on the first Postgres connection (5 s) while the runner is slow, createDatabase retry WARN
#11227 closed 2026-11-05 GHA Run 42020755850 : job wall-clock-time (5) in scala_test Failed :fire:
== "8 of 8"
#11310 closed 2026-11-09 GHA Run 42646231899 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
== "DB pool"
#11310 closed 2026-11-09 GHA Run 42646231899 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
#11211 open 2026-11-05 checkErrors: BFT orderer wording of the ignored Sequencer is unhealthy WARN
== "globalSequencerSv1"
#11192 open 2026-11-04 GHA Run 41896689021 : job frontend-wall-clock-time (1) in scala_test Failed :fire:
#11160 closed 2026-11-03 Reference sequencer driver: SERIALIZABLE insert block contention with uncapped retry backoff stalls global ordering
#11165 closed 2026-11-03 SequencerPruningIntegrationTest disables the shared alice, bob and splitwell participants at the global sequencer; all later suites fail to start
#11310 closed 2026-11-09 GHA Run 42646231899 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
#11191 closed 2026-11-04 GHA Run 41881231793 : job resource-intensive (0) in scala_test Failed :fire:
#11189 closed 2026-11-04 GHA Run 41847691151 : job resource-intensive (0) in scala_test Failed :fire:
```

- 11211: same (parent; globalSequencerSv2 pool 8/8, BFT wording, release-line-0.9.4).
- 11227: same (closed as dup; comment `Same unignored WARN: Sequencer is unhealthy, so disconnecting all members. BFT orderer can not connect to database.`).
- 11310: same (closed as dup today; comment names the 8 of 8 pool timeout and the BFT wording). Not returned by the "BFT orderer" search (its comment says "BFT wording").
- 11141: not the same (app DB pools at shard start, createDatabase retry WARN, family AG).
- 11192: not the same (SplitwellFrontendIntegrationTest settle-payment timeout, per its comment).
- 11160, 11165, 11189, 11191: not the same (reference sequencer insert-block contention, family L; SequencerPruning test isolation).

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11227/comments --jq '.[] | .created_at[:16] + " " + .user.login + ": " + (.body|gsub("\n";" ")|.[:300])'
2026-11-05T20:49 rautenrieth-da: Same unignored WARN: Sequencer is unhealthy, so disconnecting all members. BFT orderer can not connect to database.
$ gh api repos/DACH-NY/cn-test-failures/issues/11310/comments --jq '.[] | .created_at[:16] + " " + .user.login + ": " + (.body|gsub("\n";" ")|.[:300])'
2026-11-09T20:00 stephencompall-DA: Same WARN in its BFT wording on globalSequencerSv1 at 15:57:39.530, 5 s after its DB pool timed out with 8 of 8 connections active; healthy again at 15:57:45.717.
$ gh api repos/DACH-NY/cn-test-failures/issues/11192/comments --jq '.[] | .user.login + ": " + (.body|gsub("\n";" ")|.[:250])'
rautenrieth-da: Accepting the payment took 21.5 s, longer than the 20 s the test waits for Splitwell to show the settled balances (SplitwellFrontendIntegrationTest.scala:131). The test failed at 19:42:04 with bob still on the wallet page showing "Processing payment.
```

Hits since 2026-11-05: 4 (11211, 11227 on release-line-0.9.4; 11310, 11290 on main), two of them today.

## Verdict

Duplicate of 11211: same WARN text, same trigger (globalSequencerSv1 DB pool timeout with all 8 connections active
at 12:55:13.753) and recovery within seconds (db-storage Ok 12:55:18.756, sequencer active 12:55:22.308). Not a test
failure; the fix proposed on 11211 (extend `Sequencer is unhealthy, so disconnecting all members. Can't connect to
database` at line 167 of `project/ignore-patterns/canton_log.ignore.txt` to the BFT wording) is not on origin/main
885cd84668. Not verified: what held the 8 connections (the pool exhaustion itself is the open question behind both
wordings; it was ignored for the DB-sequencer wording since splice#7643).
