# CI triage state

## State 2026-11-03

Branch `rautenrieth-da/ci-triage-2026-11-02`, started from main c95027ee5f. Continues the previous
triage branch `ray/ci-triage-2026-09-15` (2026-09-08 to 2026-11-02), which has the earlier README, handover and
onboarding.

Current round: `rounds/2026-11-03T131520Z/` (inbox export 2026-11-03 13:14, 35 items), step mode, resume point at
the end of its README; queue and
per-item state in its README.
Previous round `rounds/2026-11-02T153358Z-singles/`: its commands did not run (export 2026-11-03).

Taken over from the previous triage branch:
- all packets, now in `evidence/` (index: `evidence/INDEX.tsv`, built from its README and the 2026-11-02 one); its
  round READMEs and host-command files in `rounds/` (one directory per round, named by its start time);
- `known-families.md` (moved from its skill copy, plus an index at the top);
- not taken over: its skill copies (`skill/ci-triage`, superseded by `skills/ci-triage/`; `skill/ci-triage-inbox`,
  superseded by `skills/ci-triage-inbox/`, its script is `scripts/inbox_from_timelines.py`;
  `sandbox-sbt-env.sh`, nix store paths of an older template image).

Access (`scripts/probe_access.py`, 2026-11-03 evening, after the sandbox was re-created): cn-test-failures issues
and splice via `gh api` (sandbox token of rautenrieth-da; `gh issue view` works too). No `ghx`; canton-network-internal
and CircleCI logs only as user downloads. Board (project 48, Status) not readable: host only.
The token is a sandbox secret: re-creating the sandbox wipes it (the user sets it again).

## Inbox 2026-11-03 13:14 (35 items, board export)

Round `rounds/2026-11-03T131520Z/`, step mode; nothing handed out has run yet. Target action per item:

| Class | Refs | Next action |
|---|---|---|
| done, Triaged | 10495, 10496, 11104, 11105, 11128, 11140, 11141, 11144, 11147, 11160, 11161 | set_status Triaged (hand-over) |
| done, Infrequent | 11152 | set_status Infrequent / no repro |
| done, close as dup | 10891 (10921), 11138 11139 11158 (11128), 11153 11154 (11152), 11143 (10732), 11163 (10907), 11155 (10438) | close_duplicates (hand-over) |
| waiting | 10728 (Q mechanism only), 10893 (Splitwell 0.9.x, first hit), 10917, 10918, 10919 (compose, owner questions) | none |
| needs-log, CircleCI | 10680, 11106, 11137, 11142, 11151 | user saves step output to export/<ref>/ |
| needs-log, cni GHA | 10479, 11117, 11145 | user saves job log to export/<ref>/ |
| out of scope | 11146 (blackduck vulnerability with Status Inbox) | none |

## Open fix branches

2026-11-04: `rautenrieth-da/fix-11168-unallocated-record-expiry` (5d775de976, off main 4ab8bd88e8):
UnclaimedActivityRecordIntegrationTest first test case expiry 5 s -> 10 s (as #5176 did for the second); PR 8241.

2026-11-04: `rautenrieth-da/fix-11165-sequencer-pruning-retention` (29019699a0, off main 4ab8bd88e8): family AH,
SequencerPruningIntegrationTest retention 120 s -> 180 s, wait 3 -> 5 min; PR 8238.

2026-11-04: `rautenrieth-da/fix-10930-mainnet-daml-test-tags` (67d433fb33, off main b5e6a1ba55): family Y, PR 8228, coupon suite
NoDamlCompatibilityCheck, traffic test cases SpliceAmulet_0_1_25 (fixes 10930, 10933); scalafmt and Test/compile pass; no PR yet.

2026-11-04: `rautenrieth-da/fix-11161-login-helper-timeout` (72d55afdc9, off main 7307892450): family AE, login attempts
in FrontendLoginUtil.loginOnceConfirmedToBeAtUrl get 15 s instead of 5 s; scalafmt and Test/compile pass; no PR yet.

2026-11-03 (merged 21:56, closed 11128): `rautenrieth-da/fix-11128-scan-party-monitor-dso-visible-tx` (0c380008f0, off main 8dacec5c10):
family Z test fix, a tap after the private offer; PR canton-network/splice#8211 (auto-merge), opened 2026-11-03.
2026-11-03 (merged 2026-11-04 06:18, closed 11144): `rautenrieth-da/fix-11144-tbar-wait-all-rounds` (9f4b991cbd, off main 934f543eb4): family AD, assertRewardCalcs
retries the per-round reads; PR canton-network/splice#8221, opened 2026-11-03.
2026-11-03 (merged 21:20, closed 10732): `rautenrieth-da/fix-10732-wallet-metrics-outcome-label` (b43adb1676, off main 8dacec5c10): family AC, PR 8215,
WalletMetricsTest looks up trigger.completed with outcome success; static_tests passed.

From the previous handover, 2026-11-02:

Pushed to canton-network/splice without a PR, none compiled against current main:
`ray/fix-10197-bft-read-confirmation-wait`, `ray/fix-reset-topology-plugin-no-exit` (10139),
`ray/fix-topology-init-limit` (10140 closed, likely obsolete), `ray/fix-fail-fast-init` (no proving test).

## Open owner questions

2026-11-04: 11160 (family L) waits for DACH-NY/canton#37633 (reference driver storage ignores the node timeouts); comment on 11160 says so.

2026-11-03: Z test fix (stephencompall-DA, 5 hits); U join import retry (owner unknown, 3 hits); 11160 validator
onboarding wait (owner unknown); test fixes for AC, AD, AE, AF, AG described in their packets, no branch.

From the previous handover, 2026-11-02:

10912 (W, rautenrieth-da), 10915 (Devnet 18:00 backup status race, suggestion krzysztofczyz-da), 10927 (X2,
moritzkiefer-da), 10930 and 10933 (Y, suggestion Simon Meier), plus 10917, 10918, 10919 above.
