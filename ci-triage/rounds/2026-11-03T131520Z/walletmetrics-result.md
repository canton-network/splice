# Group walletmetrics (11143)

Verdict: 11143 is a duplicate of open #10732 (second hit, identical `Found 2 matching metrics` for
`trigger.completed` / DomainIngestionService). Mechanism, new: `trigger.completed` has one series per `outcome`;
the per-party DomainIngestionService (quiet PeriodicTaskTrigger, 30 s polling, jitter 0.2) marks `success` on its
first poll and a silent `noop` on the next one, so the test's lookup becomes ambiguous when the check comes more
than 24 s after the first poll (34.6 s here, 32.3 s in #10732) and never recovers. Test bug.
Packet: `evidence/11143-wallet-metrics-trigger-completed-noop-series.md`.

Test fix (not written): `apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletMetricsTest.scala`
lines 133-136, add `"outcome" -> "success"` to the label map of the `trigger.completed` lookup.

## INDEX.tsv rows

`walletmetrics-index-row.tsv` (11143). Suggested update of the 10732 row: dup_of `new (AC)`, status
`open; test fix: add outcome -> success to the lookup labels`, file add `evidence/11143-wallet-metrics-trigger-completed-noop-series.md`.

## known-families.md

Index row:
```
| AC | WalletMetricsTest trigger.completed lookup matches success + noop series | 10732 (dup 11143) | open, test fix proposed |
```
Section:
```
## AC. WalletMetricsTest: trigger.completed lookup matches 2 series (success + noop)
- Signature (canton_network_test.clog, ERROR): `Found 2 matching metrics` followed by `Failed clue: DomainIngestionService
  should report task completions correctly`, for 20 s; job log shows only `CommandFailure: Command execution failed.`
  in "User wallet automation metrics should are labeled with the party ID of the wallet user".
- Confirm: the tc1 DomainIngestionService `Completed processing` line and the first `Running clue:
  DomainIngestionService should report task completions` are more than 24 s apart.
- Mechanism: `trigger.completed` is labeled with `outcome` (TaskbasedTrigger.scala:188-241); the quiet per-party
  DomainIngestionService marks `success` on the first poll and a silent `noop` 24-36 s later; `metrics.get` needs one
  series. Test bug.
- Parent 10732 (run 40672823965); dup 11143 (run 41481391794).
- Fix (test, not written): add `"outcome" -> "success"` to the label map at WalletMetricsTest.scala:133-136.
```

## Issue commands

Status of 10732 is not Inbox (not in the export); no set_status for it. Commands as for a host terminal with direnv:
```
ci-triage/scripts/close_duplicates.py 10732 11143 --comment 'Duplicate of #10732: same Found 2 matching metrics for trigger.completed of DomainIngestionService in WalletMetricsTest (run 41481391794, wall-clock-time (3), main 233250e5a9). Packet: ci-triage/evidence/11143-wallet-metrics-trigger-completed-noop-series.md on the triage branch.'
gh issue comment 10732 -R DACH-NY/cn-test-failures --body 'Second hit: #11143 (2026-11-02, main 233250e5a9). Mechanism: trigger.completed has one series per outcome label (TaskbasedTrigger.scala). The per-party DomainIngestionService is a quiet periodic trigger (30 s, jitter 0.2): its first poll marks outcome=success, the next poll finds nothing and silently marks outcome=noop. The test checks 32 s (here) and 35 s (#11143) after the first poll, so metrics.get finds 2 series and the eventually cannot recover. Test fix: add outcome -> success to the label map of the trigger.completed lookup in WalletMetricsTest.scala lines 133-136. Packet: ci-triage/evidence/11143-wallet-metrics-trigger-completed-noop-series.md on the triage branch.'
gh issue edit 10732 -R DACH-NY/cn-test-failures --title 'WalletMetricsTest: trigger.completed lookup matches the success and the noop series of DomainIngestionService'
```
