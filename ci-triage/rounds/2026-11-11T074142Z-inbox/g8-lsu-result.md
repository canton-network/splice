# g8-lsu: 11354

Verdict: new (proposed family AU), test race in LsuIntegrationTest. Packet:
`evidence/11354-lsu-validators-outlive-scan-restart.md`. Comment body: `comments/11354.md`.

Second test restarts all SV/scan nodes but not aliceValidator/splitwellValidator from test 1; their trigger BFT
scan calls (2 of 3) exhaust 12 client-call retries (07:12:04.088 to 07:12:11.010) while the scans are down
(stop 07:12:03.060; sv1Scan HTTP unbind 5.03 s; second healthy scan 07:12:10.925) and log `Consensus not reached.`
at WARN. Race since #6503 (2026-07-23). Fix: stop the two validators before the restart. Assignee nicu-da (#6503,
#4716).

## INDEX.tsv row

11354	2026-11-11	42975175376	main b350273 (#8369)	130372803034 logical-sync-upgrade (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	LsuIntegrationTest test 2 restarts SV/scan nodes but not alice/splitwell validators; their trigger BFT scan calls give up after 12 retries 07:12:04-07:12:11 while scans are down (sv1Scan unbind 5 s), 3x Consensus not reached WARN	new (AU)	test race	Triaged suggested, assignee nicu-da; fix: stop validators before restart	evidence/11354-lsu-validators-outlive-scan-restart.md

## known-families.md

Index row:
| AU | LsuIntegrationTest test 2 restarts SV and scan nodes while test-1 validators keep calling scans; BFT call retries (12, about 7 s) run out | 11354 | open, test fix proposed |

Section:
### AU. Validators outlive a scan restart in LsuIntegrationTest
Signature: checkErrors only, `"message":"Consensus not reached."` WARN from `BftScanConnection:LsuIntegrationTest/.../validator=(aliceValidator|splitwellValidator)`, span `ValidatorPackageVettingTrigger-work` or `RollForwardLsuTrigger-work`, a few seconds after `Starting 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'`.
Confirming grep: `zcat canton_network_test.clog.gz | grep -a -E 'Stopping node sv1Scan"|Total maximum number of retries 12 exceeded|"Consensus not reached."'` shows the stop, then give-ups within about 8 s.
Mechanism: test 2 does `allNodes.par.foreach(_.stop())` (scans, SVs, SV validators) and `initDso`; aliceValidator and splitwellValidator from test 1 are not in `allNodes`. Scans refuse connections while stopped and answer 404 on all routes while initializing; the BFT client-call retry budget (12 retries, about 7 s) runs out if the second scan is not healthy within that time (11354: sv1Scan HTTP unbind 5.03 s).
Parent: 11354. Fix state: open; stop the two validators before the restart (test).

## Lesson candidate

Title: Restarting a dependency? Stop its clients first.
Rule: when a test restarts servers (e.g. to clear caches), any client node left running from an earlier test case keeps calling them, and its bounded retries (seconds) can run out before the servers are back; the give-up is logged at WARN and fails the log check even though the test passes. Shared-environment suites make this easy to miss because the client was started by a different test case.
Do: stop the clients before the servers and start them after, in the same order teardown uses:
```
stopAllAsync(clients*).futureValue
servers.par.foreach(_.stop())
startAll(servers); startAllSync(clients*)
```

## Suggested action

```
gh issue comment 11354 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T074142Z-inbox/comments/11354.md
gh issue edit 11354 -R DACH-NY/cn-test-failures --title 'LsuIntegrationTest: validators keep calling scans during the scan restart, BFT retries run out (Consensus not reached WARN)' --add-assignee nicu-da
gh issue comment 11354 -R DACH-NY/cn-test-failures --body '@nicu-da Could you please have a look?'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11354
```
