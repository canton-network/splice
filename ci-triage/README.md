# CI failure triage - 2026-09-15

Evidence packets for cn-test-failures refs. Each packet is reproducible: every command was run
against the run's downloaded artifacts and the pasted output is what it produced (long hashes trimmed
by a sed/cut baked into the command). Artifacts are streamed gzipped with `zcat` (no decompression).

## Overview

| My ref | GH run | Failure (one line) | Resolution / status |
|--------|--------|--------------------|---------------------|
| 10048 | 33911369750 | BFT deadlock: SEQ::sv4 named first leader of epoch 26 before it was initialized; ordering wedges (strongQuorum == size == 3) | Canton-side bug. Evidence packet `10048-bft-deadlock.md`. Hand off to Canton team. |
| 10084 | 34458892258 | checkErrors flags 18 IndexerState reconnect-drain WARNs from sv3Participant; all tests pass | Evidence packet `10084-indexer-reconnect-warn.md`. Log-level threshold artifact (INFO escalates to WARN after 2s under reconnect load), drain succeeds in ~3.8s. Not a functional bug; log-ignore rejected; real fix (canton `retryLogLevel=INFO`, or why disconnect returns before drain) open. |
| 10088 | 34474728903 | (A) roll-forward-lsu: `InvalidStaticSynchronizerParameters` over non-default synchronizerLimits at SV init; (B) LSU: bobValidatorLocal hangs 5 min - validator retries `modify synchronizer` against the OLD psid after the LSU deactivated it | Evidence packet `10088-lsu-failures.md`. A = canton 3.6 static-param check rejects LSU predecessor params (jobs cancelled). B (validator-side): bob picked psid `::36-0` for "Set the new synchronizer connection", the LSU activated `::36-2` and deactivated `::36-0`, so the modify loops on `SYNC_SERVICE_SYNCHRONIZER_STATUS_NOT_ACTIVE` (infinite 100ms retries, no re-pick of the new psid) -> 5-min timeout. Fix in the validator. Separate incidental: sv4 published its successor ~43s past the upgrade time (dropped) - not the cause. |
| 10091 / 10094 | 34477382133 | globalSequencerSv1 public API (:5108) unresponsive ~120s; co-located mediator's `acknowledge-signed` hits DEADLINE_EXCEEDED; checkErrors flags 2 WARNs. Plus a separate frontend vote-propagation flake. | Evidence packet `10094-sequencer-ack-stall.md`. ROOT CAUSE FOUND 2026-09-16 (section 8): the BFT ordering topology stepped 1 -> 4 at epoch 26 while sv2-4's sequencers were still being created; sv1 had no dissemination quorum (authenticated 1 < 2), was blacklisted for epochs 27-29 (~48 s) and could not answer acks for ~70 s; the mediator's ack from that window was never answered and timed out at 120 s. Same family as 10048/10137 (BFT onboarding), self-healed. NOT the LSU lower-bound variant of canton#33616 (0 SEQUENCING_TIME_NOT_ADMISSIBLE, no upgrade announcement). |
| 10121 | 34612379425 | SummarizingMiningRoundTrigger logs ERROR (fails checkErrors) on a retryable "our own Scan has not yet computed the reward accounting totals" that self-resolves on the next poll | FIX committed on branch `ray/fix-summarizing-round-log-noise` (return TaskNoop instead of throwing; INFO not ERROR). Compile not yet verified in-sandbox (disk-limited). |
| 10133 | 34931425682 | docker-compose validator: `ghcr.io` image pull `i/o timeout` bringing up the compose stack | Infra flake (transient registry timeout), no code bug. Re-run; other shard passed. |
| 10312 | cn-internal CircleCI `deploy_upgrade` | preflight `sbt testOnly` killed at the 15m step timeout; a cold Daml+Scala compile ate ~13 of 15 min before the test ran | FIX MERGED into cn-internal: add a `Test/compile` step to `preflight_check` before the timed testOnly (mirrors `preflight_validator_check`). |

## Notes on runtime canton version (differs per run; not what `canton/VERSION` says)

- 10048 (33911369750): canton 3.5.16
- 10088 (34474728903): canton 3.6.0-snapshot.20260909.20251.0.v0a9e6e25
- 10094 (34477382133): canton 3.5.15 (branch release-line-0.7.5)
- 10084 (34458892258): canton 3.6.0-snapshot.20260909.20251.0.v0a9e6e25

## Reproduction prerequisites

The sandbox root filesystem is nearly full; `gh run download` staging ENOSPCs there. Stage on a
mount with several GB free by setting TMPDIR, and keep artifacts gzipped:

```
TMPDIR=<roomy-mount>/ghtmp gh run download <run> --repo canton-network/splice -n <artifact> -D <dir>
cd <dir>
zcat <file>.clog.gz | grep -a '<pattern>'
```

# CI failure triage - 2026-09-16

Same packet conventions as above. Artifacts staged under `log/<ref>/` (git-ignored), job logs fetched with
`gh api repos/canton-network/splice/actions/jobs/<job>/logs` because `gh run view --log` returned empty
output for these jobs.

## Ref -> run -> job mapping

Refs are Raymond's cn-test-failures issue numbers, recorded exactly as he gave them (as (run, ref) tuples).
The tracker is not readable from this sandbox, so nothing here is inferred by the triage: where a run has
more than one failed job and more than one ref, the job-to-ref assignment is left open until Raymond states
it. Job names are listed so the packets can be found by job regardless.

| GH run | Refs (Raymond) | Branch / sha | Failed jobs | Ref -> job |
|--------|----------------|--------------|-------------|------------|
| 35052914872 | 10136 | release-line-0.8.1 66a5e3f02b (#7313 backport of #7310) | 104656956648 `static_tests` | single job |
| 35052864473 | 10137 | release-line-0.8.0 7cafbf8ef1 (#7314 backport of #7310) | 104657010559 `wall-clock-time (8)` | single job |
| 35057829498 | 10139 | main b5d5645c56 (nightly "Wall Clock Tests with Postgres 14") | 104671796734 `wall-clock-time (2)` | single job |
| 35067363744 | 10140 | main 8c20340d0d (#7322) | 104700886275 `simtime (1)` | single job |
| 35072334729 | 10141, 10142, 10143 | main 0c43730f70 | 104716522199 `ui_tests`; 104716731789 `simtime (3)`; 104716753202 `frontend-wall-clock-time (2)` | Raymond stated 10141 = ui_tests and 10142 = simtime (3) in the original request; 10143 is the remaining job, frontend-wall-clock-time (2) |
| 35076492327 | 10144 = `resource-intensive (1)` 104730519175, 10145 = `ui_tests` 104730030877 | main f1ee318e39 ("Don't wait forever on a non-active psid in ensureSynchronizerRegistered") | both | confirmed by Raymond 2026-09-17 |
| 35077158925 | 10146 | main 8f931e71c0 (#7329 backport of #7325 to main) | 104732535931 `wall-clock-time (4)` | single job |
| 35082230264 | 10147 | main 743a6ec124 (#7333) | 104749000327 `roll-forward-lsu (0)` | single job |
| 35092848061 | 10149 | release-line-0.8.1 b3e6bfa49d (backport of #7176) | 104783740346 `docker-no-canton (0)` | single job |
| 35103689739 | 10150 | main c1baff4cfc (#7341) | 104819283178 `deployment_test` (step "Check that pinned docker images are multi-arch") | single job |
| unknown | 10135 | - | - | run not yet given |

## Overview

| My ref | GH run | Failure (one line) | Resolution / status |
|--------|--------|--------------------|---------------------|
| 10048 | 33911369750 | BFT deadlock: SEQ::sv4 named first leader of epoch 26 before it was initialized; ordering wedges (strongQuorum == size == 3) | Canton-side bug. Evidence packet `10048-bft-deadlock.md`. Hand off to Canton team. |
| 10084 | 34458892258 | checkErrors flags 18 IndexerState reconnect-drain WARNs from sv3Participant; all tests pass | Evidence packet `10084-indexer-reconnect-warn.md`. Log-level threshold artifact (INFO escalates to WARN after 2s under reconnect load), drain succeeds in ~3.8s. Not a functional bug; log-ignore rejected; real fix (canton `retryLogLevel=INFO`, or why disconnect returns before drain) open. |
| 10088 | 34474728903 | (A) roll-forward-lsu: `InvalidStaticSynchronizerParameters` over non-default synchronizerLimits at SV init; (B) LSU: bobValidatorLocal hangs 5 min - validator retries `modify synchronizer` against the OLD psid after the LSU deactivated it | Evidence packet `10088-lsu-failures.md`. A = canton 3.6 static-param check rejects LSU predecessor params (jobs cancelled). B (validator-side): bob picked psid `::36-0` for "Set the new synchronizer connection", the LSU activated `::36-2` and deactivated `::36-0`, so the modify loops on `SYNC_SERVICE_SYNCHRONIZER_STATUS_NOT_ACTIVE` (infinite 100ms retries, no re-pick of the new psid) -> 5-min timeout. Fix in the validator. Separate incidental: sv4 published its successor ~43s past the upgrade time (dropped) - not the cause. |
| 10091 / 10094 | 34477382133 | globalSequencerSv1 public API (:5108) unresponsive ~120s; co-located mediator's `acknowledge-signed` hits DEADLINE_EXCEEDED; checkErrors flags 2 WARNs. Plus a separate frontend vote-propagation flake. | Evidence packet `10094-sequencer-ack-stall.md`. Raymond's pick to prioritise. Two independent analyses converged. |
| 10121 | 34612379425 | SummarizingMiningRoundTrigger logs ERROR (fails checkErrors) on a retryable "our own Scan has not yet computed the reward accounting totals" that self-resolves on the next poll | FIX committed on branch `ray/fix-summarizing-round-log-noise` (return TaskNoop instead of throwing; INFO not ERROR). Compile not yet verified in-sandbox (disk-limited). |
| 10133 | 34931425682 | docker-compose validator: `ghcr.io` image pull `i/o timeout` bringing up the compose stack | Infra flake (transient registry timeout), no code bug. Re-run; other shard passed. |
| 10312 | cn-internal CircleCI `deploy_upgrade` | preflight `sbt testOnly` killed at the 15m step timeout; a cold Daml+Scala compile ate ~13 of 15 min before the test ran | FIX MERGED into cn-internal: add a `Test/compile` step to `preflight_check` before the timed testOnly (mirrors `preflight_validator_check`). |

## Notes on runtime canton version (differs per run; not what `canton/VERSION` says)

- 10048 (33911369750): canton 3.5.16
- 10088 (34474728903): canton 3.6.0-snapshot.20260909.20251.0.v0a9e6e25
- 10094 (34477382133): canton 3.5.15 (branch release-line-0.7.5)
- 10084 (34458892258): canton 3.6.0-snapshot.20260909.20251.0.v0a9e6e25

## Reproduction prerequisites

The sandbox root filesystem is nearly full; `gh run download` staging ENOSPCs there. Stage on a
mount with several GB free by setting TMPDIR, and keep artifacts gzipped:

```
TMPDIR=<roomy-mount>/ghtmp gh run download <run> --repo canton-network/splice -n <artifact> -D <dir>
cd <dir>
zcat <file>.clog.gz | grep -a '<pattern>'
```

# CI failure triage - 2026-09-16

Same packet conventions as above. Artifacts staged under `log/<ref>/` (git-ignored), job logs fetched with
`gh api repos/canton-network/splice/actions/jobs/<job>/logs` because `gh run view --log` returned empty
output for these jobs.

## Ref -> run -> job mapping

The cn-test-failures repo is not readable from this sandbox. Refs given only as issue URLs were first mapped
by elimination and then confirmed by Raymond on 2026-09-16: (35052914872, 10136), (35052864473, 10137),
(35057829498, 10139). Ref 10135 is therefore NOT one of the two release-line backport runs; its run is still
unknown (see below). 10143 remains inferred as the third failed job of run 35072334729.

| My ref | GH run | Branch / sha | Failed job | Mapping |
|--------|--------|--------------|------------|---------|
| 10135 | unknown | - | - | UNMAPPED: not a 09-16 03:43Z backport run; awaiting run URL |
| 10137 | 35052864473 | release-line-0.8.0 7cafbf8ef1 (#7314 backport of #7310) | 104657010559 `wall-clock-time (8)` | confirmed by Raymond |
| 10136 | 35052914872 | release-line-0.8.1 66a5e3f02b (#7313 backport of #7310) | 104656956648 `static_tests` | confirmed by Raymond |
| 10139 | 35057829498 | main b5d5645c56 (nightly "Wall Clock Tests with Postgres 14") | 104671796734 `wall-clock-time (2)` | given |
| 10140 | 35067363744 | main 8c20340d0d (#7322) | 104700886275 `simtime (1)` | given |
| 10141 | 35072334729 | main 0c43730f70 | 104716522199 `ui_tests` | given |
| 10142 | 35072334729 | main 0c43730f70 | 104716731789 `simtime (3)` | given |
| 10143 | 35072334729 | main 0c43730f70 | 104716753202 `frontend-wall-clock-time (2)` | inferred |

## Overview

| My ref | GH run / job | Failure (one line) | Category | Resolution / status | Duplicate of |
|--------|--------------|--------------------|----------|---------------------|--------------|
| 10136 | 35052914872 static_tests (release-line-0.8.1) | `apps-app / scalafixAll` dies with "Unable to load symbol table: .../classes/splice-wallet-0.1.22.dar.<uuid>.tmp": scalafix indexed the classes dir while `copyResources` was atomically staging a dar into it. The "ErrorResponse.scala contained different content" lines are guardrail noise present in passing runs too. | build race, FIXED on main, missing backport | Packet `10136-scalafix-dar-copy-race.md`. Raymond identified it: #7176 (4031327bc4, 2026-09-10, `compile dependsOn copyResources` for apps-app) fixed exactly this on main and was never backported; release-line-0.8.1, 0.8.0 and 0.7.5 lack it (compare API `diverged`, no copyResources ordering in their build.sbt, no backport PR). Backport #7176 to the release lines; rerun until then. | #7176 (main, fixed) |
| 10137 | 35052864473 wall-clock-time (8) (release-line-0.8.0, canton 3.5.16) | SvOnboardingIntegrationTest: `initDso()` times out after 5 min on sv3. SEQ::sv3 joins the BFT ordering topology (size = strongQuorum = 3) at epoch 21 while two epochs behind and still waiting for onboarding state; it receives all of epoch 20 but never enters 21; nothing is ordered again. sv3's app then spins on "No traffic state found for MED::sv3" from its own frozen sequencer. Teardown plugin `sys.exit(1)`s, so no ScalaTest report. | real bug, canton BFT onboarding | Packet `10137-sv3-init-timeout-bft-onboarding-wedge.md`. Same mechanism and log signatures as 10048 (2->3 membership step with the newcomer two epochs behind; the "named leader before init" detail in 10048 is incidental). New finding: sv3's SequencerSynchronizerState became effective 170 us after epoch 19's topology tick, which slipped its membership from epoch 20 to 21. Hand to Canton BFT team; splice mitigation options in packet. | 10048 family (BFT onboarding, 27+ CI occurrences) |
| 10139 | 35057829498 wall-clock-time (2) (nightly PG14, main) | SvStateManagementIntegrationTest teardown: ResetDecentralizedNamespace waits 60 s for its proposals, then `sys.exit(1)` kills sbt (no report, checkErrors skipped). Proposals missed their 20 s max sequencing time because globalSequencerSv1's reference driver `insert block` sat in a PostgreSQL SQLSTATE 40001 serialization-failure retry loop (backoff up to 7.9 s), then all four sequencers exhausted their 8-connection DB pool and disconnected all members. 5 tests passed, none failed, 5 never ran. | infra / canton reference sequencer contention | Packet `10139-reset-namespace-sequencer-insert-block-storm.md`. Flake, not a regression. Test-infra: drop `sys.exit(1)` in ResetTopologyStatePlugin, allow more than 60 s or resubmit after a `not sequenced` timeout. Canton/infra: 40001 storm rises 93/min -> 346/min with four reference-driver writers on one Postgres; cap the backoff, raise the pool, or fewer sequencers per shard. | none; check whether 10094's sequencer stall shows the same `insert block` 40001 + pool-exhaustion signature |
| 10140 | 35067363744 simtime (1) (main) | checkErrors WARN: aliceParticipant got `ABORTED/SERVER_OVERLOADED` from globalSequencerSv1 on `DownloadTopologyStateForInitHash`. The limit is splice's own test-only `public-api.limits` = 3 in `apps/app/src/test/resources/include/sequencers.conf`; sv4, sv3 and splitwellValidator held the 3 slots and alice connected 8 ms later. Alice retried after 1 s and finished topology init 1.17 s later. All 11 tests passed. | checkErrors WARN, splice test config | Packet `10140-sequencer-overloaded-topology-init.md`. Flake. Fix either: raise the test-only limit to >= participant count (7), or add a scoped ignore next to the existing `SEQUENCER_OVERLOADED` one in canton_log.ignore.txt. Optional upstream: Canton logs a retried refusal at WARN client-side while the server logs it at INFO. | none; same failure mode family as 10094/10121/10084 (all tests pass, one WARN fails checkErrors) |
| 10141 | 35072334729 ui_tests (main) | SV frontend vitest exits 1 with 269/269 tests passed: an un-awaited `waitFor` in `set-amulet-rules-form.test.tsx:71-85` (sync test, 1000 ms override) rejects after its test finished ("Unhandled Rejection", `AssertionError: expected 20 to be greater than 65`). The 681 "Invalid URL: h..." lines, 67 MSW unhandled requests, ENOTFOUND, 503/500 lines all appear with identical counts in passing runs. | frontend unit test flake | Packet `10141-sv-ui-unawaited-waitfor.md`. Latent since #1945 (2025-08), exposed by a ~1.8x slower runner (SV suite 115 s vs 63 s). Fix: make the test `async`, `await` the `waitFor`, drop the 1000 ms override. ui_tests passed in the main runs before and after. Owner: SV UI. | none |
| 10142 | 35072334729 simtime (3) (main) | checkErrors ERROR: sv1's SummarizingMiningRoundTrigger "Skipping processing of Task(summarizingRound = round 3)" with `FAILED_PRECONDITION: our own Scan has not yet computed the reward accounting totals`. TrafficBasedRewardsTimeBasedIntegrationTest itself paused sv1Scan verdict ingestion for 123 s; round 3 closed inside the pause; the SV's `RetryFor.Automation` budget (35 retries) ran out 2 s before the resume. Scan computed rounds 3..10 on resume and the SV succeeded 5 s later. All 12 tests passed. | checkErrors ERROR, splice sv trigger | Packet `10142-summarizing-round-retry-budget.md`. DUPLICATE of 10121, and supplies its mechanism: the ERROR fires whenever the test's ingestion pause outlasts the ~114 s retry budget. Land the existing fix 2fb77e0be2 on `ray/fix-summarizing-round-log-noise` (same code path; `Undetermined` -> `TaskNoop` at INFO). Do not add an ignore for the generic "Skipping processing of" sink. | 10121 |
| 10143 | 35072334729 frontend-wall-clock-time (2) (main) | WalletAuth0FrontendIntegrationTest "redirect to the previous page after login": after logout and `go to /confirm-payment/<cid>`, clicking "Log In with OAuth2" never produced Auth0's login form; "None was equal to None" is `find(tagName("h1")) should not be None` at FrontendIntegrationTest.scala:565 polled for 20 s. oidc-client-ts reached `RedirectNavigator navigate: begin`; the Marionette click took exactly 5.062 s (Firefox unload timeout: `beforeunload` fired, `pagehide` never did), so the top-level GET to the Auth0 /authorize URL never committed. Same click succeeded twice earlier in the suite; zero WARN/ERROR in wallet, validator or canton logs. | frontend flake, external IdP navigation | Packet `10143-wallet-auth0-redirect-stall.md`. Flake in the Auth0 redirect (Auth0 measurably slower in this run: form POST 5.1 s vs 1.0 s), not a Splice bug. Test gap: the initial login is wrapped in a 1-minute "auth0 login gets stuck" retry, the re-login step at WalletAuth0FrontendIntegrationTest.scala:106 is not. Fix: reuse that retry there; optionally capture BiDi network events in the driver. | none confirmed; same family as the pre-existing "auth0 login workflow gets stuck" retry (b6086ad603) |
| 10145 | 35076492327 ui_tests (main f1ee318e39) | SV frontend vitest: `config-diffs.test.tsx > SV can see AmuletRules config diffs > in the rejected section` fails on `screen.findByText('Vote Requests')` in the shared helper `navigateToLegacyGovernancePage` hitting testing-library's 1000 ms default timeout; the /governance-old route needs two Loading gates to clear. Suite ran at baseline speed; the same test passed at 1.4-2.0 s in the three previous main runs, including the slow 10141 run. 268/269 passed. | frontend unit test flake | Packet `10145-ui-tests-config-diffs-findby-timeout.md`. Not the 10141 cause (that un-awaited waitFor passed silently here); same family: 1000 ms testing-library default vs the 15 s vitest budget. Fix: `configure({ asyncUtilTimeout })` in the SV frontend test setup or a `{ timeout }` on the helper's findByText. Owner: SV UI. | none (family: 10141) |
| 10144 | 35076492327 resource-intensive (1) (main f1ee318e39) | checkErrors: 3 ERROR lines from sv1's CreateBootstrapExternalPartyConfigStateInstructionTrigger: one `GetPreferredPackages` call got a redacted INTERNAL. Server side (tid match) sv1Participant threw `Missing package-id <splice-amulet-name-service 0.1.21> in package metadata view` while the SV app was uploading splice-dso-governance 0.1.26 (24 DAR uploads in 15 s after the "Change AmuletConfig to latest packages" vote): Canton merges the metadata view one package at a time, main package first, and the query snapshotted it mid-merge. Trigger retried after 1 s and succeeded; 1 of 745 calls failed. Both tests passed. | checkErrors ERROR, Canton package-metadata race | Packet `10144-resource-intensive-get-preferred-packages-metadata-view-race.md`. Known Canton race: the server-side line is already allowlisted (canton_log.ignore.txt:210, #6356, cn-test-failures 9136) and matched here; only the client-side mirror escapes because the ledger API redacts INTERNAL and RetryProvider treats INTERNAL as non-retryable. Interim: scoped allowlist for the three client lines, or an operation-scoped INTERNAL retry in getSupportedPackageVersion. Real fix in Canton: atomic updateMany or a tolerant getPreferredPackages. | #6356 / cn-test-failures 9136 (server side) |
| 10146 | 35077158925 wall-clock-time (4) (main 8f931e71c0) | checkErrors WARN `ACS_COMMITMENT_MISMATCH` on sv1Participant, sender aliceValidator, period (09:28:07.504, 09:30:00] = 112.5 s, digests differ. All 16 tests passed. MECHANISM: AmuletExpiryV1FallbackIntegrationTest multi-hosts `alice__wallet__user` on sv1Participant at 09:27:44.97 via a plain PartyToParticipant proposal (no onboarding flag, zero ACS-import lines on sv1) after alice already owns alice-only contracts (WalletAppInstall, ValidatorRight). Canton 3.6's new commitment pipeline (old processor disabled) writes PartyHostingChange checkpoints and re-buckets contracts on hosting changes, so the two sides compute different digests. | checkErrors WARN, test-created real divergence | Packet `10146-acs-commitment-mismatch.md`. Duplicate of 10129 and run 34523566111 (that shard ran ExpiryWithNoVettedAmuletVersionIntegrationTest, same base class, same multi-host step). Explains the onset: multi-host step since #6680 (2026-08-06), canton 3.6 since #6859 (2026-08-21). Period starts are not boundary artifacts and a second period would mismatch again, so the 30 s learning branch is not needed. Fix the four suites (AmuletExpiryV1Fallback, ExpiryWithIgnoredAmuletVersion, ExpiryWithNoVettedAmuletVersion, AutoIgnoreUnresponsiveParties): host alice on sv1 before onboardWalletUser, or replicate with ACS import, or drop the extra host. Do NOT widen ignore line 145. | 10129, run 34523566111 |
| 10147 | 35082230264 roll-forward-lsu (0) (main 743a6ec124) | checkErrors WARN in the shutdown half of the BFT standalone `roll-forward-lsu-dr` canton log: sv4StandaloneSequencer `[UNEXPECTED] State transition for P2PUrl("http://localhost:28210") ... Connecting (unchanged)` at 10:10:10.186, 211 ms after the test SIGTERMed the 4-sequencer JVM. Port 28210 = sv2Standalone's BFT P2P port; sv2's orderer closed first, sv4 (still proposing) reconnected twice within 5 ms on two threads and the first connect worker found the state replaced by the second attempt's channel-less Connecting. The single test passed. | checkErrors WARN, Canton BFT P2P log level at peer shutdown | Packet `10147-bft-p2p-connecting-unchanged-shutdown.md`. Timing flake at process shutdown, not a regression. No ignore pattern matches (no `canton-standalone-roll-forward-lsu-dr.ignore.txt`; shutdown-extra set has nothing for this class). Fix: shutdown-scoped line in `canton_log_shutdown_extra.ignore.txt` (precedent canton_log.ignore.txt:129-130), and report the WARN level (sibling race logs DEBUG) to the Canton BFT team. Canton 3.6.0-snapshot.20260910.20260.0.v90621933, verified against the real jar. | none |
| 10149 | 35092848061 docker-no-canton (0) (release-line-0.8.1 b3e6bfa49d, the #7176 backport) | BulkStorageCommitFromStagingTest "wait until all objects are known to the peers ..." times out on `expectNext(20 s)`. The test re-stubs `getBulkObjectChecksums` on live Mockito mocks while the copy flow polls them; the pool thread's `url()` call landed between `when(...)` and `thenReturn(...)`, so the `Future` answer got bound to `url()`. The next poll's `executeCall` callback died with `ClassCastException: Promise cannot be cast to Uri` before counting the response, the BFT call's promise never completed, the flow stopped polling and the sink timed out. | test bug, Mockito stubbing race | Packet `10149-bulk-storage-mockito-stubbing-race.md`. Not the backport (build.sbt only), not Canton; main has the same stubbing pattern (differs only by the requiredCatchupTimestamp argument). FIX on branch `ray/fix-bulk-storage-test-stubbing-race` (uncompiled here): stub once with `thenAnswer` reading a per-scan AtomicReference, no `when(...)` after the flow is running. The fatal ERROR is hidden by `assertLogsSeq(ERROR)` + `forAtLeast`, so only the clog shows the cause. | FIX VERIFIED 2026-09-17: `ray/fix-bulk-storage-test-stubbing-race` 63359204ae (rebased on main), compiled + scalafmt clean + 6/6 green local runs of the suite (Postgres + s3mock containers); unpushed, needs PR + 0.8.x/0.8.1 backport. |
| 10150 | 35103689739 deployment_test (main c1baff4cfc) | `scripts/check-multiarch-images.py` fails: `skopeo inspect` of `python:3.12-slim@sha256:2c941e...` (cometbft-watchdog Dockerfile) got HTTP 502 Bad Gateway from Docker Hub; the script counts any inspect error as "not multi-arch". The other 13 images, including 4 other Docker Hub images, resolved in the same run. The digest is a multi-arch OCI index (verified with skopeo from the sandbox: amd64, arm64, arm, 386, ppc64le, riscv64, s390x). Pin unchanged since 2026-08-18, check unchanged since 2026-09-03, #7341 is cluster config only. | infra flake (external registry) | Packet `10150-multiarch-check-dockerhub-502.md`. Re-run. Harden the script: `skopeo --retry-times 3`, and report "could not inspect" separately from "single-arch". | none (same class as 10133) |

## Cross-cutting observations

- `sys.exit(1)` in ResetTopologyStatePlugin (ResetDecentralizedNamespace) destroyed the evidence in both
  10137 and 10139: no ScalaTest summary, no junit XML, checkErrors skipped. Same pattern noted for the
  wall-clock shards in the 2026-09-08 triage. Making the plugin fail the suite instead is a cheap,
  independent win.
- Six items (10140, 10142, 10146, 10147, resource-intensive (1) of 35076492327, plus 10121/10094/10084
  from earlier) are "all tests pass, one log line fails checkErrors". 10142 is the only one with a fix
  already written.
- 10137 confirms the BFT onboarding wedge family is still live on release-line-0.8.0 (canton 3.5.16).
- Runtime canton versions: 10136/10137 (release-line-0.8.x) 3.5.16; 10139/10140/10141/10142/10143 (main)
  3.6.0-snapshot.20260910.20260.0.v90621933. `canton/VERSION` says 3.5.7-SNAPSHOT at all these shas.

# CI failure triage - 2026-09-17

Same packet conventions. Artifacts under `log/<ref>/<artifact-name>/` (git-ignored), job logs via
`gh api repos/canton-network/splice/actions/jobs/<job>/logs`. Refs are Raymond's cn-test-failures numbers,
given as (run, job, ref) tuples in the request; nothing inferred.

## Ref -> run -> job mapping

| My ref | GH run | Branch / sha | Failed job | Canton |
|--------|--------|--------------|------------|--------|
| 10153 | 35113435367 | main f3adbc39e1 | 104853130622 `wall-clock-time (0)` | 3.6.0-snapshot.20260910.20260.0.v90621933 |
| 10154 | 35115826905 | release-line-0.8.x ba405bcbf6 (#7344) | 104861329019 `simtime (0)` | 3.5.17 |
| 10155 | 35115412224 | main ed12df6164 (#7348) | 104861312251 `wall-clock-time (5)` | 3.6.0-snapshot.20260910.20260.0.v90621933 |
| 10156 | 35115826905 | release-line-0.8.x ba405bcbf6 (#7344) | 104863007896 `frontend-wall-clock-time (2)` | 3.5.17 |
| 10157 | 35122937351 | release-line-0.8.x b741fda663 (#7342) | 104884928585 `ui_tests` | n/a (vitest only) |
| 10158 | 35123371703 | main 2b3e9d21ae (#7352) | 104886784003 `wall-clock-time (1)` | 3.6.0-snapshot.20260910.20260.0.v90621933 |
| 10162 | 35206251191 | main 22e775d614 (#7363) | 105152950685 `wall-clock-time (1)` | 3.6.0-snapshot.20260910.20260.0.v90621933 |
| 10161 | 35206251191 | main 22e775d614 (#7363) | 105152950723 `wall-clock-time (6)` | 3.6.0-snapshot.20260910.20260.0.v90621933 |
| 10164 | 35222005752 | main d7a75f6e9e (#7346) | 105204675967 `wall-clock-time (6)` | 3.6.0-snapshot.20260916.20284.0.vf27c4824 |
| 10165 | 35223275137 | main ce81b9f29b | 105208906926 `wall-clock-time (9)` | 3.6.0-snapshot.20260916.20284.0.vf27c4824 |
| 10166 | 35226878907 | release-line-0.8.x 57c06ffded (#7369) | 105220970474 `simtime (2)` | 3.5.18-snapshot.20260916.19252.0.v9635aea8 |
| 10111 | 34523566111 | main 4031327bc4 (#7176), 2026-09-10 | 103027245797 `wall-clock-time (1)` | 3.6.0-snapshot (2026-09-10 pin) |
| 10010 | 33785420105 | main 3063ad675b (#7110), 2026-09-03 | 100749318272 `wall-clock-time (1)`; (8) and (9) are in the off-boarding sweep | 3.5.16 |
| 10167 | 35237977178 | main e6689c46e7 | 105259186280 `wall-clock-time (1)` | 3.6.0-snapshot.20260916.20284.0.vf27c4824 |
| 10169 | 35325516312 | release-line-0.8.3 5f97fba71b (#7383) | 105537809798 `wall-clock-time (1)` | 3.5.18 |
| 10170 | 35326825932 | release-line-0.8.x 19bfcbca6f (#7386) | 105542036722 `simtime (0)` | 3.5.18 |
| 9740 | 32257514623 | main 306014bc81 (#6853), 2026-08-19 | 96082925012 `simtime (1)` | 3.5.14-snapshot.20260815 |
| 10171 | 35330711023 | release-line-0.8.3 8460154135 (#7389, release 0.8.3) | 105556336894 `simtime (3)` | 3.5.18 |
| 8784 | 27544243403 | main 21ed11e124, 2026-06-15 (logs expired) | simtime (3) / signatures (0) / roll-forward-lsu (1), job for the ref unknown | - |
| 10172 | 35341655357 | release-line-0.8.x 495349f01f (#7381) | 105588693020 `deployment_test` | 3.5.18 (n/a) |
| 10173 | 35349868387 | release-line-0.8.3 9adbf80cd2 (#7410) | 105615504911 `simtime (2)` | 3.5.18 |
| 10174 | 35366024437 | main 696b79a4c6 (#7418) | 105669130974 `logical-sync-upgrade (0)` | 3.6.0-snapshot.20260916.20284.0.vf27c4824 |
| 10175 | 35368993884 | main 6d59d2b131 (#7416) | 105678546586 `wall-clock-time (1)` | 3.6.0-snapshot.20260916.20284.0.vf27c4824 |
| 10176 | 35379257952 | main 18f490ae5a (the 10174 fix) | 105711633730 `wall-clock-time (5)` | 3.6.0-snapshot.20260916.20284.0.vf27c4824 |

## Overview

| My ref | Failure (one line) | Duplicate of | Resolution / status |
|--------|--------------------|--------------|---------------------|
| 10153 | All 36 tests pass; checkErrors flags 2 WARNs: splitwellParticipant `acknowledge-signed` to SEQ::sv1 (:5108) hits DEADLINE_EXCEEDED after 120 s during ValidatorIntegrationTest "validator apps connect to all DSO sequencers". Epoch 28 steps the BFT topology 1 -> 4 at 15:33:18 with only sv1 authenticated; sv1 is blacklisted epochs 29-31 (42.5 s); the ack accepted at 15:33:20 is answered 155 ms after the client cancelled. | 10094 (same mechanism; 10048/10137 family) | Packet `10153-sequencer-ack-stall-bft-1-to-4.md`. First occurrence on main / canton 3.6. Canton-side (BFT onboarding membership step); splice mitigation options as in 10137. |
| 10154 | WalletMintingDelegationTimeBasedIntegrationTest: `transferPreapprovalSend` -> `LOCAL_VERDICT_INACTIVE_CONTRACTS` right after `advanceTime(PT25H)`. | cn-test-failures 10060 / splice #7223 | Packet `10154-minting-delegation-time-advance-missing-backport.md`. FIXED on main by #7261 (0c43730f70, 2026-09-16); NOT on release-line-0.8.x. Backport. |
| 10155 | All 20 tests pass; checkErrors WARN `ACS_COMMITMENT_MISMATCH` sv1Participant vs aliceValidator, period (15:59:21.46, 16:00:00], 4 s after AutoIgnoreUnresponsivePartiesInMemoryIntegrationTest's `Multi-host alice on sv1Participant` (ExpiryWithIgnoredAmuletVersionIntegrationTest did the same 70 s earlier). | 10146 (and 10129, run 34523566111) | Packet `10155-10158-acs-commitment-mismatch-expiry-multihost.md`. Fix the multi-hosting test suites; do not widen the ignore. |
| 10156 | SplitwellFrontendIntegrationTest "settle debts with multiple parties": alice's splitwell UI (:3400) never renders a login form. Firefox blocked all `/node_modules/.vite/deps/*.js` modules ("disallowed MIME type") because alice's `vite --force` optimizer lost the `.vite/deps` rename race against bob's and charlie's servers (same directory) at 15:46:58 and only re-optimized at 16:00:19. | cn-test-failures 9704 | Packet `10156-splitwell-vite-deps-cache-race-missing-backport.md`. FIXED on main by #7305 "Per-port vite deps caching" (4f0c04b8ed, 2026-09-15); NOT on release-line-0.8.x. Backport. |
| 10157 | wallet vitest: 2 of 32 tests in `wallet.test.tsx` hit the describe-level 7500 ms cap. (A) "see allocation requests v2, and accept them": contract id re-minted per poll remounts the Accept button. (B) "Regular transfer offer > ... checkbox is unchecked": 7726 ms on a slow runner, siblings 6.0-7.2 s. | (A) cn-test-failures 10120 | Packet `10157-wallet-vitest-7500ms-timeouts.md`. (A) FIXED on main by #7304 (ffe110031a, 2026-09-15), NOT on release-line-0.8.x: backport. (B) remove the `}, 7500)` override at `wallet.test.tsx:1140` (same class as #7252). |
| 10158 | All 28 tests pass; checkErrors WARN `ACS_COMMITMENT_MISMATCH` sv1Participant vs aliceValidator, period (16:59:27.31, 17:00:00], 13 s after ExpiryWithIgnoredAmuletVersionIntegrationTest's `Multi-host alice on sv1Participant`. | 10146 | Same packet as 10155. |
| 10162 | All 23 tests pass; checkErrors WARN `ACS_COMMITMENT_MISMATCH` sv1Participant vs aliceValidator, period (09:59:53.89, 10:00:00], 3.6 s after AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest's `Multi-host alice on sv1Participant`. | 10146 | Section 5 of the 10155/10158 packet. Sixth occurrence. |
| 10161 | All 25 tests pass; checkErrors flags globalMediatorSv1's acknowledge-signed to SEQ::sv1 at DEADLINE_EXCEEDED (120 s) during SvOnboardingIntegrationTest: epoch 32 steps 1 -> 4 at 09:50:45 with only sv1 authenticated, sv1 blacklisted epochs 33-35, the ack accepted in the gap is answered 190 ms after cancellation. | 10094 / 10153 | Packet `10161-mediator-ack-stall-bft-1-to-4.md`. Third hit; two on main in two days. Canton-side. |
| 10164 | All 39 tests pass; checkErrors WARN `ACS_COMMITMENT_MISMATCH` sv1Participant vs aliceValidator, period (12:59:58.30, 13:00:00], 2.5 s after AmuletExpiryV1FallbackIntegrationTest's `Multi-host alice on sv1Participant`. | 10146 | Section 6 of the 10155/10158 packet. Seventh occurrence; first on canton 3.6.0-snapshot.20260916. |
| 10165 | All 35 tests pass; checkErrors WARN from sv1's HttpErrorHandler: `POST /api/sv/v0/onboard/sv/sequencer` (sv4's request) timed out after the 38 s `pekko.http.server.request-timeout`. sv1's handler was waiting for the sv4 sequencer-add topology tx, which all 4 SVs proposed at 13:16:26.5 but which was only sequenced at 13:17:16 because epoch 104 (the 1 -> 3 BFT step) had no strong quorum for 25 s and lasted 38 s. sv4's retry succeeded 23 s later. | 10094 / 10153 / 10161 family (new symptom) | Packet `10165-onboard-sv-sequencer-http-timeout-bft-1-to-3-stall.md` (section 8: NOT fixed in digital-asset/canton main as of the 2026-09-15.22 mirror); paste-ready umbrella issue text in `10165-issue-body.md` (10094/10153 closed as dups). Splice options: do not block the HTTP request on the topology wait (or a `custom-timeouts` entry for `onboardSvSequencer` in tests), or extend the existing `onboard/validator` timeout ignore to the sequencer endpoint. Quorum stall itself is Canton-side. |
| 10166 | WalletMintingDelegationTimeBasedIntegrationTest: same `advanceTime(PT25H)` -> `LOCAL_VERDICT_INACTIVE_CONTRACTS` on release-line-0.8.x. | 10154 (cn-test-failures 10060 / #7223) | Section 5 of the 10154 packet. #7261 still not backported (0.8.x tip ef2dc6d559). |
| 10111 | Earliest recorded ACS_COMMITMENT_MISMATCH sv1Participant vs aliceValidator after a multi-host step (ExpiryWithNoVettedAmuletVersionIntegrationTest), period (20:29:33.04, 20:30:00]. | parent of 10129 / 10146 / 10155 / 10158 / 10162 / 10164 | Section 7 of the 10155/10158 packet. Fix the multi-hosting suites. |
| 10010 | All 35 tests pass; checkErrors WARN: sv3's sequencer denied a P2P auth token by sv4 (`Member SEQ::sv3 access is disabled`) for 12 s. sv3 had been an active sequencer for 21 s; sv4 was stuck at its onboarding snapshot after the false off-boarding conclusion (10048 bug) and only learned of sv3 once catch-up state transfer kicked in. Shards (8)/(9) of the same run are the sv2 wedge. | 10048 family | Packet `10010-p2p-auth-token-denied-false-offboarding.md`. NOT a benign in-flight topology change; do not add the proposed ignore. Fixed by canton#35600 (3.5.17 / main 20260910+); release-line-0.8.0 (3.5.16) is no longer supported, so no supported line is exposed. |
| 10167 | All 26 tests pass; checkErrors WARN `ACS_COMMITMENT_MISMATCH` sv1Participant vs aliceValidator, period (15:29:24.16, 15:30:00], 2.7 s after ExpiryWithNoVettedAmuletVersionIntegrationTest's `Multi-host alice on sv1Participant`. | 10146 (10111 parent) | Section 8 of the 10155/10158 packet. Eighth occurrence. |
| 10169 | All 25 tests pass; checkErrors ERROR from sv1's ReceiveSvRewardCouponTrigger: `INTERPRETATION_UPGRADE_ERROR_TRANSLATION_FAILED`, DsoRulesConfig optional field 13 (SV operations switch-over times) is Some and cannot be dropped when the test downgrades the package config to dsoGovernance 0.1.25. | cn-test-failures 9965 | Packet `10169-unsupported-package-vetting-downgrade-translation-missing-backport.md`. FIXED on main by #7299 (d3499d1439, 2026-09-15); NOT on release-line-0.8.3 or 0.8.x. Backport branch `ray/backport-7299-release-line-0.8.3` (87d76de621). Deterministic on those lines. |
| 10170 | TrafficBasedRewardsTimeBasedIntegrationTest CIP-104: round 11 not opened within the 20 s check after a 10-minute sim-time jump; an SV app command was in flight, the sequencer dropped its response (MAX_SEQUENCING_TIME_EXCEEDED), sv1Participant's time proof carried the pre-jump time and it saw no further event, so the SV app's domain time stayed 10 min behind and automation paused until the 30 s wall-clock recovery. | 9740 (and the #5779 / cn-test-failures 8423 family) | Packet `10170-9740-round-opening-wait-after-time-jump.md`. Fix branch `ray/fix-round-opening-wait-budget` (9ac0f24ab3): 90 s budget in `advanceTimeAndWaitForRoundOpening`, matching #5779's fix of the sibling helper. |
| 9740 | Same test, same step, same message on main 2026-08-19 (canton 3.5.14 snapshot): identical mechanism from its artifact (blocked scheduler sc=1008, MAX_SEQUENCING_TIME_EXCEEDED, stale TimeProof, 10m 9.99 s delay). | parent of 10170 | Same packet. Not fixed on main; the fix branch above covers it. |
| 10171 | WalletMintingDelegationTimeBasedIntegrationTest: `advanceTime(PT25H)` -> `LOCAL_VERDICT_INACTIVE_CONTRACTS`, third hit, now on the 0.8.3 release commit. | 10154 (cn-test-failures 10060 / #7223) | Section 6 of the 10154 packet. Backport branch `ray/backport-7261-release-line-0.8.3`; release-line-0.8.x still needs #7261 as well. |
| 8784 | Venue participant submits OTCTrade_Settle before it has ingested the two AmuletAllocation contracts (CONTRACT_NOT_FOUND, per PR #6013). #6013 never merged; its own CI failed because its `awaitJava` predicate compares two unrelated codegen ContractId classes (`ContractId.equals` requires assignable classes), so the wait timed out although the venue had the contract 0.8 s earlier. Race still present on main. | - | Packet `8784-venue-allocation-contract-not-found.md`. Fix branch `ray/fix-venue-allocation-wait` (47c1b9f7e3): shared `waitForAllocationsOnParticipant` comparing ids by value, used in both tests. |
| 10172 | deployment_test: multi-arch image check reports `ubuntu:24.04@sha256:440dcf...` (splice-debug) as not multi-arch; skopeo succeeded but returned a manifest without a `manifests` list for a digest that is a 12-entry OCI index (unchanged since 2025-07); no stderr detail, 16 other images passed. | 10150 (same check, second failure mode) | Packet `10172-multiarch-check-non-index-response.md`. Fix branch `ray/fix-multiarch-check-retry` (4dc7d6c36d): retry inspects, print the manifest shape. |
| 10173 | UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest: after `advanceTime(37 h)` the round automation has ~220 rounds of backlog; the next `advanceRoundsToNextRoundOpening` sees (7, 7, 8, 9) instead of (6, 6, 7, 8) and fails its 90 s check. | splice #7206 (open, same test, same mechanism); sibling of 10060/#7261 | Packet `10173-unhide-expire-coupon-time-jump-backlog.md`. Fix branch `ray/fix-10173-unhide-expire-coupon-ttl` (77c50439a9): coupon TTL 2 h via initialRewardConfig, advance past it with advanceRoundsUntil. |
| 10174 | LsuIntegrationTest passes; checkErrors WARN from bobValidatorLocal: `Connection for 'global' with psid ...::36-0 is no longer active (status: LSU source), skipping update` (the branch #7311 added for 10088-B). #7311's own ignore pattern has unescaped parentheses and never matches. bob was restarted after the upgrade and its init used the participant's stale registered psid. | successor of 10088-B (#7311) | Packet `10174-lsu-source-warn-ignore-regex.md`. Fix branch `ray/fix-10174-lsu-source-ignore-regex` (8cdbe8996b): escape the parentheses; ripgrep-verified. Validator-side psid selection after LSU left as a note. |
| 10175 | UnclaimedActivityRecordIntegrationTest "An UnclaimedActivityRecord gets expired": `pause().futureValue` on alice's CollectRewardsAndMergeAmuletsTrigger times out after 5 s. Block 1 took 10.6 s (actAndCheck 5 s poll cap), the record (expiresAt = now+10 s) had expired, the one-instant resume between the blocks let the trigger start a task on the expired record, Daml deadline-exceeded, infinite retries inside the task. | recurrence of 7864 (#5176 widened 5 s -> 10 s) | Packet `10175-unclaimed-activity-record-merge-trigger-pause-timeout.md`. Fix branch `ray/fix-10175-unclaimed-activity-record-keep-merge-paused` (a107408328): keep the merge trigger paused across both blocks; scalafmt only. App note: wallet does not filter expired UnclaimedActivityRecord inputs. |
| 10176 | SvInitializationIntegrationTest "SV apps can start one by one" passes its body, then `UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` times out (5 s) pausing sv2Scan's AcsSnapshotTrigger at 18:30:47.179: the trigger's `UpdateIncrementalSnapshotTask` had finished its SQL at 18:30:46.901 but the transaction only completed at 18:30:57.568 (10.67 s). The plugin hook runs outside the `try` in vendored `EnvironmentSetup.manualDestroyEnvironment`, so `environment.close()` is skipped, Prometheus :25000 stays bound, and 12 more tests plus 2 shared-environment suites fail at `Creating fixture` (`Could not create Prometheus HTTP server`). Commit latency was an outlier for the whole shard (p99 350 ms, six commits over 1 s vs 10-30 ms p99 elsewhere). | H3 sibling of 10175 (different cause); cascade shape of run 28921009132 (July, SvOnboardingViaNonFoundingSv) | Packet `10176-sanity-check-pause-timeout-teardown-cascade.md`. Fix branch `ray/fix-10176-sanity-check-pause-timeout` (e59f6538c0): `setTriggersWithin` takes a `pauseTimeout`, the plugin passes 1 minute. Canton-side: run `beforeEnvironmentDestroyed` inside the `try` so a failing check cannot leak the environment (described, not written). |

## Cross-cutting observations

- Three of the four release-line-0.8.x failures (10154, 10156, 10157-A) are main fixes merged 2026-09-15/16
  that were not backported: #7261, #7305, #7304. Worth a single backport PR.
- 10155 and 10158 bring the (sv1Participant, aliceValidator) ACS mismatch after a multi-host step to five
  recorded occurrences; every one follows `Multi-host alice on sv1Participant` by 1-15 s. The test fix
  proposed in 10146 is the only open action.
- 10153 shows the 10094 quorum-loss mechanism is not specific to canton 3.5: same epoch shape (1 -> 4 step,
  newcomers unauthenticated, sv1 blacklisted 3 epochs) on the 20260910 3.6 snapshot.
- Main moved to canton 3.6.0-snapshot.20260916.20284.0.vf27c4824 (#7364) on 2026-09-17; 10164 and 10165 are the
  first triaged runs on it. Both the multi-host ACS mismatch (test issue) and the BFT onboarding-step stall (10165,
  consensus/iss classes unchanged from 20260910) are still present on it.
- 10048 follow-up (Canton reply 2026-09-17): the #35600 state-transfer fix is present in the jars main
  (20260910 snapshot), release-line-0.8.x and 0.8.1 run, verified by string markers absent in 3.5.16 and the
  20260909.20244 snapshot; release-line-0.8.0 still pins 3.5.16. Details appended to `10048-bft-deadlock.md`.
- 10176 and the July run 28921009132 share the teardown-leak cascade: any exception from a plugin's
  `beforeEnvironmentDestroyed` skips `environment.close()` in vendored `EnvironmentSetup.manualDestroyEnvironment`
  (canton main unchanged as of 2026-09-15.22), so one teardown hiccup costs the rest of the shard. Worth an
  upstream one-line fix independent of the individual plugin causes.

## Fix branches written 2026-09-17 (sandbox only, unpushed; compile/tests to be run on the host)

| Branch | Commit | Fixes | Verified here |
|--------|--------|-------|---------------|
| `ray/fix-bulk-storage-test-stubbing-race` | 63359204ae | 10149 | apps-scan Test/compile, scalafmtCheck, 6/6 green runs of BulkStorageCommitFromStagingTest |
| `ray/fix-reset-topology-plugin-no-exit` | 743babe3f0 | 10137/10139 evidence loss (plugin `sys.exit`): reset failure now aborts the suite after `environment.close()` via `afterEnvironmentDestroyed`, and every later environment creation in the JVM is refused with `TopologyStateNotReset` | apps-app Test/compile, scalafmtCheck; no runtime run |
| `ray/fix-topology-init-limit` | 72dc373508 | 10140: `sequencers.conf` DownloadTopologyStateForInit(Hash) limit 3 -> 7 (no test references the value) | config only |
| `ray/fix-round-opening-wait-budget` | 9ac0f24ab3 | 9740 / 10170: `advanceTimeAndWaitForRoundOpening` gets the 90 s budget #5779 gave the sibling helper | scalafmt only; NOT compiled/run |
| `ray/backport-7261-release-line-0.8.3` | 5e0ac85796 | 10154/10166/10171: `cherry-pick -x -s 0c43730f70` (#7261) onto origin/release-line-0.8.3; one context conflict resolved (main has an extra svRewardWeight line) | cherry-pick + scalafmtCheck |
| `ray/backport-7299-release-line-0.8.3` | 87d76de621 | 10169: clean `cherry-pick -x -s` of #7299 onto origin/release-line-0.8.3 (0.8.x needs the same) | cherry-pick only |
| `ray/fix-venue-allocation-wait` | 47c1b9f7e3 | 8784: venue participant waits for both AmuletAllocation contracts (id compared by value) before OTCTrade_Settle, in TokenStandardAllocationIntegrationTest and TrafficBasedRewardsTimeBasedIntegrationTest | scalafmt only; NOT compiled/run |
| `ray/fix-auth0-relogin-retry` | c21d8246fb | 10143: the second login in WalletAuth0FrontendIntegrationTest goes through completeAuth0LoginWithAuthorization (1 min retry with Auth0 cookie/storage reset and re-navigation to /confirm-payment) instead of a single unretried loginViaAuth0InCurrentPage | scalafmt only; NOT run (needs Auth0 credentials) |
| `ray/fix-multiarch-check-retry` | 4dc7d6c36d | 10150 / 10172: `check-multiarch-images.py` retries skopeo 3x and reports the manifest shape on a non-index answer | py_compile + 5-case harness with a fake skopeo (success, 2x502 then success, bad JSON then success, 3x single-arch, 3x502); no real skopeo, no formatter |
| `ray/fix-10173-unhide-expire-coupon-ttl` | 77c50439a9 | 10173 / #7206: 2 h reward coupon TTL in the test config, both 37 h jumps replaced by advanceRoundsUntil | scalafmt only; NOT compiled/run |
| `ray/fix-10174-lsu-source-ignore-regex` | 8cdbe8996b | 10174: #7311's ignore pattern for the LSU-source WARN escaped so it matches | rg against the run's line: old 0, new 1 |
| `ray/fix-10175-unclaimed-activity-record-keep-merge-paused` | a107408328 | 10175: outer pause of alice's merge trigger across both blocks of the expiry test | apps-app/Test/scalafmtCheck |
| `ray/fix-sv-ui-test-timeouts` | 26ad84f42d | 10141 (await the `waitFor`, drop the 1000 ms override) and 10145 (`navigateToLegacyGovernancePage` findByText with the 15 s vitest budget) | prettier --check clean; vitest not run |
| `ray/fix-multihost-acs-mismatch` | 5e4f9464cd | 10111 family (10129, 10146, 10155, 10158, 10162, 10164, 10167): new `WalletTestUtil.onboardWalletUserHostedAlsoOn` allocates alice's wallet party hosted on both her participant and sv1Participant before she owns any contract, then onboards it as the wallet user; the expiry base suite and AutoIgnoreUnresponsivePartiesIntegrationTest use it instead of `propose_delta` multi-hosting after onboarding | scalafmt only; NOT compiled, NOT run (host) |
| `ray/fix-10176-sanity-check-pause-timeout` | e59f6538c0 | 10176: `TriggerTestUtil.setTriggersWithin` gets a `pauseTimeout` (default unchanged), `UpdateHistorySanityCheckPlugin` pauses the snapshot triggers with a 1 min budget | apps-app/Test/scalafmtCheck (see packet); NOT compiled/run |
