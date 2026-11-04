# Round 2026-11-03T131520Z

Data source: host exports `inbox.jsonl` (2026-11-03 13:14, 35 items) and `issues-since-2026-10-20.jsonl`
(13:11, 721 issues). Access: splice via `gh api`; no `ghx`, no DACH-NY token. Job logs of the splice runs in
`log/<ref>/job.log` (sandbox only).

The commands of round 2026-11-02T153358Z-singles did not run: 10891, 11138, 11139 still open, 11140 has no
comment. They are handed out again in this round.

## Classification

| Ref | Class | Note |
|---|---|---|
| 10495, 10496, 11104, 11105, 11128 | done | previous verdicts (STATE.md); propose Triaged |
| 10891, 11138, 11139, 11140 | done | round 2026-11-02T153358Z-singles, commands not run |
| 10728, 10893, 10917, 10918, 10919 | waiting | cause open on purpose (STATE.md) |
| 10479, 10680, 11106, 11117, 11137 | needs-log | as in STATE.md |
| 11142, 11151 | needs-log | CircleCI deploy_sv_reonboard (pipelines 87882 job 528092, 87945 job 528370), like 11106, 11137 |
| 11145 | needs-log | cni GHA 41497863226 backup_status_cluster (Devnet Backup Status), like 11117 |
| 11146 | out of scope | blackduck vulnerability (pypi:filelock), filed with Status Inbox |
| 11152, 11153, 11154 | new | group cimain: run 41610142697 attempts 1-3, trigger_cimain / trigger_cluster_test, step Trigger cluster test |
| 11158 | new | group scanmon: ScanPartyMonitorScriptIntegrationTest, suspected Z (11128) |
| 11143 | new | group walletmetrics: WalletMetricsTest, suspected dup of 10732 |
| 11144 | new | group traffic: TrafficBasedRewardsDryRunTimeBasedIntegrationTest CIP-104 |
| 11160 | new | group tscli: release-line-0.10.0 TokenStandardCliTestDataTimeBasedIntegrationTest expire amulet allocations |
| 11161 | new | group splitwell: SplitwellFrontendIntegrationTest settle debts with a single party (main) |
| 11141, 11147, 11155 | new | group checkerrors: tests pass, checkErrors (createDatabase connect; 1 line; DbLockedConnection lost) |
| 11163 | new | group simtime1: simtime (1) Run Tests failed without a test summary |

## Verdicts

| Ref | Verdict | Parent | Packet (evidence/) |
|---|---|---|---|
| 11152 | infra one-off (J): cluster test invoker HTTP 500 09:20-09:40, attempt 4 passed | - | `11152-11153-11154-deploy-cimain-trigger-invoker-500.md` |
| 11153, 11154 | dup (attempts 2, 3; mapping from the issue URL each attempt logs) | 11152 | same |
| 11158 | dup, family Z, fifth hit | 11128 | `11158-scan-party-monitor-events-cap-dup-11128.md` |
| 11143 | dup; mechanism found (success + noop series), new family AC | 10732 | `11143-wallet-metrics-trigger-completed-noop-series.md` |
| 11144 | new family AD: round totals read before the parallel backlog computation committed them; test fix proposed | - | `11144-tbar-round-totals-out-of-order.md` |
| 11160 | family L occurrence: global sequencer 18 s stall, onboardWalletUser gives up; validator fix needs owner | - | `11160-onboarding-retry-budget-reference-sequencer-40001-stall.md` |
| 11161 | new family AE: wallet login helper misses the logged-in state on a slow page load; test fix proposed; not #8040 | - | `11161-splitwell-wallet-login-already-logged-in-race.md` |
| 11163 | dup, family U third hit (sv2 ImportPartyAcs topology offset), SV app exit loses the shard (H) | 10907 | `11163-sv2-join-import-acs-topology-offset.md` |
| 11141 | new family AG: first Postgres connection > 5 s at shard start (second hit after 10739); config fix proposed | - | `11141-postgres-first-connection-timeout-at-shard-start.md` |
| 11147 | new family AF: sim-clock jump timeouts trip the sv2 circuit breaker; test fix proposed | - | `11147-sim-jump-timeouts-trip-circuit-breaker.md` |
| 11155 | dup, family G (Locked connection was lost at Canton startup) | 10438 | `11155-splitwell-mediator-locked-connection-lost.md` |

Family letters: the agents proposed AE, AF, AH, AI, AJ; renumbered at merge to AC, AD, AE, AF, AG (WalletMetrics,
TBAR round totals, login helper, circuit breaker, Postgres first connection).

## Needs an owner

- Z test fix (tap after createTransferOffer), stephencompall-DA; 5 hits in 2 days.
- U fix in JoiningNodeDsoPartyHosting (retry the import after reconnect); 3 hits; owner unknown.
- 11160: ValidatorUtil.onboard waits for the party on the AmuletRules synchronizer (validator app).
- Test fixes described in the packets, no branch yet: AC (WalletMetricsTest.scala:133-136 outcome label), AD
  (assertRewardCalcs waits for all rounds), AE (FrontendLoginUtil.loginOnceConfirmedToBeAtUrl), AF
  (CircuitBreakersConfig.never in ScanNewNetworkBackfilling), AG (connectionTimeout 15000 in storage-postgres.conf).

## Hand-over (batch, superseded)

Printed on 2026-11-03 as one block; the user asked to go through the items one by one instead, so none of
these ran. Kept for reference; each item in the step-mode queue below hands out its own commands.

Live state: no `ghx` or token for cn-test-failures, so only the export (13:11 and 13:14) was checked; all targets
were open and in Inbox then. Commands to run from the `~/splice` root (triage branch checked out there, direnv
loaded). Status changes for duplicates are not needed: closing sets Closed.

```
gh issue comment 11128 -R DACH-NY/cn-test-failures --body 'Recurs often: 5 failures from 2026-11-02 to 2026-11-03 (#11128 05:00 PG14 nightly, #11136 07:43, #11138 10:23, #11139 11:36, #11158 2026-11-03 11:13, post-merge runs on main), same cap mechanism. The hidden verdict comes 1 microsecond (#11138, #11139) to 330 ms (#11158) after the last DSO-visible update. No fix on main yet (5c53b74294). Test fix still needed: cause a DSO-visible transaction (e.g. a tap) after createTransferOffer.'
gh issue comment 11140 -R DACH-NY/cn-test-failures --body 'Tests passed, log check only. BftScanConnectionIntegrationTest init fast enough even if there are unavailable scans stops sv2Scan (:5112) on purpose and suppresses the Failed to connect to scan of Digital-Asset-Eng-2 WARN only around aliceValidator startSync and onboardUser. aliceValidator refreshes its scan list every 60 s (scansRefreshInterval in the test config): first refresh 12:14:04.066 (suppressed), test body done 12:14:50, teardown plugins (token-standard CLI sanity check) until 12:15:04, and the next refresh at 12:15:04.067 hit the still-stopped sv2Scan 13 ms before aliceValidator was stopped. Timing race, first hit. Test fix: keep the suppression until aliceValidator is stopped (or restart sv2Scan inside it); no ignore pattern. Packet: ci-triage/evidence/11140-bft-scan-refresh-warn-after-suppression.md on the triage branch.'
gh issue comment 11152 -R DACH-NY/cn-test-failures --body 'Infra one-off. Attempts 1-3 of Deploy CIMain run 41610142697 (this issue = attempt 1; 11153 = attempt 2; 11154 = attempt 3, per the failure notification step of each attempt) failed in Trigger cluster test: the cluster test invoker (CLUSTER_TEST_INVOKER_URL) answered HTTP 500 with the default Werkzeug page to all 4 curl tries, from 09:20:12 to 09:40:29 UTC. Attempt 4 at 09:44:12 triggered canton-network-internal run 41613435215; all later Deploy CIMain runs passed. curl already retries 500 within 60 s; the cause is in the invoker (GCP da-cn-ci logs for that window), not checked. Packet: ci-triage/evidence/11152-11153-11154-deploy-cimain-trigger-invoker-500.md on the triage branch.'
gh issue comment 10732 -R DACH-NY/cn-test-failures --body 'Second hit: #11143 (2026-11-02, main 233250e5a9). Mechanism: trigger.completed has one series per outcome label (TaskbasedTrigger.scala). The per-party DomainIngestionService is a quiet periodic trigger (30 s, jitter 0.2): its first poll marks outcome=success, the next poll finds nothing and silently marks outcome=noop. The test checks 32 s (this issue) and 35 s (#11143) after the first poll, so metrics.get finds 2 series and the eventually cannot recover. Test fix: add outcome = success to the label map of the trigger.completed lookup in WalletMetricsTest.scala lines 133-136. Packet: ci-triage/evidence/11143-wallet-metrics-trigger-completed-noop-series.md on the triage branch.'
gh issue comment 11144 -R DACH-NY/cn-test-failures --body 'Test race, first hit. The test pauses sv1Scan verdict ingestion for rounds 3 to 10; when it resumes, the RewardComputationTrigger of sv1Scan (a parallel polling trigger) starts rounds 3 to 10 at 17:57:27.407 and finishes them out of order: round 10 committed at .433, rounds 4 and 5 only at .510. assertRewardCalcs waits (eventually) only for round 10 to be Ok, then reads rounds 0 to 10 once; it read round 4 at .495 and got Undetermined, which scan documents as retry. Not a product bug. Test fix: in assertRewardCalcs (TrafficBasedRewardsTimeBasedIntegrationTest.scala lines 456 to 462) wait inside the eventually for the totals and root hash of every round 0 to 10, not only round 10. Unchanged on main 5c53b74294. Packet: ci-triage/evidence/11144-tbar-round-totals-out-of-order.md on the triage branch.'
gh issue comment 11160 -R DACH-NY/cn-test-failures --body 'Family L (reference sequencer insert block SQLSTATE 40001 retry storm), new symptom. TokenStandardCliTestDataTimeBasedIntegrationTest fails in its first step, onboardWalletUser for alice on aliceValidator: HTTP 400 INVALID_PRESCRIBED_SYNCHRONIZER_ID (informees on splitwell, not on global). The new party became effective on splitwell at 12:29:15.85 but on the global synchronizer only at 12:29:27.6: globalSequencerSv1 did not create a store batch from 12:29:09.478 to 12:29:27.680 (18.2 s) while insert block failed with SQLSTATE 40001 (backoff up to 6.373 s), right after a sim-time advance. installWalletForUser prescribes the global synchronizer and gave up after 12 retries at 12:29:23.5. Not a missing backport (#8192 only changes a Grafana dashboard; no main-only change to the onboarding code or the test). Possible fix in the validator app: in ValidatorUtil.onboard, wait for the PartyToParticipant of the new party on the AmuletRules synchronizer before installWalletForUser (createPartyAndUser only waits on the Authorized store). Packet: ci-triage/evidence/11160-onboarding-retry-budget-reference-sequencer-40001-stall.md on the triage branch.'
gh issue comment 11161 -R DACH-NY/cn-test-failures --body 'SplitwellFrontendIntegrationTest settle debts with a single party failed in the wallet login helper, not in splitwell. Bob was still logged in to the wallet (localhost:3001) from test case 0. After the splitwell transfer redirected to /confirm-payment, the wallet page reached DOMContentLoaded only at 12:26:47.022, 3.8 s after the click. FrontendLoginUtil.loginOnceConfirmedToBeAtUrl checks for logout-button once per attempt (12:26:43.883 and 12:26:47.030, both before React rendered), so it never logged out and waited for user-id-field, which a logged-in wallet never shows; the 5 s budget ended at 12:26:50 with the failure screenshot showing the logged-in wallet. Not caused by #8040 (react-router 8): the same shard passed on the next two main runs. Test fix: before the 5 s retry loop, wait until either logout-button or user-id-field is present, then decide. Packet: ci-triage/evidence/11161-splitwell-wallet-login-already-logged-in-race.md on the triage branch.'
gh issue comment 10907 -R DACH-NY/cn-test-failures --body 'Third hit: #11163 (2026-11-03, main 5c53b74294, simtime (1)), this time sv2 in SvTimeBasedRewardCouponIntegrationTest. The last indexed event of sv2Participant before the disconnect was a TopologyTransactionEffective; the same sv2 join passed 5 minutes earlier in another suite, so it is a timing race. Suggested fix: in JoiningNodeDsoPartyHosting, on this error reconnect, wait until the ledger end moves past the topology offset, disconnect and retry the import (ImportPartyAcsRequest has no force flag for this check). The SV app exit also loses the rest of the shard (family H).'
gh issue comment 11147 -R DACH-NY/cn-test-failures --body 'Tests passed, log check only. ScanNewNetworkBackfillingTimeBasedIntegrationTest (copy the first segments of the founder to an SV that joins inside the first segment) advances the sim clock in 42 steps of 1 h. Each step drops the in-flight SV automation commands with LOCAL_VERDICT_TIMEOUT, which is accepted by design at a jump (same jumps as #10475). SpliceCircuitBreaker counts these timeouts (category 2 is not ignored) and only resets the count on a call made 15 min of sim time after the last failure, so after the next jump. After the jump at 19:54:35.970 sv2 logged 47 failed submissions, and its medium-priority breaker (test max-failures 20) tripped at 19:54:38.531; it closed again at 19:55:09.390 and the test passed. Suggested fix in the test: set circuitBreakers = CircuitBreakersConfig.never for the SV apps, as LsuIntegrationTest does for sv4. No ignore pattern. Packet: ci-triage/evidence/11147-sim-jump-timeouts-trip-circuit-breaker.md on the triage branch.'
gh issue comment 11141 -R DACH-NY/cn-test-failures --body 'Tests passed, log check only. At the start of the shard (first environment, SvFrontendIntegrationTest) all 8 app DB pools start at 14:39:26.819 with connectionTimeout 5000. sv2 gets its first Postgres connection only after 5.8 s, the other 7 (sv1, sv1Scan, sv1Validator, aliceValidator, bobValidator, splitwellValidator, providerSplitwellBackend) time out at 14:39:33.097 with DbStorage.createDatabase failed, retrying in 300ms (Connection attempt timed out), and the retry connects within 13 ms. Postgres was slow for the Canton process too: its log thins out at 14:39:28-29 and 9 lock checks come back LockCheckRejected within 3 ms at 14:39:30.78. The reason for the Postgres stall is not in the artifacts (no Postgres log, no host metrics). Second hit after #10739 (Canton startup, 2026-10-28). Suggested fix: connectionTimeout = 15000 in apps/app/src/test/resources/include/storage-postgres.conf (and the Canton test storage config), so a slow first connection is not a failure. No ignore pattern. Packet: ci-triage/evidence/11141-postgres-first-connection-timeout-at-shard-start.md on the triage branch.'
ci-triage/scripts/close_duplicates.py 10921 10891 --comment 'Duplicate of #10921 (pekko-connectors-google-cloud-storage 1.3.0 reads the GCS service-account scopes at the top level). The observed run is on PR branch 9ff2637f41 (PR 8083), which contains #8017 but not the fix #8148 (merged to main 2026-10-30): the PeriodicTopologySnapshotTrigger of sv1 logs ConfigException Missing scopes at 01:30:42 UTC and never takes the snapshot the test waits for. Rebasing the branch onto main fixes it. Packet: ci-triage/evidence/10891-periodic-topology-snapshot-missing-scopes.md on the triage branch.'
ci-triage/scripts/close_duplicates.py 11128 11138 11139 11158 --comment 'Duplicate of #11128 (family Z): same assertion at ScanPartyMonitorScriptIntegrationTest.scala:273; the cursor of the script stays at the last DSO-visible update while scan already has the private verdict (1 microsecond to 330 ms later), and the update history only advances with records not visible to the DSO party. Packets: ci-triage/evidence/11138-11139-scan-party-monitor-events-cap-dup-11128.md and 11158-scan-party-monitor-events-cap-dup-11128.md on the triage branch.'
ci-triage/scripts/close_duplicates.py 11152 11153 11154 --comment 'Duplicate of #11152: same Deploy CIMain run 41610142697, a later attempt; the cluster test invoker answered HTTP 500 from 09:20 to 09:40 UTC, attempt 4 passed.'
ci-triage/scripts/close_duplicates.py 10732 11143 --comment 'Duplicate of #10732: same Found 2 matching metrics for trigger.completed of DomainIngestionService in WalletMetricsTest (run 41481391794, wall-clock-time (3), main 233250e5a9). Packet: ci-triage/evidence/11143-wallet-metrics-trigger-completed-noop-series.md on the triage branch.'
ci-triage/scripts/close_duplicates.py 10907 11163 --comment 'Duplicate of #10907 (family U). SvTimeBasedRewardCouponIntegrationTest environment start: sv2 joining the DSO disconnected its participant and called ImportPartyAcs, which sv2Participant refused with IMPORT_ACS_ERROR Cannot apply a repair command as the last event is a topology offset (13:11:32.295). The SV app then exited the sbt JVM, so the job shows no test summary and two suites of the shard never ran. Not related to #8202. Packet: ci-triage/evidence/11163-sv2-join-import-acs-topology-offset.md on the triage branch.'
ci-triage/scripts/close_duplicates.py 10438 11155 --comment 'Duplicate of #10438 (family G, same mechanism as #10302): tests passed; checkErrors flags one WARN Locked connection was lost, trying to rebuild on splitwellMediator pool-2 at 09:26:50.058, during Canton startup before the first suite. Three read-only probes of the same KeepAliveConnection fail with NoConnectionAvailable 1-9 ms after a DbLockPostgres lock check on the same node, and the connection is rebuilt 99 ms later. Canton race, no functional effect, no ignore pattern. Packet: ci-triage/evidence/11155-splitwell-mediator-locked-connection-lost.md on the triage branch.'
gh issue edit 11140 -R DACH-NY/cn-test-failures --title 'BftScanConnectionIntegrationTest: 60 s scan refresh WARNs about the deliberately stopped sv2Scan after the log suppression ended'
gh issue edit 11152 -R DACH-NY/cn-test-failures --title 'Deploy CIMain: cluster test invoker returned HTTP 500 for 20 min on 2026-11-03 (3 attempts), rerun passed'
gh issue edit 10732 -R DACH-NY/cn-test-failures --title 'WalletMetricsTest: trigger.completed lookup matches the success and the noop series of DomainIngestionService'
gh issue edit 11144 -R DACH-NY/cn-test-failures --title 'TrafficBasedRewardsDryRunTimeBasedIntegrationTest: round totals read before the parallel backlog reward computation committed them'
gh issue edit 11160 -R DACH-NY/cn-test-failures --title 'TokenStandardCliTestDataTimeBasedIntegrationTest: onboarding gives up with INVALID_PRESCRIBED_SYNCHRONIZER_ID while global ordering stalls on insert block 40001 (family L)'
gh issue edit 11161 -R DACH-NY/cn-test-failures --title 'SplitwellFrontendIntegrationTest: wallet login helper misses the already-logged-in state when the confirm-payment page loads slowly'
gh issue edit 11147 -R DACH-NY/cn-test-failures --title 'ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock jump timeouts trip the sv2 medium-priority circuit breaker'
gh issue edit 11141 -R DACH-NY/cn-test-failures --title 'Shard start: app DB pools time out on the first Postgres connection (5 s), createDatabase retry WARN'
ci-triage/scripts/set_status.py Inbox Triaged 10495 10496 11104 11105 11128 11140 11141 11144 11147 11160 11161
ci-triage/scripts/set_status.py Inbox 'Infrequent / no repro' 11152
```

Log requests (save under `~/claude-splice/ci-triage/export/<ref>/`):
- CircleCI deploy_sv_reonboard, output of the failing step: 11106 (pipeline 87744, job 527403), 11137 (87814,
  527732), 11142 (87882, 528092), 11151 (87945, 528370); reset_validator_runbook_devnet: 10680 (87309, 525338).
- canton-network-internal GHA job logs: 11145 (run 41497863226, backup_status_cluster), 11117 (run 41175569098,
  Devnet Backup Status), 10479 (run 38997942871, job 117294527070, Purge Unused Databases).

Not handed out: 11146 (blackduck vulnerability with Status Inbox; not part of this triage).

## Mode switch 2026-11-03: step mode

User feedback: the batch hand-over was too much at once; the user wants to understand the cause and resolution of
every issue and resolve each before the next. From here the round runs in step mode (skill ci-triage-inbox step 5).
Command convention for this user: run from the `~/splice` root with direnv loaded, so no `direnv exec .` prefix.

| # | Refs | Class | Signature | Family | State |
|---|---|---|---|---|---|
| 1 | 11152, 11153, 11154 | new | Deploy CIMain trigger: cluster test invoker HTTP 500, 3 attempts | J | resolved |
| 2 | 11128, 11138, 11139, 11158 | done + new | ScanPartyMonitorScript verdict held back by the /v0/events cap | Z | resolved |
| 3 | 11143 (parent 10732) | new | WalletMetricsTest trigger.completed matches success + noop series | AC | resolved |
| 4 | 11144 | new | TBAR round totals read before the parallel backlog committed | AD | resolved |
| 5 | 11160 | new | onboarding gives up during a global sequencer 40001 stall (0.10.0) | L | resolved |
| 6 | 11161 | new | wallet login helper misses the logged-in state on a slow load | AE | resolved |
| 7 | 11163 (parent 10907) | new | sv2 ImportPartyAcs refused, last event is a topology offset | U | resolved |
| 8 | 11141 | new | first Postgres connection > 5 s at shard start | AG | presented |
| 9 | 11147 | new | sim-clock jump timeouts trip the sv2 circuit breaker | AF | open |
| 10 | 11155 (parent 10438) | new | Locked connection was lost at Canton startup | G | resolved |
| 11 | 10891 (parent 10921) | done | PeriodicTopologySnapshot missing scopes on a PR branch without #8148 | V | resolved |
| 12 | 11140 | done | BftScanConnection refresh WARN after the suppression ended | AB | open |
| 13 | 10495 | done | offboarded sv4 mediator in-flight send rejected | G | open |
| 14 | 10496 | done | docs job npm ci hangs | J | open |
| 15 | 11104 | done | LsuIntegrationTest sv4 faucet trigger ERROR during catch-up | AA | open |
| 16 | 11105 | done | Canton 3.7.0 ACS updates stream abrupt termination | note 2026-11-02 | open |

Listed once, not presented: waiting 10728, 10893, 10917, 10918, 10919; needs-log 10479, 10680, 11106, 11117,
11137, 11142, 11145, 11151 (requests at the end of the queue); out of scope 11146.

Correction 2026-11-03 (item 1, applies to all later items): issue comments do not cite evidence packets; they inline
the decisive log lines or summarize them, for a senior engineer as reader. The batch commands above predate this.

Item 1 (11152, 11153, 11154): user looked at the invoker log via recipe 13 (new, gcp_logs.py): Cloud Function
invoke-circleci-workflow got 403 secretmanager.versions.access denied on secret github_token; user: a permission
problem that was fixed. Resolution changed from Infrequent / no repro to close as completed. Commands handed out
2026-11-03, not yet confirmed as run.

Item 1: commands ran (user, 2026-11-03). User: print every comment body in chat, also when posted from a file.

Item 2 (family Z): user asked for automatic fix branches when the cause is established and the fix is test-only
(skills updated, 5828036a17). Branch rautenrieth-da/fix-11128-scan-party-monitor-dso-visible-tx, commit
0c380008f0: aliceValidatorWalletClient.tap(1.0) after createTransferOffer in the act step. Not compiled: nix in the
worktree started downloading (newer pin on main), stopped; the user was told to rebuild the image.

Item 2: user opened PR 8211 (auto-merge). No new comment on 11128 (the comment of 2026-11-02 already has the cause);
only the duplicates are closed. 11128 keeps its Status; close it once 8211 is merged.
Item 2: duplicates closed (user, 2026-11-03).

Item 3 (11143, parent 10732): cause rechecked on main 8dacec5c10 (DomainIngestionService is quiet = true and marks
TaskNoop as outcome noop; metrics.get keeps series whose attributes contain the given ones). The comment of
2026-10-28 on 10732 already names the mechanism and the outcome-label fix, so no new comment. Fix branch
rautenrieth-da/fix-10732-wallet-metrics-outcome-label (b43adb1676). Command: close 11143 as dup.

Lessons: started lessons/2026-W45.md on user request (weekly rotation, max 10 min read); lessons from items 2 (Z) and 3 (AC).
Lessons rewritten generic on user request (no issues, PRs or test names, no blame). Sources: W45 lesson 1 from
item 2 (family Z, 11128), lesson 2 from item 3 (family AC, 10732).
User on lessons: W45 lesson 1 is the target style; lesson 2 of lower value, keep for now, prune at the end of the week if over the cap.

## Resume point (2026-11-03, before the sandbox rebuild)

- Item 3 (11143, parent 10732): waiting for the user: close 11143 as dup (command above), static checks and PR for
  branch rautenrieth-da/fix-10732-wallet-metrics-outcome-label (b43adb1676, not yet checked).
- PR 8211 (item 2) failed scalafmt in static_tests; the user fixes it manually. The local formatFix wanted:
  `"...followed by a DSO-visible tap", {` on one line (the block opens on the description line).
- Next: item 4 (11144, family AD), presented in step mode; its fix qualifies for an automatic branch (test-only),
  which must pass the static checks before it is shown.
- User conventions for this round (also in the skills): step mode; commands run from the ~/splice root with direnv
  loaded (no `direnv exec .`); exports via `--out ~/claude-splice/ci-triage/export/...`; every comment body printed
  in chat; no new comment where an existing root-cause comment holds; lessons generic (W45 lesson 1 is the style).

## Resumed 2026-11-03 after the sandbox rebuild

- Image at main 934f543eb4, no nix diff against the fix branches; access unchanged (splice via `gh api` only).
- Item 3: the user opened PR canton-network/splice#8215 from rautenrieth-da/fix-10732-wallet-metrics-outcome-label
  (b43adb1676); CI static_tests passed on it, so the local static check was stopped. PR 8211 static_tests also
  passed after the user's scalafmt fix. Still open: close 11143 as dup (asked the user whether it ran).

## Fresh export 2026-11-03 18:08 (36 items)

Exports `inbox.jsonl` (18:08) and `issues-since-2026-10-20.jsonl` (18:08, 732 issues, newest 11173).
- Left the inbox: 11152, 11153, 11154 (closed, item 1); 11138, 11139, 11158 (closed, item 2); 11128 (open, Status
  changed by the user, item 2).
- 11143 still open: the close of item 3 has not run yet.
- New, splice GHA on main, not yet classified (job logs not read): 11164 (run 41637827972, wall-clock-time (0),
  5c53b74294), 11165 (41637223316, wall-clock-time (6), 6a32b00e92), 11168 + 11169 (41661993096, wall-clock-time
  (4) and (2), d852f3c54c), 11172 + 11173 (41674340437, frontend-wall-clock-time (1) and wall-clock-time (3),
  8dacec5c10). Queued as items 17-20 by run, regrouped by signature once read; presented after item 10, before the
  done items 11-16.
- New, needs-log: 11170 (cni GHA run 41669902127, cluster_lock_reaper), 11171 (CircleCI pipeline 88016 job
  528537, deploy_sv_reonboard, like 11106, 11137, 11142, 11151).

| # | Refs | Class | Signature | Family | State |
|---|---|---|---|---|---|
| 17 | 11164 | new | wall-clock-time (0), main 5c53b74294 | ? | open |
| 18 | 11165 | new | wall-clock-time (6), main 6a32b00e92 | ? | open |
| 19 | 11168, 11169 | new | run 41661993096, wall-clock-time (4) and (2) | ? | open |
| 20 | 11172, 11173 | new | run 41674340437, frontend-wall-clock-time (1), wall-clock-time (3) | ? | open |

Item 3: 11143 closed as dup of 10732 (user, 2026-11-03), comment without packet reference, fix PR 8215. Resolved.

Item 4 (11144): fix branch rautenrieth-da/fix-11144-tbar-wait-all-rounds (bd47fecde5, off main 934f543eb4):
assertRewardCalcs waits in its eventually for totals and root hash of every round 0..10. Local checks: scalafmtOnly
on the file and Test/compile pass (fatal warnings on); formatFix and lint OOM-killed 4 times (sandbox 16 GB), not run.
Presented with comment, retitle, set_status Triaged.

User 2026-11-03: every comment body goes into a body file (comments/<ref>.md); only single-line plain-text comments inline. CLAUDE.md, issue-comment.md and ci-triage step 8 updated. Item 4 comment moved to comments/11144.md.

Item 4: user asked why the new eventually repeats the checks of the block below it; fix redone as the user chose: block 1 (wait for round 10) deleted, the per-round read block wrapped in eventually(). Amended to 9f4b991cbd (replaces bd47fecde5); scalafmtOnly and Test/compile pass. Body file updated.

Item 4: user opened PR 8221. User: issue comments never reference the fix PR or branch (the PR links the issue with Fixes); describe the fix in one sentence. Body file and issue-comment.md updated; applies to all later items.

Item 4: comment posted (user, 2026-11-03); retitle and set_status not confirmed. PR 8221 waits for review. Lesson
not yet answered. Resolved.

Item 5 (11160): artifact re-downloaded (lost in the rebuild). Correction to the packet: the Created batch at
12:29:27.680 holds requests enqueued at 27.667/27.672, not the one from 09.478; the decisive lines are the insert
block retry chain (11.392 to 27.539) and block 6840 at 27.587 delivering the broadcast; empty blocks kept being
processed in the gap. Fix is production code (validator app), so no automatic branch. Body file comments/11160.md.

Item 5: user flagged the #8192 link: a bare #N in a cn-test-failures comment links to the cn-test-failures issue N, and the commit under test needs no reference. Removed from comments/11160.md; rule added to issue-comment.md. Applies to later items: the old batch comment for 10891 (item 11) cites splice PRs #8017 and #8148 bare.

Item 5: user asked for the issue tracking the 40001 contention. Export issues-since-2026-07-01 (22:33) was cut at 1000 (GitHub search cap; oldest 10177), so 10139 is missing. In it: 10197 open, comment 2026-10-15 pawelperek-da '2w no repro'; 10273 (castVote, stephencompall-DA verdict: reference-sequencer contention on the shared Postgres) open, same '2w no repro'. No dedicated contention issue found. export_issues.py now queries 7-day windows and fails if a window hits the cap.

Item 5: full export (22:37, 2176 issues from 9001): 10139 open, unassigned, last comment ray-roestenburg-da 2026-10-06 (waiting for fix-fail-fast). 11160 body now references #10139 as tracking issue; recurrence comment drafted in comments/10139.md.

Item 5: user asked why 40001. Jar 3.7.1: insert block = insert into blocks with id = max(id)+1 under SERIALIZABLE, one table shared by the 4 global sequencers. Correction: not all writers stalled; Sv1 lost every retry 12:29:13.5-27.5 while Sv2-4 stored 31-32 batches each (blocks 6741-6839 without events). comments/11160.md and comments/10139.md rewritten; family L cause updated.

Live check 2026-11-03 (gh api): PR 8211 merged 21:56:57, closed 11128 (completed); PR 8215 merged 21:20:20, closed 10732 (completed). 11143 closed duplicate. 11144 open, retitled, 1 comment (PR 8221 open). 11160 open, no comment yet. Board Status not readable from the sandbox.

Item 5: user asked why tests do not fail more often. Data (whole shard): 40001 routine, 99 percent of retry chains < 0.5 s, max 4 consecutive losses except Sv1 (8); failure ratio rises through the shard independent of load; 12:29 load not unusual. Reference driver: integration tests (4 writers) plus single-writer compose/localnet; Helm deployments use cometbft/cantonbft. Comments 11160 and 10139 updated (no sim-clock causation).

Item 5: GCP Postgres log (user export downloaded-logs-20261104-001129.json): 94 failures 12:29:05-28, all pivot during write on insert into blocks; 2-3 of 4 writers aborted at the same instant in 26 of 58 collisions; Sv1 retries match within 1 ms. Packet section 7; recipe 13 target confirmed (da-cn-splice).

Item 5: user: 10139 is about sys.exit, so findings go into 11160 (links 10139, 10197, 10273); comments/10139.md dropped. New: driver storage ignores the 1 s storage-max-retry-interval (ProcessingTimeout() defaults, 10 s), empty-block tick every 500 ms per driver. comments/11160.md rewritten with fix options (Canton driver timeouts, test config empty-block interval, validator wait).

Item 5: user asked for a frequency check on unrelated runs. New script ref_seq_contention.py. 11144 (simtime (3)) and 11141 (frontend-wall-clock-time (2)): same rate (0.30-0.34 failed per stored batch), chains up to 5-6 failures, max 2.5 s. 11155 (wall-clock-time (3)) has no insert block at all: main wall-clock shards run Canton BFT (-we). 11160 comment sentence on rising rate replaced by the comparison.

## Duplicate check 2026-11-03 late (no new triage)

User: pause for today; only close inbox items that are duplicates. Live issue list via gh api (newest 11176).
- 11169 (run 41661993096, wall-clock-time (2), main d852f3c54c): family Z, dup of 11128. Same assertion at
  ScanPartyMonitorScriptIntegrationTest.scala:273; confirm: script `At record time 2026-11-03T15:56:14.862127Z` for 16
  polls until the failure at 15:56:35.910; d852f3c54c does not contain the 8211 merge 50545a01cc.
- 11176 (run 41721296163, frontend-wall-clock-time (1)): family AE, dup of 11161. Same failure
  `[frontend=bobSplitwell step=enter-payment-accept-verify] Could not find IdQuery(user-id-field) (FrontendIntegrationTest.scala:427)`;
  confirm: `#logout-button` FindElement no such element at 21:59:45.539 and 21:59:49.035, two `Failed clue: Waiting
  for login page to be ready` at 21:59:48.963 and 21:59:52.041, failure screenshot shows bob logged in (Logout).
- Earlier verdicts of this round, closes handed out now: 11163 -> 10907 (item 7), 11155 -> 10438 (item 10),
  10891 -> 10921 (item 11; comment rewritten without bare splice PR numbers).
- Not duplicates by signature, left in the inbox untriaged: 11164 (WalletSurviveCantonRestartIntegrationTest),
  11165 and 11168 (UnclaimedActivityRecordIntegrationTest, same test, likely one group), 11172 (Splitwell settle
  debts with multiple parties, FrontendLoginUtil.scala:61, different assertion from AE), 11173 (checkErrors),
  11174 (backup_status_cluster, cni), 11175 (static_tests), 11170, 11171 (needs-log).

User: duplicate-close comments must not say Duplicate of #N (close --duplicate-of links the parent already). Rule in issue-comment.md; the five close commands of the duplicate check reissued without it.

Duplicate check: all five closed by the user 2026-11-03 23:49-23:54 (state_reason duplicate): 11169, 11176, 11163, 11155, 10891. 11169, 11176, 11163 still have the Duplicate of #N prefix (posted before the rule change). Items 7, 10, 11 resolved. Next session: step-mode queue from item 6 (11161), then the untriaged new items listed above.

## Resumed 2026-11-04

- 11160: user commented, waits for DACH-NY/canton#37633 (driver storage ignores node timeouts). Item 5 resolved.
- 11144 closed by PR 8221 (merged 06:18). PRs merged 2026-11-02 to 04: 8211, 8215, 8221.
- New issues since the last check: 11177, 11178, 11180, 11181 (run 41776986892: simtime (0), simtime (3),
  wall-clock-time (8), (9)), 11179 (auth0_user_cleanup), 11182 (docker-canton-simtime (0)); not yet classified.
- Item 6 (11161, AE): fix branch rautenrieth-da/fix-11161-login-helper-timeout off main 7307892450;
  body file comments/11161.md.

Item 6: PR 8227 (user, 2026-11-04). Duplicate check: none open (11172 fails later in the helper, FrontendLoginUtil.scala:61,
after the login click; 11177-11183 are not frontend jobs).
Item 8 (11141, AG): Postgres log from GCP (user export downloaded-logs-20261104-103714.json, runner
self-hosted-k8s-large-runner-8mrj7, 14:32:18-14:43:22): nothing in the stall window; checkpoint 14:37:22-14:41:53
spread (write 269 s, sync 1.6 s at 14:41:52). Vite bundling 14:38:15-21. Runner-wide slowdown (Canton log thin at
14:39:29), source not in the logs. Fix option: `storage.parameters.connection-timeout = 15s` in _shared.storage
(covers apps and Canton nodes; Hikari connectionTimeout via StorageConfig). Presented; branch offered, not created.

Item 8: user checked GKE metrics: node CPU 3 -> 9 cores in the window, neighbours < 0.05 cores; node pool replaced since. Slowdown = the job's own startup burst. Comment and packet updated.

## Re-prioritized 2026-11-04 (inbox export 10:21, 37 items; triaged export 10:40, 19 items)

User: first-pass every inbox item without a verdict, then process in order of importance: hard broken, cluster
deployments, frequent flakes, rest. First pass (job log only, no deep analysis):

| Prio | Refs | First pass | Family / parent | Next |
|---|---|---|---|---|
| 1 hard broken | 11178; 11180, 11181 | mainnet-Daml nightly (41776986892): tests need splice-amulet 0.1.25, nightly runs them against MainNet packages; every night since 10-31 | Y: dup 10930; dup 10933 (confirm greps done: batchType only BatchOfMintingAllowances; transfer POST then UNKNOWN_INFORMEES 42-43 ms) | fix branch rautenrieth-da/fix-10930-mainnet-daml-test-tags; close dups |
| 1 hard broken | 10728 | release lines: javac inference on codegen, one job per run | Q | waiting (cause open) |
| 2 cluster | 11106 11137 11142 11151 11171 11183 | deploy_sv_reonboard (CircleCI), 5 of the runs since 11-02 08:00 | ciperiodic, after #8125 | needs CircleCI step output |
| 2 cluster | 11185 | deploy_ciperiodic_upgrade_workflow (CircleCI 88091) | ciperiodic deploy_upgrade (10677, 10736) | needs CircleCI step output |
| 2 cluster | 11184 | bump_splice (CircleCI 88092) | - | needs CircleCI step output |
| 2 cluster | 10680 | reset_validator_runbook_devnet 0.9.4 (CircleCI 87309) | - | needs CircleCI step output |
| 2 cluster | 11145 11174; 11117 | Devnet backup_status_cluster at 18:1x on 11-02 and 11-03; 11117 06:00 after the Devnet LSU | 18:00 race 10915 (suspected); 11117 own | needs cni GHA log |
| 2 cluster infra | 11170, 11179, 10479 | cni GHA cluster_lock_reaper, auth0_user_cleanup, Purge Unused Databases | - | needs cni GHA log |
| 3 frequent | 11177 | mainnet nightly simtime (0): sv4 JoiningNodeInitializer IMPORT_ACS_ERROR topology offset, app exit | U, 4th hit (10907, 11107, 11163, 11177) | close dup; owner needed |
| 3 frequent | 11165, 11168 | UnclaimedActivityRecordIntegrationTest twice on 11-03 (11165 env setup failed; 11168 vote request list empty, line 109) | unknown | triage |
| 3 frequent | 11173 | checkErrors: aliceValidator high-priority circuit breaker tripped (DistributedDomainIntegrationTest) | like 9752, 10300, 10611 | triage |
| 3 frequent | 11172 | Splitwell settle debts multiple parties: no logout-button/loginFailed 20 s after login click (FrontendLoginUtil.scala:61) | AE-adjacent | triage |
| 4 rest | 11164 | WalletSurviveCantonRestartIntegrationTest CommandFailure | - | triage |
| 4 rest | 11182 | docker-canton-simtime checkErrors: BFT `Not all objects are known to the BFT peers` | - (first) | triage |
| 4 rest | 11175 | static_tests setup: 3 fetches of release-line-0.10.0 failed (15 min) | J infra one-off | comment, Infrequent |
| done | 10495 10496 11104 11105 11140 11147 | verdicts from earlier rounds (queue items 9, 12-16) | G, J, AA, -, AB, AF | hand out commands |
| waiting | 10893 10917 10918 10919 | as before | | none |

Priority 1 (Y): user asked why each test needs the tag. Traffic: needs #8012 (0.1.25) - tag correct. Coupon: needs active == latest amulet version, not 0.1.25 (wrong-vetting log lines 0 in nightly 11178 vs 3 in main 11144); proposed NoDamlCompatibilityCheck instead; the 10930 comment of 10-30 (likely needs 0_1_25) needs a correction.

Priority 1 (Y): user agreed. Branch amended to 67d433fb33 (coupon suite NoDamlCompatibilityCheck). Correction comment comments/10930.md.

Priority 1 (Y): PR 8228 (user) fixes 10930 only (Fixes line). User: 10930 is the single parent; close 11178 and the traffic failures (10933, 11180, 11181) as dups of 10930; retitle 10930 to cover both tests. Commands handed out.
