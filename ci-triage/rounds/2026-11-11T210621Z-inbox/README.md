# Round 2026-11-11T210621Z-inbox

Inbox export 2026-11-11 21:05 UTC (`export/inbox.jsonl`), 24 items: 22 new (11357-11378 without 11359) plus the
leftovers 10479 (needs-log, cni GHA) and 10919 (owner question). 11354 is triaged in round
`2026-11-11T074142Z-inbox` (g8) and presented from there.

Access: cn-test-failures and splice via gh api (sandbox token); canton-network-internal not readable (log requests).
Job logs: `log/inbox-1111b/<job id>.log` (gitignored); item to job map `log/inbox-1111b/jobs.tsv`.

Context: PR splice#8432 (featured app config update triggers) merged 16:02 UTC as 3b5b82822d with a versionGuard
(supportsTbarV1_1) on the new SvDsoStore filter; its PR runs failed on the minimum package config (11359). Runs at
or after 3b5b828: 1ee8c88, 08774bc, 915616a, ee8772c (also Canton bump to 3.7.3-snapshot.20261111, #8441).

## Queue

| Group | Refs | Signature | Suspected | Rank, why | State |
|---|---|---|---|---|---|
| g1-simtime-init | 11358 11362 11368 11374 | simtime (3) / (2) / (5) / (0) on 4 main runs (2d54198, 2fc780a, 1ee8c88, 915616a): several suites abort at `Creating environment failed at step Running setup` / `Condition never became true within 5 minutes`, then cascade | AM (slow SV init), H cascade; common cause? | 1: 4 of today's main runs, several suites per shard | open |
| g2-rflsu | 11371 11372 | roll-forward-lsu (0), two consecutive main runs (08774bc, 915616a): `roll forward LSU DR *** FAILED ***`, `Condition never became true within 5 minutes`, then `ShutdownFailedException` | unknown; both after #8432 merge | 2: two consecutive main runs | open |
| g3-init-wct | 11363 11373 | key-download (0) `reonboard SV ... *** FAILED ***` 5 min (11347 shape); frontend-wall-clock-time (2) suites abort at Running setup, 5 min | 11347 (AM + H), related to g1? | 3: init timeouts, same theme as g1 | open |
| g4-bootstrap | 11360 | resource-intensive (0) (2fc780a, before #8432): `Bootstrap with specific versions and then upgrade to latest *** FAILED ***` TestFailedException | unknown (not the #8432 store filter: sha predates it) | 4 | open |
| g5-wct | 11364 11375 11376 | wall-clock-time (5): checkErrors SvOnboardingAddlIntegrationTest GetConnectedSynchronizers / DomainIngestionService INTERNAL; wall-clock-time (5) `auto-expire payment requests`; wall-clock-time (9) `Multiple SvRewardStates for the same SV get merged` (11375 11376 on the Canton bump run ee8772c) | unknown, independent | 5: first hits | open |
| g6-fe | 11361 | frontend-wall-clock-time (0): `allow requesting an entry with subscription payments and then list it`, StaleElementReferenceException | F? | 6 | open |
| g7-misc | 11357 11365 11366 11367 | 11357 AF circuit breaker (ScanNewNetworkBackfilling, fix #8420 still open); 11365 release-line-0.9.4 wall-clock-time (5) in a cancelled run; 11366 docs `socket hang up`; 11367 docker-no-canton (0) setup step `Exceeded maximum retries (6 / 5)` | AF dup 11147; cancelled; infra | 7: mostly duplicates and infra | open |
| g8-cni | 11369 11370 11377 11378 | canton-network-internal: artifacts_push; backup_status_cluster; backup_cluster (two issues, one run 43074272995) | - | log request (no access) | open |
| - | 10479 | cni Purge Unused Databases | - | needs-log since 10-21, re-list | open |
| - | 10919 | ExpiryWithIgnoredAmuletVersion DB lock | - | owner question, re-list | open |

## Landed (agents finished 2026-11-11 ~22:30 UTC; not yet verified against the logs or presented)

| Group | Verdict | Suggested action | State |
|---|---|---|---|
| g1-simtime-init | 11358 11362 11368 11374 dup 11255 (1 -> 4 topology step under simtime: mediator-add signatures rejected, joining SV waits for mediator traffic; skipped reset fails later suites) | 4 closes, one-line comment each | open |
| g2-rflsu | 11371 11372 dup 11303 (AK pre-DR 300 s sequencing cutoff); not #8432, not hard broken (4 of 34 jobs) | close dups, comment 11303 (comments/11303.md), assignee rautenrieth-da suggested (#8234) | open |
| g3-init-wct | 11363 new: re-onboarded sv4 sequencer, peers keep old sequencer id on reused P2P endpoint (Canton BFT, like 10011); 11373 new: sv3 init > 5 min on slow runner (AM shape) + B blast radius | comments, retitles, Triaged; 11363 assignee martinflorian-da suggested | open |
| g4-bootstrap | 11360 dup 11313 (L, close vote after DAR upload burst, sv1 sequencer blacklisted; fix PR #8412 open) | close dup | open |
| g5-wct | 11364 dup 9445 (client echo of GetConnectedSynchronizers INTERNAL); 11375 new infra (4 min slowdown in two pods); 11376 dup 11375 | close 11364, 11376; comment/retitle/Triaged 11375 | open |
| g6-fe | 11361 dup 11187 (F, ANS search button stale; fix PR #8418 open) | close dup | open |
| g7-misc | 11357 dup 11147 (AF); 11365 dup 10358 (release-line cold compile, #8319 not backported: owner question); 11366 11367 new infra (runner network 16:05-16:24) | closes; comment + close 11366 11367 | open |

Family letters: AV goes to 11354 (previous round g8); g3 proposed AV for 11363, use AW when merging.
