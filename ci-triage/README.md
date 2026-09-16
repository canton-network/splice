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

## Cross-cutting observations

- `sys.exit(1)` in ResetTopologyStatePlugin (ResetDecentralizedNamespace) destroyed the evidence in both
  10137 and 10139: no ScalaTest summary, no junit XML, checkErrors skipped. Same pattern noted for the
  wall-clock shards in the 2026-09-08 triage. Making the plugin fail the suite instead is a cheap,
  independent win.
- Three of seven (10140, 10142, plus 10121/10094/10084 from earlier) are "all tests pass, one log line
  fails checkErrors". 10142 is the only one with a fix already written.
- 10137 confirms the BFT onboarding wedge family is still live on release-line-0.8.0 (canton 3.5.16).
- Runtime canton versions: 10136/10137 (release-line-0.8.x) 3.5.16; 10139/10140/10141/10142/10143 (main)
  3.6.0-snapshot.20260910.20260.0.v90621933. `canton/VERSION` says 3.5.7-SNAPSHOT at all these shas.
