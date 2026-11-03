# Group checkerrors (round 2026-11-03T131520Z)

Refs 11141, 11147, 11155: tests passed, checkErrors failed; three unrelated causes. Logs in `log/<ref>/job.log` and the
artifacts under `log/<ref>/logs-*/` (sandbox only). Formatter `log/tmp/fmt.py` (source in the 11147 packet).

## Verdicts

| Ref | Verdict | Parent / family | Packet |
|---|---|---|---|
| 11155 | duplicate: splitwellMediator `Locked connection was lost` at Canton startup, 3 read-only probes collide with the lock check, rebuilt in 99 ms | 10438 (G, as 10302) | `evidence/11155-splitwell-mediator-locked-connection-lost.md` |
| 11147 | new: sim-clock 1 h jumps in ScanNewNetworkBackfillingTimeBasedIntegrationTest drop 47 sv2 submissions (LOCAL_VERDICT_TIMEOUT) within one jump; the medium-priority circuit breaker (test threshold 20) trips; test fix proposed | new family AF (provisional), related 10475 | `evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md` |
| 11141 | new: shard start, 7 of 8 app pools of SvFrontendIntegrationTest wait > 5 s (connectionTimeout 5000) for their first Postgres connection, retry succeeds after 300 ms; Postgres slow for Canton too (9 LockCheckRejected within 3 ms at 14:39:30.78); cause of the Postgres stall not in the artifacts | new family AG (provisional), earlier 10739 | `evidence/11141-postgres-first-connection-timeout-at-shard-start.md` |

Open: the state of 10438 / 10302 (older than the export); the Postgres-side cause for 11141.

## INDEX.tsv rows

```
11141	2026-11-03	41461163244	main 7052977e68 (#8178)	125335853576 `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`	3.7.1	Shard start (first env SvFrontendIntegrationTest): 7 of 8 app pools time out on the first Postgres connection at 14:39:33.097 (pools started 14:39:26.819, connectionTimeout 5000), createDatabase retry connects 300 ms later; Canton log thins at 14:39:28-29, 9 LockCheckRejected at 14:39:30.78; Postgres stall cause not in artifacts	new (AG), earlier 10739		`11141-postgres-first-connection-timeout-at-shard-start.md`; test config fix proposed (connectionTimeout 15000), not written	evidence/11141-postgres-first-connection-timeout-at-shard-start.md
11147	2026-11-03	41510362200	main 136e2e3160 (#8193)	125500702596 `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)`	3.7.1	ScanNewNetworkBackfillingTimeBasedIntegrationTest hourly sim jumps: after the jump at 19:54:35.970 sv2 logs 47 failed submissions (LOCAL_VERDICT_TIMEOUT), medium-priority circuit breaker trips at 19:54:38.531 (test max-failures 20, failure count only reset 15 min sim time after the last failure), re-closes 19:55:09.390	new (AF), related 10475		`11147-sim-jump-timeouts-trip-circuit-breaker.md`; test fix proposed (CircuitBreakersConfig.never for the SV apps), not written	evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md
11155	2026-11-03	41610142743	main 8fd9921238 (#8053)	125830351196 `ci / scala_test_wall_clock_time / wall-clock-time (3)`	3.7.1	splitwellMediator pool-2 `Locked connection was lost` 09:26:50.058 at Canton startup (first suite 09:28:35), 3 read-only probes 1-9 ms after a lock check fail with NoConnectionAvailable, rebuilt 09:26:50.157	10438 (G)		`11155-splitwell-mediator-locked-connection-lost.md`; close as dup	evidence/11155-splitwell-mediator-locked-connection-lost.md
```

## known-families.md text

Index rows (after AB):
```
| AF | sim-clock jump timeouts trip the SV medium-priority circuit breaker | 11147 (related 10475) | open, test fix proposed |
| AG | first Postgres connection > 5 s at shard start, createDatabase retry WARN | 10739, 11141 | open, test config fix proposed |
```

Occurrence note for family G (append to the `10438 / 10302` bullet, or to the dated notes):
```
- G (10438 / 10302): third hit 11155 (main 8fd9921238, wall-clock-time (3), Canton 3.7.1): splitwellMediator pool-2 at
  Canton startup 09:26:50.058, probes at 38.641, 44.040, 50.047 each 1-9 ms after a lock check, rebuilt in 99 ms.
```

New sections:
```
## AF. Sim-clock jump timeouts trip the SV medium-priority circuit breaker
- Signature (log check only, canton_network_test.clog): WARN `Circuit breaker medium-priority-connection tripped after
  20 failures. Attaching last failure` from `SpliceCircuitBreaker:<simtime suite>/.../SV=<sv>`, stack
  `ABORTED: LOCAL_VERDICT_TIMEOUT(2,0)`.
- Confirm: `grep -a -E 'advancing time by|(SubmitAndWait.*failed with|Resetting circuit|tripped).*SV=<sv>'`: a burst of
  LOCAL_VERDICT_TIMEOUT failures within 3 s after one `advancing time by PT1H`, with no `Resetting circuit breaker`
  in between, then the WARN; `moving to closed state` about 30 s later.
- Mechanism: each sim-clock step drops the in-flight SV automation commands (accepted by design at a jump; same
  jumps as 10475). LOCAL_VERDICT_TIMEOUT (category 2) is not ignored by SpliceCircuitBreaker, and the failure count
  is only reset on a call made 15 min (sim time) after the last failure, i.e. after the next jump; test config
  max-failures is 20 (`include/svs/_sv.conf`).
- Parent 11147 (2026-11-02, main 136e2e3160, ScanNewNetworkBackfillingTimeBasedIntegrationTest, 47 sv2 failures in one
  jump). Fix in the test: `circuitBreakers = CircuitBreakersConfig.never` for the SV apps, as LsuIntegrationTest does
  for sv4. No ignore pattern.

## AG. First Postgres connection slower than 5 s at shard start
- Signature (log check only): WARN `Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying
  in 300ms: DatabaseCreationFailed(Failed to connect to database: ... PoolInitializationException: Failed to initialize
  pool: Connection attempt timed out.` from DbStorage of an app (canton_network_test.clog) or a Canton node
  (canton log), at the first environment / Canton startup of the shard.
- Confirm: `HikariDataSource ... - Starting...` at most about 6.3 s before the WARN, `Added connection` for the same pool
  within 300 ms after it; nothing else fails.
- Mechanism: pools start with connectionTimeout 5000 and initializationFailTimeout 1; Postgres is slow for all its
  clients for a few seconds (in 11141 also the Canton lock checks, 9 LockCheckRejected within 3 ms). The cause of the
  Postgres stall is not in the artifacts (no Postgres log, no host metrics).
- Hits: 10739 (2026-10-28, simtime (1), Canton globalSequencerSv2; closed not planned), 11141 (2026-11-02,
  frontend-wall-clock-time (2), 7 app pools). Fix in test config: `connectionTimeout = 15000` in
  `apps/app/src/test/resources/include/storage-postgres.conf` (and the Canton test storage config). No ignore pattern.
```

## Issue commands

```
direnv exec . ci-triage/scripts/close_duplicates.py 10438 11155 --comment 'Duplicate of #10438 (family G, same mechanism as #10302): tests passed; checkErrors flags one WARN Locked connection was lost, trying to rebuild on splitwellMediator pool-2 at 09:26:50.058, during Canton startup before the first suite. Three read-only probes of the same KeepAliveConnection fail with NoConnectionAvailable 1-9 ms after a DbLockPostgres lock check on the same node, and the connection is rebuilt 99 ms later. Canton race, no functional effect, no ignore pattern. Packet: ci-triage/evidence/11155-splitwell-mediator-locked-connection-lost.md on the triage branch.'
gh issue comment 11147 -R DACH-NY/cn-test-failures --body 'Tests passed, log check only. ScanNewNetworkBackfillingTimeBasedIntegrationTest (copy the first segments of the founder to an SV that joins inside the first segment) advances the sim clock in 42 steps of 1 h. Each step drops the in-flight SV automation commands with LOCAL_VERDICT_TIMEOUT, which is accepted by design at a jump (same jumps as #10475). SpliceCircuitBreaker counts these timeouts (category 2 is not ignored) and only resets the count on a call made 15 min of sim time after the last failure, so after the next jump. After the jump at 19:54:35.970 sv2 logged 47 failed submissions, and its medium-priority breaker (test max-failures 20) tripped at 19:54:38.531; it closed again at 19:55:09.390 and the test passed. Suggested fix in the test: set circuitBreakers = CircuitBreakersConfig.never for the SV apps, as LsuIntegrationTest does for sv4. No ignore pattern. Packet: ci-triage/evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md on the triage branch.'
gh issue edit 11147 -R DACH-NY/cn-test-failures --title 'ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock jump timeouts trip the sv2 medium-priority circuit breaker'
gh issue comment 11141 -R DACH-NY/cn-test-failures --body 'Tests passed, log check only. At the start of the shard (first environment, SvFrontendIntegrationTest) all 8 app DB pools start at 14:39:26.819 with connectionTimeout 5000. sv2 gets its first Postgres connection only after 5.8 s, the other 7 (sv1, sv1Scan, sv1Validator, aliceValidator, bobValidator, splitwellValidator, providerSplitwellBackend) time out at 14:39:33.097 with DbStorage.createDatabase failed, retrying in 300ms (Connection attempt timed out), and the retry connects within 13 ms. Postgres was slow for the Canton process too: its log thins out at 14:39:28-29 and 9 lock checks come back LockCheckRejected within 3 ms at 14:39:30.78. The reason for the Postgres stall is not in the artifacts (no Postgres log, no host metrics). Second hit after #10739 (Canton startup, 2026-10-28). Suggested fix: connectionTimeout = 15000 in apps/app/src/test/resources/include/storage-postgres.conf (and the Canton test storage config), so a slow first connection is not a failure. No ignore pattern. Packet: ci-triage/evidence/11141-postgres-first-connection-timeout-at-shard-start.md on the triage branch.'
gh issue edit 11141 -R DACH-NY/cn-test-failures --title 'Shard start: app DB pools time out on the first Postgres connection (5 s), createDatabase retry WARN'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11141 11147
```
Alternative for 11141 if no config change is wanted: `Infrequent / no repro` instead of `Triaged`.
