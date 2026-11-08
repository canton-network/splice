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
| g1-c218905 | 11241 11242 11248 | simtime app init fails (circuit breaker, SUBMISSION_ALREADY_IN_FLIGHT); CopyVotes circuit breaker WARN | B / BFT ordering | 1: new on main since the BFT switch, 4 of 7 post-merge runs | presented: dup 10165 (a) vs new parent 11255 (b) asked |
| g2-6f7a5f8 | 11255 11256 11257 | sv4Validator init fails; LSU test fails; onboard/sv/sequencer 38 s timeout WARN | B / BFT | 1: same | landed: 11255 new (topology outbox stuck at BFT 1->4, not blacklisting), 11256 dup 10729, 11257 dup 10165; quotes verified |
| g3-d8537ea | 11258 | SvAppReference.waitForInitialization 5 min, two suites aborted | B / BFT | 1: same | landed: new, verdict refused by blacklisted sv4 sequencer, simtime freeze (same mechanism as 11275); quotes verified |
| g4-mainnet | 11274 11275 11276 | nightly mainnet-Daml: sv1 init fails; reward and round tests fail | BFT or Daml version | 2: nightly, first failure after a pass | landed: 11274 new test bug (SpliceAmulet_0_1_25 tag on abstract base, #8224, verified at 0b2ccec235), 11275 new (lost verdict during blacklisting), 11276 dup 11275 |
| g6-11173 | 11173 | wall-clock-time (3), earlier round left open | sibling of 11240 (10165) | 3: older, likely dup | landed: dup of 10165 (B), flagged line verified |
| - | 11259 | canton-network-internal cluster_lock_reaper (scratchnet) | unknown | 2: daily infra job; log request | open |

Waiting from earlier rounds (re-list, not re-triaged): 11192, 11238, 11240 (actions handed out 2026-11-06),
10479, 10495, 10496, 10680, 10728, 10893, 10917, 10918, 10919, 11104, 11105, 11117, 11140, 11147 (see STATE.md).

## Cross-group picture (all agents landed)

Two simtime-fatal mechanisms at the first multi-SV sequencer onboarding of a Canton instance, both turned permanent
because nothing advances the sim clock during setup:
- M1, P2P authentication gap at the 1 -> 4 epoch (sends rejected `P2P connectivity is not ready` or batched and never
  ordered): 11241, 11242, 11255 (simtime); same trigger in wall clock 11248, 11257, 11173 (10165). Not fixed by #8356.
- M2, mediator verdict refused by the blacklisted sequencer, aggregation below threshold, decision timeout in sim time
  never fires, DSO participants stop indexing: 11258, 11275, 11276. Removed by #8356.
Other: 11274 test bug (#8224 tag on abstract base), 11256 dup 10729.

## 11274 (presented 2026-11-07)

Fix branch `rautenrieth-da/fix-11274-tbar-mainnet-tags` (c4391e6180, off main 0b2ccec235): tags moved from the
abstract base to both concrete TrafficBasedRewards classes. Checks: scalafmtOnly on the file, apps-app/Test/compile,
javap shows both tags on both concrete classes. Full lint OOM-killed in the sandbox (recipe 8); left to static_tests.
Action: comment, retitle, Triaged, assignee adetokunbo (#8224), PR closes it. User opened canton-network/splice#8362 (2026-11-07).

## BFT onboarding window: one parent (user decision 2026-11-08)

Parent 11255 (comment, retitle, Triaged); duplicates 11241 11242 11248 11257 11173 closed into it, even where the
shape differs (rejected send, lost batch, 1 -> 2 strong-quorum stall, newcomer segment stall). Correction to the
earlier chat claim: sequential onboarding removes the rejected and lost sends (11241 11242 11248 11255) but not the
newcomer catch-up stalls (11257 at 1 -> 2, 11173 sv4 segment). 11240 was handed out earlier as dup of 10165; left as is.
