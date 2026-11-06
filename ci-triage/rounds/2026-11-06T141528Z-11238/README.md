# Round 2026-11-06T141528Z-11238

Single-issue triage of 11238 (run 42205124002, `wall-clock-time (5)`, main 04c342f6fd).
Packet: `evidence/11238-sv4-list-connected-during-validator-sequencer-reconnect.md`. Family I (test race).

Commands for the user (not run):
- `gh issue comment 11238 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-06T141528Z-11238/comments/11238.md`
- `gh issue edit 11238 -R DACH-NY/cn-test-failures --title 'SvInitializationIntegrationTest: list_connected races the validator sequencer-connection reconnect'`
- `direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11238`

Lesson candidate (week 2026-W45): a single read of state that another component can legitimately bounce
(connection state, triggers) needs `eventually`, even after a startSync that waited for the same state.
