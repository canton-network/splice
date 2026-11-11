# g6-restart: 11346

Verdict: new, first hit. WalletSurviveCantonRestartIntegrationTest: the restarted standalone participant needed 106 s
to serve the Ledger API (25 s on the first start; every stage slower, plus 40 s package metadata rebuild on restart);
the 2 min tap budget in the second withCanton block includes process startup (withCanton does not wait), and the one
tap after the Ledger API came up hit the 30 s submission deadline / 38 s HTTP timeout. Test flake, test-side fix.

Packet: ci-triage/evidence/11346-wallet-restart-participant-slow-startup-2min-budget.md
Comment: comments/11346.md

INDEX.tsv row:
11346	2026-11-11	42914678423	main 5ccef7f ("Give onboardUser in BFT scan tests a budget above the 38s request timeout (#8410)")	130205231981 wall-clock-time (5)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	WalletSurviveCantonRestart: 2nd standalone participant start 106 s to Ledger API (00:04:25 spawn, 00:06:11 listening, 00:06:32 reconnected); 2 min tap budget includes startup; last tap 00:06:28 DEADLINE_EXCEEDED, 38 s HTTP timeout 00:06:59	new (AU)	test	comment, retitle, Triaged; test fix: wait for participant before tap loop	evidence/11346-wallet-restart-participant-slow-startup-2min-budget.md

known-families.md (index row and section; letter AU is a proposal, next free after AT):
| AU | test retry budget starts before a restarted external Canton process is ready (withCanton does not wait) | 11346 | open, test fix proposed |
### AU: retry budget includes external process startup
Signature: `Failed clue: We can tap and list after Canton restart and domain reconnection` with `Authorization Failed for alice_wallet_user-... Reason: UNAVAILABLE: io exception` through most of the budget.
Confirming check: in canton-standalone-wallet-survive-canton-restarts-2.clog.gz, `Listening on 127.0.0.1:27501` later than about 100 s after `Running clue: We can tap and list after Canton restart` in canton_network_test.clog.
Mechanism: withCanton spawns the process and runs the body at once; eventuallySucceeds(2.minutes) covers JVM start, participant startup (package metadata view rebuild of ~100 packages on restart) and synchronizer reconnect. Slow runner -> budget exhausted.
Parent: 11346. Fix: wait for list_connected() non-empty before the tap loop, or a 5 min budget.

Lesson candidate: "Start a retry budget only once the thing you restarted is ready." When a test spawns an external
process and immediately enters a fixed-budget retry loop, the budget silently includes process and JVM startup, which
varies several-fold with runner load and grows with the state the node reloads on restart. Do: wait for an explicit
readiness signal (health endpoint, connected synchronizers) with its own generous timeout, then run the functional
retry with the budget it actually needs.
  ```scala
  restartProcess()
  eventually(5.minutes)(node.synchronizers.list_connected() should not be empty)
  eventuallySucceeds(2.minutes)(client.doTheThing())
  ```

Suggested action (no assignee; blame of the retry block is a single 2025-09-22 ignore-rule commit, not a clear owner):
  gh issue comment 11346 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T074142Z-inbox/comments/11346.md
  gh issue edit 11346 -R DACH-NY/cn-test-failures --title 'WalletSurviveCantonRestartIntegrationTest: restarted participant slower than the 2 min tap budget'
  direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11346
