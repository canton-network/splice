# Group scanmon (11158)

Verdict: 11158 duplicate of 11128 (family Z), test flake. Cursor stuck at 11:12:55.878973 for 21 s; private
verdict 11:12:56.208982 (330 ms later) ingested by sv1Scan, history only `@ None` after. No fix on main
(5c53b74294). Packet `evidence/11158-scan-party-monitor-events-cap-dup-11128.md`.

Commands (for the user):
```
ci-triage/scripts/close_duplicates.py 11128 11158 --comment 'Duplicate of #11128 (family Z): same assertion at ScanPartyMonitorScriptIntegrationTest.scala:273; the cursor of the script stays at the last DSO-visible update (11:12:55.878973) for 21 s while sv1Scan already has the private verdict at 11:12:56.208982, and the update history only advances with records not visible to the DSO party. Packet: ci-triage/evidence/11158-scan-party-monitor-events-cap-dup-11128.md on the triage branch.'
gh issue comment 11128 -R DACH-NY/cn-test-failures --body 'Fifth hit: #11158 (2026-11-03 11:13, post-merge main 6949aa0f3b), same cap mechanism, hidden verdict 330 ms after the last DSO-visible update. No fix on main yet (5c53b74294). Test fix still needed: cause a DSO-visible transaction (e.g. a tap) after createTransferOffer.'
```
