# Round 2026-11-09T084535Z-nightly

Inbox export 2026-11-09 08:40 UTC, 32 items. New since the last round: 11231, 11277, 11278, 11279, 11280, 11281,
11282, 11283, 11284, 11285. The mainnet-Daml nightly failed on 0b2ccec235 three nights in a row (11-07, 11-08,
11-09; main did not move over the weekend); 11-05 and 11-06 passed. PR canton-network/splice#8362 (fix for 11274)
still open.

Access: cn-test-failures and splice via gh api (sandbox token); canton-network-internal and CircleCI only as user downloads.

## Queue

| Group | Refs | Signature | Suspected | Rank, why | State |
|---|---|---|---|---|---|
| g1-n1108 | 11277 11278 11279 | mainnet nightly 11-08 (run 42449239264): simtime (5), simtime (3), docker-no-canton (0) | 11274 tag bug, 11275 M2 | 1: nightly red 3 nights on the same sha | landed: 11277 11278 dup 11274; 11279 new infra one-off (Testcontainers pull watchdog, s3mock); quotes verified |
| g2-n1109 | 11281 11282 11283 11284 | mainnet nightly 11-09 (run 42565193320): simtime (5), (3), (0), (1) | same | 1: same | landed: 11281 dup 11274; 11282 11283 dup 11255; 11284 dup 11275; quotes verified |
| g3-ingest | 11280 | Daily store performance tests, Ingestion Tests (run 42557724754) | unknown | 2: daily, first failure after 6 passes | landed: new, #8269 added VerdictStore test without threshold rule, fails every scheduled run; owner jagathweerasinghe-da; quotes verified |
| g4-rl094 | 11231 | wall-clock-time (4) on release-line-0.9.4 (run 42082967872) | unknown | 3: release line, single hit | landed: dup 10358 (closed): cold compile + 40 min sbt timeout, #8319 not backported; quotes verified |
| - | 11285 | CircleCI reset_validator_runbook_devnet, release-line-0.10.1 | sibling of 10680 | 2: daily deploy; log request | open |

Waiting from earlier rounds (re-list, not re-triaged): 11238, 11256, 11258, 11274, 11275, 11276 (round
2026-11-07T155023Z-bft, actions not run; INDEX.tsv rows for 11256 11258 11274 11275 11276 not merged yet),
11259 and 11194 (log requests), and the STATE.md list.

## Resume state (2026-11-09, before sandbox restart)

All four groups landed and are verified; the overview went to the user. Nothing presented group by group yet,
no commands handed out, nothing ran. Next: present groups in queue order (mainnet nightly first) with commands and
comment bodies, unless the user wants a single hand-over.

Suggested actions (details and commands in the g* files):
- 11277 11278 11281: close dup 11274 (run the pending 11274 action from round 2026-11-07T155023Z-bft first).
- 11284: close dup 11275; add the 11-09 recurrence (simtime (1)) to the unposted bft-round comments/11275.md.
- 11282 11283: close dup 11255 (11255 already retitled and Triaged by someone else).
- 11280: comments/11280.md, retitle, Triaged, assignee jagathweerasinghe-da (#8269), ping comment.
- 11279: comments/11279.md, retitle, Triaged (first hit).
- 11231: close dup 10358 (closed); #8319 not backported to release lines, ask the user about a tracking issue.
- 11285: log request (CircleCI reset_validator_runbook_devnet, release-line-0.10.1, sibling of 10680).

INDEX.tsv rows not merged yet: this round's rows are in g1-n1108-result.md, g2-n1109.md, g3-ingest-notes.md,
g4-rl094.md (fix the date column of 11277-11279 to 2026-11-08 when merging); the 2026-11-07 round's rows for 11255,
11256, 11258, 11274, 11275, 11276 are missing too.

## 11274 + 11277 11278 11281 (presented 2026-11-09)

PR canton-network/splice#8362 (author rautenrieth-da) is on auto-merge; its body names no issue and the issue
lives in another org, so it will not close 11274: close by hand after merge. Assignee changed from adetokunbo
(#8224 author) to rautenrieth-da (owns the fix PR). Comment body `comments/11274.md` (bft-round text plus PR and
recurrence paragraphs). State: handed out.

## 11275 + 11258 11276 11284 (presented 2026-11-09)

Cause revised in the main thread (packet addendum): the healthy mediator that never sends is the one held back by
the delayed verdict sender, whose 3 s delay runs on the sim clock; the group has 4 active mediators (sv3 added,
threshold 2, liveness-margin 1), sv3 joined after the request, sv4 was refused. So #8356 (still open, not merged)
only removes one of the two missing sends; earlier text "removed by #8356" is superseded. Candidate fix:
`_mediator_template.parameters.delayed-verdict-sender.enabled = false` in simple-topology-canton-simtime.conf.
11258 becomes a duplicate of 11275 (bft-round comments/11258.md not posted). State: handed out.
Lesson candidate: in simtime tests, any component that schedules on the sequencer clock waits forever while the
test waits on the ledger; disable or zero such delays for the simtime instance.
