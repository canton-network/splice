# CI triage state

## Handover 2026-11-09 afternoon

Round `rounds/2026-11-09T084535Z-nightly/` done except 11286 (log request). Live state checked 2026-11-09 ~13:30 UTC.
Closed today: 10680, 11231, 11255 dups (11258 11275 11276 11282 11283 11284 11287 11288), 11274 (+ dups, #8362 merged
13:10), 11285. Triaged today: 11279, 11280 (jagathweerasinghe-da).

Inbox still open, oldest first, with the outstanding action:
- 10479 (10-21) cni Purge Unused Databases: needs-log (cni GHA), never commented.
- 10495, 10496 (10-23), 11104, 11105 (10-30), 10728, 10893: commented, only `set_status.py Inbox Triaged` missing.
- 10917, 10918 (10-29, no comment), 10919: owner questions; suggested Triaged with the question in the comment.
- 11117 (10-31) Devnet backup after LSU: needs cni GHA log; may pair with 11286.
- 11140, 11147 (11-02): packets exist, comment/retitle/Triaged never run (STATE table above).
- 11194 (11-04) CircleCI monitor_sv_catchup_test: log request (rounds/2026-11-05T094500Z-credentials).
- 11238 (11-06): commands in rounds/2026-11-06T141528Z-11238/README.md, not run.
- 11256 (11-06): close dup 10729 (rounds/2026-11-07T155023Z-bft/g2-6f7a5f8.md), not run.
- 11259 (11-06) cni cluster_lock_reaper: log request.
- 11286 (11-09) cni Mainnet Backup Status: log request to export/11286/; asked whether Mainnet had an LSU.
- 11255 (parent, open, unassigned): fix candidates splice#8356 (open), sequential sequencer onboarding, simtime
  delayed-verdict-sender off; at least 5/13 post-merge main runs hit since 11-06. Needs an owner.

Not merged yet into shared files: INDEX.tsv rows of the bft and nightly rounds (g*-files, g6-11285.tsv),
known-families text for the 11255 shapes, lessons/2026-W46.md candidates (11274 tag on abstract base; simtime and
sequencer-clock delays). Rules from 2026-11-09 in the round README (duplicates: per-ref one-liners, no parent comment
unless understanding changes; no unverified guesses in comments).

## State 2026-11-03

Branch `rautenrieth-da/ci-triage-2026-11-02`, started from main c95027ee5f. Continues the previous
triage branch `ray/ci-triage-2026-09-15` (2026-09-08 to 2026-11-02), which has the earlier README, handover and
onboarding.

Current round: `rounds/2026-11-09T084535Z-nightly/` (exports 08:40 and 12:13; see "Handover 2026-11-09 afternoon" above).
Previous: `rounds/2026-11-07T155023Z-bft/` (export 2026-11-07 15:47, 29 items).
Queue and per-item state in its README. Earlier: `rounds/2026-11-03T131520Z/`.
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

## Fix branches

Since 2026-11-05 not tracked here: the fix agent (skill `ci-triage-fix`) works from the board (Status Triaged
without assignee, `closingPullRequests` in the export). Open on 2026-11-05: `rautenrieth-da/fix-11165-sequencer-pruning-retention`, PR 8238
(PR text accepted, do not change it). All earlier fix branches are merged and their issues closed.

## Open owner questions

2026-11-04: 11160 (family L) waits for DACH-NY/canton#37633 (reference driver storage ignores the node timeouts); comment on 11160 says so.

2026-11-03: Z test fix (stephencompall-DA, 5 hits); U join import retry (owner unknown, 3 hits); 11160 validator
onboarding wait (owner unknown); test fixes for AC, AD, AE, AF, AG described in their packets, no branch.

From the previous handover, 2026-11-02:

10912 (W, rautenrieth-da), 10915 (Devnet 18:00 backup status race, suggestion krzysztofczyz-da), 10927 (X2,
moritzkiefer-da), 10930 and 10933 (Y, suggestion Simon Meier), plus 10917, 10918, 10919 above.
