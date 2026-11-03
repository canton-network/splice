# Group tscli (11160)

Packet: `evidence/11160-onboarding-retry-budget-reference-sequencer-40001-stall.md`. INDEX row: `tscli-index-row.tsv`.

## Verdict

11160: family L (reference sequencer `insert block` SQLSTATE 40001 retry storm), new occurrence with a new symptom.
Release-line-0.10.0 0ad3ce0394 (#8192, Grafana dashboard only), Canton 3.7.1. alice onboarding
(`onboardWalletUser`, first step of "expire amulet allocations handling both locked and already unlocked amulets")
fails with INVALID_PRESCRIBED_SYNCHRONIZER_ID: the new party is effective on splitwell at 12:29:15.85 but on global
only at 12:29:27.6 because globalSequencerSv1 did not create a store batch for 18.2 s (insert block 40001, backoff
up to 6.373 s); `installWalletForUser` gave up after 12 retries at 12:29:23.5. Not a missing backport (family E
checked: no main-only change to ValidatorUtil, HttpValidatorAdminHandler or the test). Not a duplicate of an open
issue in the export; family L parents 10139 and 10197 are older than the export, state unknown.

Fix (not written): splice validator `ValidatorUtil.onboard` should wait for the PartyToParticipant on the
AmuletRules synchronizer before `installWalletForUser` (owner decision), and/or the family L contention itself. No
test-side fix (generic `onboardWalletUser` helper).

## known-families.md text (append to section L, Occurrences)

- 2026-11-03: 11160 (release-line-0.10.0 simtime, Canton 3.7.1): globalSequencerSv1 store stall 18.2 s
  (12:29:09.478 to 27.680, backoff up to 6.373 s) makes `onboardWalletUser` fail with HTTP 400
  INVALID_PRESCRIBED_SYNCHRONIZER_ID: the new party is effective on splitwell 1.5 s after allocation but on global
  only after 13.2 s, and `installWalletForUser` gives up after 12 ClientCalls retries. Onboarding waits only on
  the Authorized store (`createPartyAndUser` -> `ensurePartyAllocated(TopologyStoreId.Authorized)`). Packet
  `evidence/11160-onboarding-retry-budget-reference-sequencer-40001-stall.md`.

Index row update for L: parent / main refs `10139, 10197, 11160`; state unchanged (open, Canton / test infra).

## Issue commands

```
gh issue comment 11160 -R DACH-NY/cn-test-failures --body 'Family L (reference sequencer insert block SQLSTATE 40001 retry storm), new symptom. TokenStandardCliTestDataTimeBasedIntegrationTest fails in its first step, onboardWalletUser for alice on aliceValidator: HTTP 400 INVALID_PRESCRIBED_SYNCHRONIZER_ID (informees on splitwell, not on global). The new party became effective on splitwell at 12:29:15.85 but on the global synchronizer only at 12:29:27.6: globalSequencerSv1 did not create a store batch from 12:29:09.478 to 12:29:27.680 (18.2 s) while insert block failed with SQLSTATE 40001 (backoff up to 6.373 s), right after a sim-time advance. installWalletForUser prescribes the global synchronizer and gave up after 12 retries at 12:29:23.5. Not a missing backport (#8192 only changes a Grafana dashboard; no main-only change to the onboarding code or the test). Possible fix in the validator app: in ValidatorUtil.onboard, wait for the PartyToParticipant of the new party on the AmuletRules synchronizer before installWalletForUser (createPartyAndUser only waits on the Authorized store). Packet: ci-triage/evidence/11160-onboarding-retry-budget-reference-sequencer-40001-stall.md on the triage branch.'
gh issue edit 11160 -R DACH-NY/cn-test-failures --title 'TokenStandardCliTestDataTimeBasedIntegrationTest: onboarding gives up with INVALID_PRESCRIBED_SYNCHRONIZER_ID while global ordering stalls on insert block 40001 (family L)'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11160
```

## Not verified

Why the 40001 storm started at 12:29:09 beyond the sim-time advance burst; state of 10139 and 10197.
