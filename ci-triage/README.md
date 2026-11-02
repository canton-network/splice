# CI failure triage

Shared triage branch, never merged. Procedure: the `ci-triage` skill in `skills/ci-triage/` on this branch
(newer than the copy on main; install with `cp -r ci-triage/skills/ci-triage ~/.claude/skills/`).

- `HANDOVER.md`: current state, open inbox items and what each one needs next.
- `known-families.md`: flake family catalogue with confirming greps. Check it before any deep analysis.
- `<ref>-<slug>.md`: evidence packets from 2026-11-02 on (and Raymond's from 2026-09).
- `2026-10-27b/` ... `2026-11-02/`: Raymond's inbox rounds (packets, host commands, already run).
- `history/`: Raymond's index, handover and onboarding as of 2026-11-02, unchanged. The index
  (`history/README-2026-09-08_2026-11-02.md`) maps every ref triaged before 2026-11-02 to its run, job and verdict;
  grep it by ref.

Issue exports and manually downloaded logs go to `ci-triage/export/` (gitignored); in a clone-mode sandbox they
are read from `/run/sandbox/source/ci-triage/export/`. See the skill's Sources for private data.

## Ref -> run -> job mapping

| ref | run | branch sha (PR) | job id and name | canton pin |
|---|---|---|---|---|
| 10891 | [40713988598](https://github.com/canton-network/splice/actions/runs/40713988598) | is/deflake-bulk-storage-consensus-test 9ff2637f41 (PR 8083) | 123197207725 wall-clock-time (9), attempt 1 | 3.7.0-snapshot.20261027.20812.0.v1af931f4 |
| 11138 | [41428422164](https://github.com/canton-network/splice/actions/runs/41428422164) | main 288c61b7e8 (#8042) | 125221165964 `ci / scala_test_wall_clock_time / wall-clock-time (0)` | 3.7.1 |
| 11139 | [41438225454](https://github.com/canton-network/splice/actions/runs/41438225454) | main b2b57fc37b (#8174) | 125253822686 `ci / scala_test_wall_clock_time / wall-clock-time (8)` | 3.7.1 |
| 11140 | [41441330497](https://github.com/canton-network/splice/actions/runs/41441330497) | main 1938e4170b (#8014) | 125264181558 `wall-clock-time (9)` | 3.7.1 |

## Overview

| ref | failure (one line, with the key timestamps) | duplicate of | packet + resolution/status |
|---|---|---|---|
| 10891 | PeriodicTopologySnapshotIntegrationTest:57 no snapshot line; sv1 trigger WARN 01:30:42 `ConfigException$Missing 'scopes'` (PR branch has #8017, lacks #8148) | 10921 (V) | `10891-periodic-topology-snapshot-missing-scopes.md`; close as dup, fixed on main by #8148 |
| 11138 | ScanPartyMonitorScriptIntegrationTest.scala:273 no "confirming"; script cursor stuck at 10:59:17.853991 for 20 s, private verdict at .853992, history only `@ None` after | 11128 (Z) | `11138-11139-scan-party-monitor-events-cap-dup-11128.md`; close as dup |
| 11139 | same assertion; cursor stuck at 12:13:10.194132 for 21 s, private verdict at .194133 | 11128 (Z) | `11138-11139-scan-party-monitor-events-cap-dup-11128.md`; close as dup |
| 11140 | BftScanConnectionIntegrationTest "init fast enough even if there are unavailable scans": aliceValidator's 60 s scan refresh at 12:15:04.067 WARNs about the deliberately stopped sv2Scan (:5112), after the suppression ended (body done 12:14:50, teardown plugins until 12:15:04.085) | new (AB) | `11140-bft-scan-refresh-warn-after-suppression.md`; test fix proposed, not written |

## Fix branches

| branch | commit | fixes | verified here |
|---|---|---|---|

## Cross-cutting notes

- 2026-11-02: family Z failed 4 times in one day (11128, 11136, 11138, 11139), all post-merge or nightly on main;
  the test fix (a DSO-visible transaction after createTransferOffer) is the most valuable open item of the round.
