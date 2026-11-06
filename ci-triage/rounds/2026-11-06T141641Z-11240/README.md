# 11240 single triage (2026-11-06)

11240 (run 42214429624, wall-clock-time (7), main 97df2c4399): duplicate of 10165 (family B), symptom as 10611 / AI.
Packet: `evidence/11240-copyvotes-topup-in-flight-circuit-breaker-bft-1-to-4.md`.

Commands for the user (not run):

```
direnv exec . ci-triage/scripts/close_duplicates.py 10165 11240 --comment 'BFT step from 2 to 4 sequencers during CopyVotesIntegrationTest setup stalled ordering for about 14 s; the first traffic top-up of splitwellValidator hit the 5 s test deadline and its 19 in-flight resubmissions tripped the high-priority circuit breaker.'
gh issue comment 10165 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-06T141641Z-11240/comments/10165.md
```

Open: 11173 (inbox, AI signature) not re-analysed; check whether its first submission was also ordered late during a
BFT step.
