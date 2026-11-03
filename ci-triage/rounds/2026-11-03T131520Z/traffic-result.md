# Group traffic: 11144

Verdict: new family AD (provisional letter), test-side race, first hit. Packet
`evidence/11144-tbar-round-totals-out-of-order.md`. sv1Scan computes the backlog rounds 3 to 10 in parallel after the
test resumes verdict ingestion; round 10 committed at 17:57:27.433, round 4 at .510; the test waits for round 10
only and read round 4 at .495 (Undetermined). Fix: in `assertRewardCalcs` (TrafficBasedRewardsTimeBasedIntegrationTest.scala
lines 456 to 462) wait for every round 0 to 10, not round 10 only. No branch.

## INDEX.tsv row

```
11144	2026-11-03	41492215719	main 5fdbcdce19	125438719877 simtime (3)	3.7.1	TrafficBasedRewardsDryRunTimeBasedIntegrationTest CIP-104: `Round 4 should have totals` got Undetermined at 17:57:27.497; sv1Scan RewardComputationTrigger computed the backlog rounds 3-10 in parallel after the verdict-ingestion pause (all started 17:57:27.407), round 10 committed .433, round 4 only .510; the test waits for round 10 only, then reads 0-10 once	new (AD)	test race	open; test fix proposed (wait for all rounds 0-10 in assertRewardCalcs), no branch; comment, retitle, Triaged	evidence/11144-tbar-round-totals-out-of-order.md
```

## known-families.md

Index row:
```
| AD | TBAR test reads per-round totals while a parallel backlog computation is still committing | 11144 | open, test fix proposed |
```

Section:
```
## AD. TrafficBasedRewards test reads round totals before the parallel backlog computation committed them
- Signature (test failure): `RewardAccountingActivityTotalsUndetermined(...) Round <n> should have totals
  (TrafficBasedRewardsTimeBasedIntegrationTest.scala:467)`, n below 10.
- Confirm: `zcat canton_network_test.clog.gz | grep -a 'scan=sv1Scan' | grep -a -E 'RewardComputationTrigger|Computed and stored rewards for round'`
  shows rounds 3..10 started in the same millisecond and round n's `Computed and stored rewards` after round 10's;
  the test's GET `/rounds/<n>/activity-totals` lies in between.
- Mechanism: the test pauses sv1Scan verdict ingestion for rounds 3..10, so on resume RewardComputationTrigger
  (PollingParallelTaskExecutionTrigger) runs the whole backlog in parallel and finishes out of order; the test's
  `eventually` waits for round 10 only, then reads 0..10 once. Undetermined is scan's documented "retry" answer.
- Parent 11144 (run 41492215719, DryRun variant; applies to both variants via the shared base).
- Fix: test-side, `assertRewardCalcs` waits for all rounds 0..10 (totals and root hash) inside the `eventually`.
```

## Issue commands

```
gh issue comment 11144 -R DACH-NY/cn-test-failures --body 'Test race, first hit. The test pauses sv1Scan verdict ingestion for rounds 3 to 10; when it resumes, the RewardComputationTrigger of sv1Scan (a parallel polling trigger) starts rounds 3 to 10 at 17:57:27.407 and finishes them out of order: round 10 committed at .433, rounds 4 and 5 only at .510. assertRewardCalcs waits (eventually) only for round 10 to be Ok, then reads rounds 0 to 10 once; it read round 4 at .495 and got Undetermined, which scan documents as retry. Not a product bug. Test fix: in assertRewardCalcs (TrafficBasedRewardsTimeBasedIntegrationTest.scala lines 456 to 462) wait inside the eventually for the totals and root hash of every round 0 to 10, not only round 10. Unchanged on main 5c53b74294. Packet: ci-triage/evidence/11144-tbar-round-totals-out-of-order.md on the triage branch.'
gh issue edit 11144 -R DACH-NY/cn-test-failures --title 'TrafficBasedRewardsDryRunTimeBasedIntegrationTest: round totals read before the parallel backlog reward computation committed them'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11144
```
