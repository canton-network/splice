---
name: ci-triage-inbox
description: Triage every item with Status Inbox in the cn-test-failures board (DACH-NY project 48) in one go - infer the inbox from issue timelines (Projects v2 is not readable from the sandbox), separate already-triaged items whose host commands were never run from new ones, triage the new ones in parallel background forks with the ci-triage process, and hand Raymond one consolidated block of gh close/comment commands. Use for "triage the inbox", "ci triage the inbox again", "/ci-triage-inbox", "what's in the inbox".
---

# CI triage of the cn-test-failures inbox

"The inbox" is the items in https://github.com/orgs/DACH-NY/projects/48/views/1 with Status = Inbox, not every open
issue in DACH-NY/cn-test-failures (that is ~200, back to 2025). Per-issue analysis follows the `ci-triage` skill;
this skill is the batch wrapper around it. Output: packets in `ci-triage/<date>[b|c]/` on the triage branch
`ray/ci-triage-2026-09-15` (main checkout, `~/src/splice`), README rows, known-families updates, and ONE block of
host commands in chat. Never push, comment, close or assign yourself; Raymond runs the commands.

## 1. Fetch the inbox

Needs the host GitHub router (`ghx`). If `ghx` fails with `dial tcp ... 18555 refused`, the router is off: ask
Raymond to switch it on, or to paste
`gh project item-list 48 --owner DACH-NY --limit 500 --format json --jq '.items[] | select(.status == "Inbox") | .content.number'`.

```
cd ~/src/splice
python3 -I ~/.claude/skills/ci-triage-inbox/scripts/inbox_fetch.py --min-number <oldest open item of the last round, e.g. 10400> --triage-dir ci-triage
```

The script lists open issues whose project status was only ever changed by the automation bot (= still Inbox),
flagged `T` when an issue number already appears in `ci-triage/**.md`. Old 2025 / March 2026 bulk items match the
heuristic too (archived on the board); `--min-number` drops them. Sanity-check the count against Raymond's number
when he gives one.

## 2. Split the list

- `T` items: already triaged. They stay in the inbox because their host commands were not run, or because they
  are deliberately open umbrellas (an open family like 10520, a fix PR with `Fixes ...` pending). For each, find
  its command in `ci-triage/*/host-commands-*.md`; re-hand only the commands of items still open, and say which
  `T` items stay open on purpose.
- Items assigned to someone with an active discussion: skip unless asked, mention them.
- New items: group before triaging.
  - per-job batches of one run (same `GHA Run N`, many jobs): check every job's first error, one verdict per
    batch, one close command per issue (no loops in handed-over commands).
  - splice GHA scala_test (canton-network/splice runs; logs and artifacts via `gh api`).
  - infra: canton-network-internal GHA (backup_status, auth0 cleanup; Actions logs are refused by the router) and
    CircleCI pipelines (403 from the sandbox). Use issue bodies/comments, earlier issues with the same title, cni
    config via `ghx` contents, and mark needs-log with the exact job id + step to paste.

## 3. Triage in background forks

Run each group as a background `fork` agent (Raymond wants the chat free; see feedback-ci-triage-background-fork),
all in one message. Brief each fork with: the issue numbers with run ids, the ci-triage skill process, the current
known-family leads, the packet dir `ci-triage/<date>[b]/`, and these rules:
- other forks write the same dir and README concurrently: stage only own files + own README hunk, re-read README
  right before editing, retry on a commit race;
- `git commit -s -m "[skip ci] ..."`, one subject line, no Co-Authored-By;
- no push, no issue comments; host commands go to `ci-triage/<dir>/host-commands-<a|b>.md`, zsh-safe, no
  placeholders, one command per issue;
- no sbt; chase the root cause (what is it waiting on, why), not the timeout symptom;
- update `ci-triage/skill/ci-triage/references/known-families.md` and copy it to
  `~/.claude/skills/ci-triage/references/`.

Do not do the same work in the main thread while forks run. Report each fork's verdicts when it lands, shortly.

## 4. Hand over

Before handing over (and always before re-handing), read each target's live state and drop what Raymond already did
(closed, commented, retitled); say "already done: ..." for those. Batches go out as a script whose steps are
state-guarded, so running it twice is harmless, e.g.
`[ "$(gh issue view N --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh issue close N ...`;
comments only if no own comment with the same first line exists; retitles only if the title still starts with
"GHA Run" or "Pipeline".

One message with: the per-issue verdict table (dup of X / new family + cause / needs log), what needs an owner,
and one zsh block with every command from the new `host-commands-*.md` files plus the unrun ones from step 2.
Close dups as `--reason "not planned" --comment "Duplicate of #N"`; fixed ones `--reason completed` with the
fixing PR. Real regressions stay open with a root-cause comment and get an owner suggestion (author of the culprit
PR or the holder of the umbrella issue).

## 5. Surge question

When asked why the inbox grew or who should own the mess, run a separate fork for a data-driven count: issues per
day and per family since the last calm period, culprit PR + author per family, status, owner. Distinguish per-job
dup batches of one broken scheduled run (noise) from distinct problems. Write it to
`ci-triage/<dir>/surge-analysis.md`.

## 6. Afterwards

Update the project memory note of the current triage round (date, packet dir, commits, open items, what Raymond
still has to run).
