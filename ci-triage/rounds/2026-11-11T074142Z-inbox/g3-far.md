# g3-far: 11353

Verdict: new, family Y. SvFrontendIntegrationTest "Grant, Update, Amend and Revoke Featured App Right" fails
deterministically on the mainnet-Daml nightly since #8062 (first sha 9dd892fe2e, 2026-11-11): the test sets the
splice-amulet 0.1.25 fields appKind/opsParties, the run uses 0.1.24, Canton rejects the createVoteRequest with
COMMAND_PREPROCESSING_FAILED (downgrade drops Some appKind). Test untagged. Fix: tag SpliceAmulet_0_1_25. Packet:
evidence/11353-featured-app-right-test-needs-amulet-0-1-25.md. Comment body: comments/11353.md.

Rank: nightly broken from 11-11 on (only failing job of that nightly); deterministic until tagged.

INDEX.tsv row:
11353	2026-11-11	42963305445	main 9dd892fe2e (#8062)	130334974223 frontend-wall-clock-time (2)		SvFrontendIntegrationTest Grant/Update/Amend/Revoke Featured App Right on mainnet Daml (splice-amulet 0.1.24): 05:25:05.984 createVoteRequest COMMAND_PREPROCESSING_FAILED, optional field appKind Some dropped during downgrading; #8062 added appKind/opsParties to the test, no version tag	new (Y)		comment, retitle, Triaged, assignee meiersi-da; fix: tag SpliceAmulet_0_1_25	evidence/11353-featured-app-right-test-needs-amulet-0-1-25.md

known-families.md, append to section Y:
- 2026-11-11 (11353): SvFrontendIntegrationTest "Grant, Update, Amend and Revoke Featured App Right", untagged,
  since #8062 fills the splice-amulet 0.1.25 fields appKind/opsParties (from #7627) in the Grant form. Confirm:
  `voterequest/create resulted in a gRPC StatusRuntimeException: INVALID_ARGUMENT: COMMAND_PREPROCESSING_FAILED`
  ... `An optional contract field (\"appKind\") with a value of Some may not be dropped during downgrading` in
  canton_network_test.clog. Its Amend step (SRARC_CreateFeaturedAppConfigUpdateInstruction, #8061) needs
  splice-dso-governance 0.1.31 too. Fix: tag the test case SpliceAmulet_0_1_25. Generic signature of this family on
  any field: `may not be dropped during downgrading` (precedents 4734, 5292, 7546).
Index table row Y: add 11353 to "Parent / main refs".

Lesson candidate: "A test that exercises a new Daml field or choice needs the version tag of the package that
introduced it, in the same PR": the mainnet-Daml nightly runs every untagged test against the versions live on
MainNet, while PR and post-merge CI only run the latest; a missing tag passes all PR checks and breaks the nightly the
morning after merge. Do: when a test sets a field, choice or constructor that is newer than MainNet, add
`taggedAs Tag("...scalatesttags.<Package>_<version>")` (or split the new part into its own tagged test case).
(Family Y now has 3 instances: 10933, 10930 (corrected to NoDamlCompatibilityCheck), 11353.)

Suggested action (commands below, body in comments/11353.md).

```
gh issue comment 11353 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T074142Z-inbox/comments/11353.md
gh issue edit 11353 -R DACH-NY/cn-test-failures --title 'SvFrontendIntegrationTest Featured App Right: appKind needs splice-amulet 0.1.25, test untagged, mainnet-Daml nightly fails' --add-assignee 'meiersi-da'
gh issue comment 11353 -R DACH-NY/cn-test-failures --body '@meiersi-da Could you please have a look?'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11353
```
