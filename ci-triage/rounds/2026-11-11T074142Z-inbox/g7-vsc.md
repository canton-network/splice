# g7-vsc: 11350

Verdict: duplicate of 11255 (family B signature (2), wall-clock). Packet: evidence/11350-onboard-sv-sequencer-38s-timeout-bft-3-to-4.md

ValidatorSequencerConnectionIntegrationTest environment setup, run 42948633392 attempt 1 (attempt 2 passed), job
130287690937. All 18 tests passed; one WARN: sv1's `onboard/sv/sequencer` for sv2 timed out after 38 s
(03:09:01.128). sv2's sequencer-add proposals (03:08:23.2) landed in epoch 33 (size 3, sv4 newcomer), below strong
quorum (2 < 3) until sv4 joined at 03:08:46.9; sv2's add effective with the size-4 topology (activation
03:09:00.829). sv2's retry succeeded in 16 s. 10165 (same WARN) was merged into 11255 by rautenrieth-da on 2026-11-08.
Not the #8410 / 11331 onboardUser shape.

## INDEX.tsv row

11350	2026-11-11	42948633392	main 9dd892fe2e (#8062)	130287690937 wall-clock-time (7), attempt 1 (attempt 2 passed)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	ValidatorSequencerConnectionIntegrationTest setup, all 18 tests pass; checkErrors WARN sv1 `onboard/sv/sequencer ... timeout after 38 seconds` 03:09:01.128 for sv2: sv2 sequencer-add submitted 03:08:23.2 into epoch 33 (size 3, below strong quorum until sv4 joined 03:08:46.9), size-4 topology active 03:09:00.829; sv2 retry OK in 16 s	11255 (B)	flake, BFT onboarding window	close as dup of 11255	evidence/11350-onboard-sv-sequencer-38s-timeout-bft-3-to-4.md

## known-families.md text (append to family B)

- Signature (2) on Canton 3.7.3-snapshot.20261109 (11350, 2026-11-11, main 9dd892fe2e, wall-clock-time (7),
  ValidatorSequencerConnectionIntegrationTest setup): sv3, sv4, sv2 onboard their sequencers through sv1 within 21 s;
  sv2's add is submitted into the 2 -> 3 step (sv4 newcomer, below strong quorum 03:08:23.5-03:08:46.9) and sv1's
  handler times out at 38 s waiting for it in SequencerSynchronizerState; the retry succeeds. 10165 merged into 11255
  (2026-11-08), so close new hits of this WARN as duplicates of 11255.

## Lesson

Lesson: none (Canton BFT onboarding window, plain duplicate).

## Action

direnv exec . ci-triage/scripts/close_duplicates.py 11255 11350 --comment 'During environment setup the sv1 onboard/sv/sequencer request for sv2 timed out after 38 s while the BFT ordering topology was below strong quorum during the 2 to 3 step with sv4 joining; the retry succeeded and all tests passed.'

No recurrence comment on 11255 (assigned, active owner discussion).
