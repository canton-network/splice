# g5-simtime-main (11287 run 42591855865, 11288 run 42608940134; post-merge main 2026-11-09)

Packet: evidence/11287-11288-simtime-main-bft-onboarding-lost-verdict-and-rejected-mediator.md

- 11287 simtime (3), main 9dafafbece: dup 11255, lost-verdict shape (request 00:00:00.257Z: sv3 sent, sv4 refused while
  blacklisted, sv2 subscribed from .343002Z, sv1 delayed 3 s on sim clock, sent 10:07:46.991 after the test failed).
- 11288 simtime (4), main bd9953a523: dup 11255, rejected-send shape (MED::sv4 proposal c2c2d0bd49e8 rejected for all
  four participants at the 1 -> 4 switch 11:39:58.737, P2P connectivity not ready; sv4Validator gave up 11:44:21).
- Hit rate: 5 of 13 post-merge main runs since 2026-11-06; 11255 unassigned, #8356 open. Recurrence comment on the parent.
- Epoch tables support packet addendum (2): 11287 sv3 alone 1 -> 2, no blacklist; 2 -> 4 and 1 -> 4 steps blacklist sv4.

Action:
  gh issue comment 11255 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-09T084535Z-nightly/comments/11255-recurrence-1109.md
  direnv exec . ci-triage/scripts/close_duplicates.py 11255 11287 11288 --comment 'Same BFT ordering-topology onboarding window on main: 11287 lost verdict with sv4 blacklisted, 11288 MED::sv4 proposal rejected at the switch to 4 sequencers; details in the comment on the parent.'

INDEX rows:
11287	2026-11-09	42591855865	main 9dafafbece	129076454622 `simtime (3)`	3.7.2	SvTimeBasedRoundMgmtIntegrationTest initDso: sv3Validator installWalletForUser in flight; request 00:00:00.257Z got 1 of 2 verdicts (sv3 sent 10:02:03.566, sv4 refused while blacklisted 10:02:06.023, sv2 subscribed from .343002Z, sv1 delayed 3 s on sim clock, sent 10:07:46.991 after the test failed)	11255 (lost-verdict shape)		dup close 11255, recurrence comment on parent	evidence/11287-11288-simtime-main-bft-onboarding-lost-verdict-and-rejected-mediator.md
11288	2026-11-09	42608940134	main bd9953a523	129132359292 `simtime (4)`	3.7.2	TrafficBasedRewardsSvAppTimeBasedIntegrationTest setup: MED::sv4 proposal rejected for all four SV participants at the 1 -> 4 switch (11:39:58.737, P2P connectivity not ready), outboxes stuck in that flush cycle, sv4Validator gave up 11:44:21, JVM exit	11255 (rejected-send shape)		dup close 11255	evidence/11287-11288-simtime-main-bft-onboarding-lost-verdict-and-rejected-mediator.md

known-families: none new; under 11255 add the occurrences 11287 (lost verdict) and 11288 (rejected MED::sv4 proposal), main 2026-11-09.
Lesson: none (plain duplicates; the simtime/sim-clock lesson candidate from 11275 already stands).
