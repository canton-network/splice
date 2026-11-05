# 11227 - wall-clock-time (5) on release-line-0.9.4, checkErrors: BFT orderer wording of the Sequencer is unhealthy WARN (dup of 11211)

All 20 tests passed; checkErrors flagged one WARN of sv1StandaloneSequencer during a 6 s DB pool outage. Same
signature as 11211 (ignore-pattern gap for the BFT orderer wording), second hit, both on release-line-0.9.4 runs of
the backport bursts on 2026-11-05.

- Run 42020755850, release-line-0.9.4 196579f, job 127223174192 `wall-clock-time (5)`, attempt 1.
- Artifact logs-wall-clock-time-5 (13413869844), unpacked to `log/11227/art/`.
- Component: test config (`project/ignore-patterns/canton_log.ignore.txt`).

## 1. Job summary

```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/run-42020755850/127223174192.log | grep -aB4 -A3 'lines with problems'
```
```
[info] Tests: succeeded 20, failed 0, canceled 0, ignored 4, pending 0
Found problems in log/canton-standalone-sv123-non-sv1-svs_before_shutdown.clog:
***"@timestamp":"2026-11-05T12:16:40.060Z","message":"Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database","logger_name":"c.d.c.s.s.SequencerRuntime:sequencer=sv1StandaloneSequencer/psid=global-domain::12205d7a98ab::36-0",...,"level":"WARN",...***
Total: 1 lines with problems.
```

## 2. The outage (sv1StandaloneSequencer, non-DEBUG lines)

```
zcat log/11227/art/canton-standalone-sv123-non-sv1-svs_before_shutdown.clog.gz | grep -a sv1StandaloneSequencer | jq -r 'select(12:16:38..12:16:46, level != DEBUG)'
```
```
12:16:38.723 INFO slick-sv1StandaloneSequencer-global-domain__12205d7a98ab_____36-0-20 - Failed to validate connection org.postgresql.jdbc.PgConnection@d990df1 (This connection has been closed.). Possibly consider using a shorter maxLifetime value.
12:16:39.380 WARN DB_CONNECTION_LOST(13,6032e39b): Database health check failed to establish a valid connection: slick-sv1StandaloneSequencer-global-domain__12205d7a98ab_____36-0-20 - Connection is not available, request timed out after 5006ms (total=5, active=5, idle=0, waiting=0)
12:16:40.059 INFO 'sequencer' is now in state SequencerHealthStatus(active = false, details = BFT orderer can't connect to database). Previous state was SequencerHealthStatus(active = true).
12:16:40.060 WARN Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database
12:16:45.505 INFO 'db-storage' is now in state Ok(). Previous state was Failed(...
12:16:45.511 INFO pending-writes-update succeed successfully but slow after 18858 milliseconds
```
The DB_CONNECTION_LOST WARN is ignored (line 166/167); only the follow-up WARN is flagged. Storage recovered after
6 s; the shard then passed. Suite: SvOnboardingViaNonFoundingSvIntegrationTest (12:12:35-12:21:16). Unlike 11211
(pool timeout only), Postgres had closed pool connections first (`This connection has been closed.`); the flagged
WARN is the same either way.

## 3. Ignore patterns (live, via the contents API)

```
gh api "repos/canton-network/splice/contents/project/ignore-patterns/canton_log.ignore.txt?ref=<b>" --jq .content | base64 -d | grep -nE "disconnecting all members|BFT orderer"
```
```
== main
167:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
== release-line-0.9.4
168:Sequencer is unhealthy, so disconnecting all members. Can't connect to database
```
No change since 11211 was triaged (last commit on the file: #7958, 2026-10-26).

## Verdict

Duplicate of 11211 (Triaged, open, no assignee): second hit within 4 h, both release-line-0.9.4 during backport
bursts. Fix unchanged: extend that ignore line to `(Can't|BFT orderer can't) connect to database` on main, and
backport to the release lines that run the BFT sequencer, since both hits are on 0.9.4.
