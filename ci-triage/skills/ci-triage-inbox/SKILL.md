---
name: ci-triage-inbox
description: The triage agent for the cn-test-failures board (DACH-NY project 48) - empty the Status = Inbox column on every pass. Fetch the inbox, group the items by run, sha and failure signature, triage each group with the ci-triage skill (in parallel background agents), give every item a verdict that takes it out of Inbox (close as duplicate, Triaged, Infrequent / no repro, or a log request), queue the fixes in ci-triage/fix-queue.tsv for the ci-triage-fix agent, and hand the user one block of board and issue commands per pass. Does not write fix branches. Use for "triage the inbox", "empty the inbox", "what's in the inbox", "go through the inbox one by one", "/ci-triage-inbox".
---

# Empty the CI inbox

Context, access to private data and conventions: `ci-triage/CLAUDE.md` on the triage branch. Per-issue work follows
the `ci-triage` skill; this skill is the wrapper around it for a whole inbox. Recipes:
`ci-triage/skills/ci-triage/references/recipes.md`.

Goal: after every pass the inbox is empty, except for items waiting for a log the user was asked for in this pass.
An item leaves Inbox with a verdict, not with a fix: fixes go into `ci-triage/fix-queue.tsv`, which the fix agent
(`ci-triage-fix` skill) works through on its own. Speed over depth: a recurring failure with a plausible mechanism
and an owner is triaged; the fix agent or the owner goes deeper.

## Ownership (two agents share the triage branch)

The triage agent owns everything under `ci-triage/` except `fixes.tsv` and `fixes/`, which belong to the fix agent
(read them, never edit them). Both agents may run at the same time in the same checkout, so commit only your own
paths: `git add <paths> && git commit -s -m '...' -- <paths>`; on `index.lock` errors wait a few seconds and retry.
Never commit with `-a` or a bare `git add -A`.

## Verdicts

Every inbox item gets exactly one:

| Verdict | When | Board / issue | Fix queue |
|---|---|---|---|
| duplicate | same failure as an open or fixed parent (ci-triage step 3, confirming grep done) | `close_duplicates.py` | no row (the parent has one) |
| triaged | mechanism established, or a recurring failure with a plausible mechanism and a named next step | comment, Triaged | one row per fix (below) |
| fixed | cause already fixed on main (a merged PR, `git merge-base --is-ancestor`) | comment, close as completed | no row |
| one-off | infra or GitHub incident that recovered, or a single failure without a pattern after the time box | comment, Infrequent / no repro | no row |
| needs-log | the evidence is only where no available method reaches (CircleCI always; canton-network-internal Actions without `ghx` or a token for it) | stays in Inbox | none yet |
| out of scope | `Vulnerability Inbox` items, issues filed by hand for something other than a CI failure | none; mention it | no row |

- Time box: about 30 minutes of agent work per group. Without a root cause by then, write a comment with what was
  found and what would decide it, and pick triaged (recurring: kind `owner` in the queue, with the open question as
  the spec) or one-off (first hit). Never leave an item in Inbox because the analysis is unfinished.
- needs-log may stay in Inbox for one pass. Ask for all logs of the pass in one request (exact job id and step,
  saved to `export/<ref>/`). If the logs have not come by the next pass, decide from what there is.
- Items left waiting on purpose in older rounds (owner question, umbrella) are triaged now: comment if not done
  yet, Triaged, and a kind `owner` row.
- An item the user assigned to someone keeps its assignee; still give it a verdict.

## Fix queue

`ci-triage/fix-queue.tsv`, append only (one row per fix, sorted by date; a fix for several refs is one row under the
parent ref). Tab-separated, no tabs or newlines in cells, columns:
`ref refs date kind auto owner spec packet`
- `ref`: the parent ref; the fix branch is named after it. `refs`: other refs the fix covers, space-separated.
- `kind`: `test` (test code or test config), `backport` (a fix on main missing on a release line), `prod` (splice
  production code, Daml, deployment config), `infra` (canton-network-internal, CI config), `canton` (needs a Canton
  change), `owner` (needs a person to decide or investigate).
- `auto`: `yes` only when all ci-triage step 9 conditions hold (cause established with evidence, test-only or a
  clean cherry-pick, one screen); the fix agent then writes it without asking. Otherwise `no`.
- `owner`: suggested GitHub login (git blame of the lines the fix touches, or the author of the change that caused
  it), or empty.
- `spec`: file, line and change, precise enough to implement without reading the chat; or the open question for
  `owner`.
- `packet`: path relative to `ci-triage/`.
To withdraw or correct a row, append a new row for the same `ref` with the new spec (or `withdrawn` in `spec`);
the last row per ref wins.

## Pass

### 1. Fetch

- Run `ci-triage/scripts/probe_access.py` and tell the user which method you use per capability (CLAUDE.md,
  Access) the first time in a session.
- Read `ci-triage/STATE.md` and the last round's README: open needs-log requests, commands handed out but not run.
- Create the round directory `ci-triage/rounds/<YYYY-MM-DDTHHMMSSZ>[-<slug>]/` (UTC start, `date -u
  +%Y-%m-%dT%H%M%SZ`) and name it in `STATE.md`. Someone resuming it without the chat must see what was found,
  what was handed out and what is open. Background agents write only `<round dir>/<group>-*` and their packets.
- The inbox: the user's recipe 10 export (`/run/sandbox/source/ci-triage/export/inbox.jsonl`) if it is newer than
  the last pass; otherwise the timeline heuristic, saying that the list is inferred:
  ```
  ci-triage/scripts/inbox_from_timelines.py --min-number <oldest open item of the last round> --triage-dir ci-triage --json log/inbox-heuristic.json
  ```
- Recent issues for the duplicate checks: recipe 11, else the recipe 9 export.
- Drop items whose commands from an earlier pass are only waiting to be run (re-list those commands at the
  hand-over). State the source, its time and the item count.

### 2. Group

- Same run (per-job issues of one workflow run): one group. Same sha, same test or same flagged line across runs:
  one group. Grep the signature against `known-families.md` and `evidence/INDEX.tsv` first and put the suspected
  family into the brief.
- Resolve each issue's job id (ci-triage step 1). A mapping that needs the user stops that group, not the pass.
- Order: hard broken (fails every run), cluster deployments, frequent flakes, the rest.

### 3. Triage the groups in parallel

One background agent per group, all in one message (forks inherit this context; a fresh agent reads
`ci-triage/CLAUDE.md` and the ci-triage skill first). Brief each with: the refs with run id, job id and job name, the
suspected family, the packet file name (`ci-triage/evidence/<ref>[-<ref>...]-<slug>.md`), its group name and the
round directory, the time box, and these limits:
- follow ci-triage steps 1 to 9; write only the packet and `<round dir>/<group>-*`; no edits to shared files, no
  commits, no branches;
- return: the verdict per ref (table above), the INDEX.tsv rows, the `known-families.md` text, the fix-queue rows,
  the lesson candidate or "Lesson: none", and the issue commands with their body files under
  `<round dir>/comments/` (`ci-triage/skills/ci-triage/references/issue-comment.md`).
Do not repeat an agent's work in the main thread. Report each verdict in one line when it lands.

### 4. Merge (main thread)

- Check each packet: ASCII only, commands with verbatim output, file names as briefed.
- Add the rows to `evidence/INDEX.tsv` (sorted by ref), the family text to `known-families.md`, the fix rows to
  `fix-queue.tsv`, accepted lessons to `lessons/<ISO week>.md` (`ci-triage/CLAUDE.md`, Lessons), and refresh
  `STATE.md` (round, needs-log requests, open owner questions). Reconcile agents that proposed the same new family
  letter or the same parent for different causes.

### 5. Hand over

- Re-check the live state of every target (recipe 11 or a fresh export) and drop what is already done.
- Write the hand-over into `<round dir>/README.md`, commit (`[skip ci] <round dir name>: <one line per verdict
  group>`), then print:
  1. the verdict table: ref, verdict, parent or family, one-line cause, fix-queue row (kind, auto);
  2. what needs an owner, one line each;
  3. one fenced block with every command, in this order: comments, `close_duplicates.py` (one line per parent),
     closes as completed, retitles, `set_status.py` (one line per target Status), then the log requests;
  4. the full body of every comment, each in a `~~~` fence after its command;
  5. the commit list and the fetch commands (global CLAUDE.md template).
- The user may correct any verdict; apply it to the packet, the queue and the round README, and carry corrections
  that apply to later items into the README as a rule.
- Next pass: record what ran (from the user or the live state) in the old round's README, then start over at 1.

## Step mode (on request)

When the user asks to go "one by one", present one group at a time instead of handing over the whole pass: what
failed (verbatim), the cause with two or three log lines, why not something else, the verdict with its queue row,
and only that item's commands and comment bodies. Wait for the user, then merge and commit that item
(`[skip ci] <round dir name>: <refs> <verdict>`) and present the next. The goal stays the same: every item leaves
Inbox.

## Running continuously

The pass can run under `/loop` (for example every 2 hours) with the timeline heuristic as the source. Board and issue
changes stay with the user: each pass ends with its command block, and a pass that finds nothing new says so in one
line.
