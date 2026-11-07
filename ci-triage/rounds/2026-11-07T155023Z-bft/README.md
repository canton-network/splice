# Round 2026-11-07T155023Z-bft

Inbox export 2026-11-07 15:47 UTC, 29 items. New since the last round: 11241, 11242, 11248, 11255, 11256, 11257,
11258, 11259, 11274, 11275, 11276. All splice ones run on main after the DA BFT switch (splice#8234, merged
2026-11-06 13:34 UTC at b77153c).

Post-merge on main since b77153c: 4 of 7 runs failed (b77153c only publish_docs; c218905, 6f7a5f8, d8537ea in
scala_test), then 1add181, 4476e19, 0b2ccec passed all 10 simtime shards. The mainnet-Daml nightly on 0b2ccec
failed 3 simtime shards (the previous nightly passed).

Access: cn-test-failures and splice via gh api (sandbox token); canton-network-internal only as user downloads.

## Queue

| Group | Refs | Signature | Suspected | Rank, why | State |
|---|---|---|---|---|---|
| g1-c218905 | 11241 11242 11248 | simtime app init fails (circuit breaker, SUBMISSION_ALREADY_IN_FLIGHT); CopyVotes circuit breaker WARN | B / BFT ordering | 1: new on main since the BFT switch, 4 of 7 post-merge runs | open |
| g2-6f7a5f8 | 11255 11256 11257 | sv4Validator init fails; LSU test fails; onboard/sv/sequencer 38 s timeout WARN | B / BFT | 1: same | landed: 11255 new (topology outbox stuck at BFT 1->4, not blacklisting), 11256 dup 10729, 11257 dup 10165; quotes verified |
| g3-d8537ea | 11258 | SvAppReference.waitForInitialization 5 min, two suites aborted | B / BFT | 1: same | open |
| g4-mainnet | 11274 11275 11276 | nightly mainnet-Daml: sv1 init fails; reward and round tests fail | BFT or Daml version | 2: nightly, first failure after a pass | open |
| g6-11173 | 11173 | wall-clock-time (3), earlier round left open | sibling of 11240 (10165) | 3: older, likely dup | landed: dup of 10165 (B), flagged line verified |
| - | 11259 | canton-network-internal cluster_lock_reaper (scratchnet) | unknown | 2: daily infra job; log request | open |

Waiting from earlier rounds (re-list, not re-triaged): 11192, 11238, 11240 (actions handed out 2026-11-06),
10479, 10495, 10496, 10680, 10728, 10893, 10917, 10918, 10919, 11104, 11105, 11117, 11140, 11147 (see STATE.md).
