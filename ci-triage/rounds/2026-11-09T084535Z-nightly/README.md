# Round 2026-11-09T084535Z-nightly

Inbox export 2026-11-09 08:40 UTC, 32 items. New since the last round: 11231, 11277, 11278, 11279, 11280, 11281,
11282, 11283, 11284, 11285. The mainnet-Daml nightly failed on 0b2ccec235 three nights in a row (11-07, 11-08,
11-09; main did not move over the weekend); 11-05 and 11-06 passed. PR canton-network/splice#8362 (fix for 11274)
still open.

Access: cn-test-failures and splice via gh api (sandbox token); canton-network-internal and CircleCI only as user downloads.

## Queue

| Group | Refs | Signature | Suspected | Rank, why | State |
|---|---|---|---|---|---|
| g1-n1108 | 11277 11278 11279 | mainnet nightly 11-08 (run 42449239264): simtime (5), simtime (3), docker-no-canton (0) | 11274 tag bug, 11275 M2 | 1: nightly red 3 nights on the same sha | open |
| g2-n1109 | 11281 11282 11283 11284 | mainnet nightly 11-09 (run 42565193320): simtime (5), (3), (0), (1) | same | 1: same | open |
| g3-ingest | 11280 | Daily store performance tests, Ingestion Tests (run 42557724754) | unknown | 2: daily, first failure after 6 passes | open |
| g4-rl094 | 11231 | wall-clock-time (4) on release-line-0.9.4 (run 42082967872) | unknown | 3: release line, single hit | open |
| - | 11285 | CircleCI reset_validator_runbook_devnet, release-line-0.10.1 | sibling of 10680 | 2: daily deploy; log request | open |

Waiting from earlier rounds (re-list, not re-triaged): 11238, 11256, 11258, 11274, 11275, 11276 (round
2026-11-07T155023Z-bft, actions not run; INDEX.tsv rows for 11256 11258 11274 11275 11276 not merged yet),
11259 and 11194 (log requests), and the STATE.md list.
