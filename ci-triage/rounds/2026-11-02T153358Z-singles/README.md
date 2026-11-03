# Round 2026-11-02T153358Z-singles

Backfilled on 2026-11-03 from the chat of 2026-11-02 (rounds were not persisted yet). Scope: the four inbox items
not triaged by Raymond (10891, 11138, 11139, 11140); the other 15 inbox items are in `STATE.md`. Data source: host
exports (`inbox.jsonl` 2026-11-02 14:44, `issues-since-2026-10-19.jsonl` 15:19); no `ghx`. Three forks, one per
group; packets in `evidence/`, rows in `evidence/INDEX.tsv`, commit 96e1133a6e.

## Verdicts

| Ref | Verdict | Parent | Packet |
|---|---|---|---|
| 10891 | duplicate, family V: PR 8083 branch has #8017 but not the fix #8148 | 10921 (closed, fixed) | `evidence/10891-periodic-topology-snapshot-missing-scopes.md` |
| 11138 | duplicate, family Z | 11128 (open) | `evidence/11138-11139-scan-party-monitor-events-cap-dup-11128.md` |
| 11139 | duplicate, family Z | 11128 (open) | same |
| 11140 | new family AB: 60 s scan refresh WARN after the log suppression ended; test fix proposed, not written | - | `evidence/11140-bft-scan-refresh-warn-after-suppression.md` |

Needs an owner: family Z recurred 4 times on 2026-11-02 (11128, 11136, 11138, 11139); test fix (a DSO-visible
transaction after createTransferOffer) for stephencompall-DA. 11140 test fix in
`BftScanConnectionIntegrationTest.scala` (keep the suppression until aliceValidator stops).

## Commands handed out

Handed out in chat on 2026-11-02 as gh one-liners; rewritten here to the current scripts and quoting rule. Not
confirmed as run. All are safe to run again (closes skip closed issues, set_status skips other statuses).

```
direnv exec . ci-triage/scripts/close_duplicates.py 10921 10891 --comment 'Duplicate of #10921 (pekko-connectors-google-cloud-storage 1.3.0 reads the GCS service-account scopes at the top level). The observed run is on PR branch 9ff2637f41 (PR 8083), which contains #8017 but not the fix #8148 (merged to main 2026-10-30): the PeriodicTopologySnapshotTrigger of sv1 logs ConfigException Missing scopes at 01:30:42 UTC and never takes the snapshot the test waits for. Rebasing the branch onto main fixes it. Packet: ci-triage/evidence/10891-periodic-topology-snapshot-missing-scopes.md on the triage branch.'
direnv exec . ci-triage/scripts/close_duplicates.py 11128 11138 11139 --comment 'Duplicate of #11128 (family Z): same assertion at ScanPartyMonitorScriptIntegrationTest.scala:273; the cursor of the script stays at the last DSO-visible update for 20 s while scan already has the private verdict 1 microsecond later. Packet: ci-triage/evidence/11138-11139-scan-party-monitor-events-cap-dup-11128.md on the triage branch.'
gh issue comment 11128 -R DACH-NY/cn-test-failures --body 'Recurs often: 4 failures on 2026-11-02 alone (#11128 05:00 PG14 nightly, #11136 07:43, #11138 10:23, #11139 11:36 post-merge runs on main), same cap mechanism. In #11138 and #11139 the hidden verdict is only 1 microsecond after the last DSO-visible update. Test fix still needed: cause a DSO-visible transaction (e.g. a tap) after createTransferOffer.'
gh issue comment 11140 -R DACH-NY/cn-test-failures --body 'Tests passed, log check only. BftScanConnectionIntegrationTest init fast enough even if there are unavailable scans stops sv2Scan (:5112) on purpose and suppresses the Failed to connect to scan of Digital-Asset-Eng-2 WARN only around aliceValidator startSync and onboardUser. aliceValidator refreshes its scan list every 60 s (scansRefreshInterval in the test config): first refresh 12:14:04.066 (suppressed), test body done 12:14:50, teardown plugins (token-standard CLI sanity check) until 12:15:04, and the next refresh at 12:15:04.067 hit the still-stopped sv2Scan 13 ms before aliceValidator was stopped. Timing race, first hit. Test fix: keep the suppression until aliceValidator is stopped (or restart sv2Scan inside it); no ignore pattern. Packet: ci-triage/evidence/11140-bft-scan-refresh-warn-after-suppression.md on the triage branch.'
gh issue edit 11140 -R DACH-NY/cn-test-failures --title 'BftScanConnectionIntegrationTest: 60 s scan refresh WARNs about the deliberately stopped sv2Scan after the log suppression ended'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11140
```

## Open

- Whether the commands above ran: unknown. Check with a fresh export or `ghx` before the next round.
- The 15 other inbox items of 2026-11-02 (Raymond's verdicts, logs needed, causes not found): see `STATE.md`.
