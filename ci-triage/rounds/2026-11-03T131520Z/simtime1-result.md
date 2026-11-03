# Group simtime1 (11163)

Verdict: duplicate of 10907, family U (third hit), with family H evidence loss. Packet:
`evidence/11163-sv2-join-import-acs-topology-offset.md`. INDEX row: `simtime1-index-row.tsv`.

Run 41637827972, job 125924096050 `simtime (1)`, main 5c53b74294 (#8202, not implicated). Suite
SvTimeBasedRewardCouponIntegrationTest, sv2 join: ImportPartyAcs refused with IMPORT_ACS_ERROR "last event is a
topology offset" at 13:11:32.295, SvApp init failed, NodeBase exited the sbt JVM; no ScalaTest summary,
SvTimeBasedRewardCoupon and SvTimeBasedBootstrappingRound lost. sv2 joined fine at 13:06:41 in an earlier suite.

## known-families.md, section U (occurrence note, append)

```
- Third hit 11163 (2026-11-03, main 5c53b74294, simtime (1), run 41637827972): sv2 in
  SvTimeBasedRewardCouponIntegrationTest env start, 13:11:32.295; offset 856 (TopologyTransactionEffective) was the
  last indexed event before the disconnect; the same sv2 join passed at 13:06:41 in TimeBasedTestNetPreview (race).
  Also 11107 (dup). Fix direction: in JoiningNodeDsoPartyHosting retry the import after reconnect and ledger end moving
  past the topology offset (ImportPartyAcsRequest has no force_repair_when_topology_transaction_at_ledger_end).
```

Index row update: `| U | joining SV ACS import refused, topology offset (Canton 3.7) | 10907 (dups 11107, 11163) | open, 3 hits, owner needed |`

## Issue commands (for the user, host)

```
direnv exec . ci-triage/scripts/close_duplicates.py 10907 11163 --comment 'Duplicate of #10907 (family U). SvTimeBasedRewardCouponIntegrationTest environment start: sv2 joining the DSO disconnected its participant and called ImportPartyAcs, which sv2Participant refused with IMPORT_ACS_ERROR Cannot apply a repair command as the last event is a topology offset (13:11:32.295). The SV app then exited the sbt JVM, so the job shows no test summary and two suites of the shard never ran. Not related to #8202. Packet: ci-triage/evidence/11163-sv2-join-import-acs-topology-offset.md on the triage branch.'
gh issue comment 10907 -R DACH-NY/cn-test-failures --body 'Third hit: #11163 (2026-11-03, main 5c53b74294, simtime (1)), this time sv2 in SvTimeBasedRewardCouponIntegrationTest. The last indexed event of sv2Participant before the disconnect was a TopologyTransactionEffective; the same sv2 join passed 5 minutes earlier in another suite, so it is a timing race. Suggested fix: in JoiningNodeDsoPartyHosting, on this error reconnect, wait until the ledger end moves past the topology offset, disconnect and retry the import (ImportPartyAcsRequest has no force flag for this check). The SV app exit also loses the rest of the shard (family H).'
```
