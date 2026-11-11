# Round 2026-11-11T074142Z-inbox

Inbox export 2026-11-11 07:37 UTC, 11 items (newest issue 11354, 07:21, is in the export). New since the last
round: 11346-11354. Leftovers: 10479 (needs-log, cni GHA), 10919 (owner question).

Access: cn-test-failures and splice via gh api (sandbox token); no ghx (router blocked by sandbox network policy);
canton-network-internal and CircleCI only as user downloads.

Job logs of the GHA jobs: `log/inbox-1111/<job id>.log` (gitignored).

## Queue

| Group | Refs | Signature | Suspected | Rank, why | State |
|---|---|---|---|---|---|
| g1-perf | 11351 11352 | Daily store perf (run 42954358024): read check `KeyError: no thresholds configured for 'UpdateHistoryReadPerformanceTest-serializeJson'`; ingestion job shows success | sibling of 11280 / 11321 11322 (missing threshold rule) | 1: daily, red 11-09, 11-10, 11-11 | open |
| g2-lsuop | 11349 | CircleCI deploy_lsu_operator, build/ciperiodic-lsu, job 531054 | unknown | 2: deployment, first hit; log request | open |
| g3-far | 11353 | mainnet-Daml workflow (run 42963305445) frontend-wall-clock-time (2): `Grant, Update, Amend and Revoke Featured App Right *** FAILED ***`, run sha 9dd892f = #8062 (that UI) | Y (test needs newer Daml than mainnet)? | 3: nightly, possibly broken since #8062 | open |
| g4-keydl | 11347 | key-download (0) (run 42923829975): 6 re-onboard / identities-dump tests FAILED | unknown | 4: several tests in one shard | open |
| g5-simtime | 11348 | docker-canton-simtime (0) (run 42923829975): checkErrors, `Circuit breaker medium-priority-connection tripped` in ScanNewNetworkBackfillingTimeBasedIntegrationTest | AF (11147, fix PR #8420) | 5: frequent flake, fix PR open | open |
| g6-restart | 11346 | wall-clock-time (5) (run 42914678423): `survive Canton restarts *** FAILED ***` | unknown | 6: flake, first hit? | open |
| g7-vsc | 11350 | wall-clock-time (7) (run 42948633392 attempt 1, rerun passed): checkErrors, `onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds` in ValidatorSequencerConnectionIntegrationTest | 11255 (BFT topology timeouts) | 7: flake | open |
| g8-lsu | 11354 | logical-sync-upgrade (0) (run 42975175376): checkErrors, `Consensus not reached.` WARN from BftScanConnection in LsuIntegrationTest | AB-like (WARN after suppression)? | 7: flake | open |
| - | 10479 | cni Purge Unused Databases | - | needs-log since 10-21, re-list | open |
| - | 10919 | ExpiryWithIgnoredAmuletVersion DB lock | - | owner question, re-list | open |
