# CI triage state

## State 2026-11-02

Branch `rautenrieth-da/ci-triage-2026-11-02`, started from main c95027ee5f. Continues the previous
triage branch `ray/ci-triage-2026-09-15` (2026-09-08 to 2026-11-02), which has the earlier README, handover and
onboarding.

Current round: `rounds/2026-11-02T153358Z-singles/` (10891, 11138, 11139, 11140); commands handed out, not
confirmed as run.

Taken over from the previous triage branch:
- all packets, now in `evidence/` (index: `evidence/INDEX.tsv`, built from its README and the 2026-11-02 one); its
  round READMEs and host-command files in `rounds/` (one directory per round, named by its start time);
- `known-families.md` (moved from its skill copy, plus an index at the top);
- not taken over: its skill copies (`skill/ci-triage`, superseded by `skills/ci-triage/`; `skill/ci-triage-inbox`,
  superseded by `skills/ci-triage-inbox/`, its script is `scripts/inbox_from_timelines.py`;
  `sandbox-sbt-env.sh`, nix store paths of an older template image).

Access (`scripts/probe_access.py`, 2026-11-03): only splice via `gh api`. No sandbox token for DACH-NY (the
user's fine-grained token is still in review), no `ghx` (router not reachable: network policy for
`localhost:18555` missing, client key not copied). Issues come from host exports in `ci-triage/export/`,
canton-network-internal and CircleCI logs only as user downloads. Board: host only.

## Inbox 2026-11-02 14:44 (19 items, board export)

Verdicts below are from the previous triage (issue comments and packets), not rechecked.

Root cause established, still in Inbox (the previous 2026-11-02 round did not move statuses):

| Issue | Family | Previous verdict |
|---|---|---|
| 10495 | G | offboarded mediator's in-flight send rejected; mediator-offboarding ignore file lacks the reonboarding pattern |
| 10496 | J | docs job `npm ci` hangs, npm-install.sh has no per-attempt timeout |
| 11104 | AA | LsuIntegrationTest sv4 faucet trigger ERROR during late sv4 sequencer catch-up; test fix |
| 11105 | (note 2026-11-02) | Canton 3.7.0 ACS updates stream abrupt termination; first hit, Canton |
| 11128 | Z | ScanPartyMonitorScript /v0/events cap; test fix, owner stephencompall-DA |

Left in Inbox on purpose by the previous triage, cause not established:

| Issue | Family | Open question |
|---|---|---|
| 10728 | Q | release-line javac codegen inference; mechanism only |
| 10893 | - | Splitwell frontend error on unknown party not shown (0.9.x), first hit |
| 10917 | (note 2026-10-30) | compose portfolio 'Failed to list contracts'; recheck after #8130 |
| 10918 | (note 2026-10-30) | compose wallet tap row missing; root cause not found |
| 10919 | (note 2026-10-30) | ExpiryWithIgnoredAmuletVersion startup, splitwellValidator DB lock; holder unknown |

Need logs that the sandbox cannot fetch:

| Issue | Source | Needed |
|---|---|---|
| 11106 | CircleCI pipeline 87744, job 527403, deploy_sv_reonboard | step output |
| 11137 | CircleCI pipeline 87814, job 527732, deploy_sv_reonboard | step output |
| 10680 | CircleCI pipeline 87309, job 525338, reset_validator_runbook_devnet (release-line-0.9.4) | step output (previous instance 10334: slow submodule clone) |
| 11117 | cni GHA 41175569098, Devnet Backup Status | job log (stale list); dup 11118 already closed |
| 10479 | cni GHA 38997942871 job 117294527070, Purge Unused Databases (backfilled for cni#7615) | job log |

Triaged 2026-11-02 on this branch; issue commands handed to the user, not yet confirmed as run:

| Issue | Verdict | Action |
|---|---|---|
| 10891 | dup of 10921 (V), PR branch without #8148 | close as dup |
| 11138, 11139 | dups of 11128 (Z) | close as dup, recurrence comment on 11128 |
| 11140 | new family AB, test-side timing race | comment, retitle, move to Triaged; test fix proposed, no branch |

## Open fix branches (from the previous handover, 2026-11-02)

Pushed to canton-network/splice without a PR, none compiled against current main:
`ray/fix-10197-bft-read-confirmation-wait`, `ray/fix-reset-topology-plugin-no-exit` (10139),
`ray/fix-topology-init-limit` (10140 closed, likely obsolete), `ray/fix-fail-fast-init` (no proving test).

## Open owner questions (from the previous handover, 2026-11-02)

10912 (W, rautenrieth-da), 10915 (Devnet 18:00 backup status race, suggestion krzysztofczyz-da), 10927 (X2,
moritzkiefer-da), 10930 and 10933 (Y, suggestion Simon Meier), plus 10917, 10918, 10919 above.
