# g5-simtime: 11348

Verdict: duplicate of 11147 (family AF), third hit, first on sv1 (11147 and 11291 were sv2). Packet:
`evidence/11348-sim-jump-timeouts-trip-sv1-breaker-dup-11147.md`.

Confirming check done: exactly 20 consecutive medium-priority LOCAL_VERDICT_TIMEOUT failures of sv1
(CalculateRewardsTrigger, SummarizingMiningRoundTrigger) after the last medium success at 00:33:42.988, over the
sim-clock jumps at 00:33:44.713, 00:33:46.804 and 00:33:48.761, then the trip at 00:33:49.472. Only flagged line.
11182 ruled out (its ERROR is absent). Fix splice#8420 (open, stephencompall-DA) disables the breakers of all SV
apps in the test, so it covers sv1. 2 hits (11291, 11348) in 82 completed post-merge main runs since 2026-11-04.

No parent comment: 11147 has an assignee and an open fix PR.

INDEX.tsv row:
```
11348	2026-11-11	42923829975	main 2ef0423	130205972060 docker-canton-simtime (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	ScanNewNetworkBackfillingTimeBasedIntegrationTest sv1 medium breaker tripped 00:33:49.472 after 20 LOCAL_VERDICT_TIMEOUT over 3 sim-clock steps (00:33:44-00:33:48), tests passed	11147 (AF)		close dup 11147; covered by fix PR splice#8420	evidence/11348-sim-jump-timeouts-trip-sv1-breaker-dup-11147.md
```

known-families.md, AF: replace the Hits line start with
```
- Hits: parent 11147 (2026-11-02, main 136e2e3160, 3 steps), 11291 (2026-11-09, main ab36803c8a, 4 steps, dup),
  11348 (2026-11-11, main 2ef0423, 3 steps, dup, sv1). All in docker-canton-simtime (0),
  ScanNewNetworkBackfillingTimeBasedIntegrationTest; sv2 in the first two, sv1 in 11348 (any SV app can trip).
  2 of 82 post-merge main runs 2026-11-04..11. Fix: splice#8420 (open), CircuitBreakersConfig.never for all SV apps.
```
and in the index table: `11147 (dups 11291, 11348)`.

Lesson: none (AF is already a lesson candidate from 11147 if not written; nothing new here).

Action:
```
direnv exec . ci-triage/scripts/close_duplicates.py 11147 11348 --comment 'Same failure as 11147, this time on sv1: 20 consecutive LOCAL_VERDICT_TIMEOUT of the medium-priority SV triggers over three sim-clock steps tripped the breaker. Covered by the open fix splice#8420, which disables the breakers of all SV apps in the test.'
```
