---
name: ci-triage-inbox
description: Work through the Status = Inbox items of the cn-test-failures board (DACH-NY project 48) - fetch the inbox, separate items that are already triaged, waiting on purpose or waiting for logs from new ones, group the new ones by run, sha and failure signature, triage each group in a parallel background agent with the ci-triage skill, merge their results into the shared triage files in one commit, and hand the user one block of issue commands. Use for "triage the inbox", "work through the CI inbox", "what's in the inbox", "/ci-triage-inbox".
---

# Work through the CI inbox

Context, access to private data and conventions: `ci-triage/CLAUDE.md` on the triage branch. Per-issue work follows
the `ci-triage` skill; this skill is the batch wrapper around it. Recipes: `ci-triage/skills/ci-triage/references/recipes.md`.

## 1. Fetch

- Read `ci-triage/STATE.md` first: items left open on purpose, open owner questions, unrun commands from the
  last round.
- The inbox: ask the user for a fresh recipe 10 export and read `/run/sandbox/source/ci-triage/export/inbox.jsonl`.
  Without the user but with `ghx`, use the timeline heuristic, and say that the list is inferred:
  ```
  ci-triage/scripts/inbox_from_timelines.py --min-number <oldest open item of the last round> --triage-dir ci-triage --json log/inbox-heuristic.json
  ```
  It lists open issues whose board Status only the automation bot ever changed (= still Inbox), flagged `T` when the
  number already appears in `ci-triage/**.md`; it reads only the first 100 timeline events per issue, and old
  bulk-filed items match too, hence `--min-number`.
- Recent issues for the duplicate checks: recipe 9 export (about two weeks back), or recipe 11 with `ghx`.
- State the export time and the item count before going on.

## 2. Classify every inbox item

One table, one row per item, exactly one class each:

| Class | Test | Action |
|---|---|---|
| done | has a row in `issues/INDEX.tsv` or a root-cause comment, and no newer failure | check the comment against the packet or the family's confirming grep; propose the Status (`Triaged`, or close as duplicate); no new packet |
| waiting | STATE.md lists it as open on purpose (owner question, fix PR pending, umbrella) | leave it; mention it |
| needs-log | the evidence is only in CircleCI or canton-network-internal Actions | name the exact job id and step for the user to save to `export/<ref>/`; one request for all of them |
| assigned | assigned to a person with an active discussion | skip unless asked; mention it |
| new | everything else | group (step 3) |

`Vulnerability Inbox` items are not part of this triage.

## 3. Group the new items

- Same run (per-job issues of one workflow run): one group; check every job's first error, one verdict per batch.
- Same sha, same test or same flagged line across runs: one group. Grep the signature against `known-families.md`
  and `INDEX.tsv` first, and put the suspected family into the group's brief.
- Resolve each issue's job id now (ci-triage step 1); a mapping that needs the user stops that group, not the round.
- Largest groups first. A group is the unit of one agent; a single issue is a group of one.

## 4. Triage the groups in parallel

Launch one background agent per group, all in one message. Prefer forks (they inherit this conversation, including
CLAUDE.md and the classification); a fresh agent must be told to read `ci-triage/CLAUDE.md` and the ci-triage skill
first. Brief each with:
- the refs with run id, job id and job name, the suspected family, and the packet file name to write
  (`ci-triage/issues/<ref>[-<ref>...]-<slug>.md`);
- follow the ci-triage skill, steps 1 to 9, with these limits:
  - write only your own packet file; do not edit `INDEX.tsv`, `known-families.md` or `STATE.md`, do not commit,
    do not create or switch branches (other agents work in the same checkout);
  - a test-side fix is described precisely (file, line, change), not written;
  - return: the verdict per ref, the INDEX.tsv rows (exact TSV), text for `known-families.md` (an occurrence note,
    or a new family section with the next free letter, plus its index row), and the issue commands (CLAUDE.md, Commands for the user).

While the agents run, do not do the same work in the main thread. Report each agent's verdict in one or two lines
when it lands. Two agents proposing the same new family letter, or the same parent ref for different causes, are
reconciled in step 5.

## 5. Merge (main thread only)

- Check each returned packet: ASCII only, commands with verbatim output, file name as briefed.
- Add the rows to `issues/INDEX.tsv` (keep it sorted by ref, one row per ref, 11 tab-separated columns), the family
  text and index rows to `known-families.md`, and refresh `STATE.md`: the inbox table by class with the next
  action per item, open fix branches, open owner questions.
- One commit for the round: `[skip ci] <date>: <one line per verdict group>`.
- Fix branches only when the user asks, one worktree per branch (ci-triage step 9).

## 6. Hand over

- Re-check the live state of every target first (`ghx`, or a fresh recipe 9 export) and drop what is already done;
  say "already done: ..." for those.
- One message: the verdict table (ref, class or verdict, parent, action), what needs an owner, then one fenced
  block with every command in this order: comments, `close_duplicates.py` lines (one per parent, all its
  duplicates and one shared comment), retitles, `set_status.py` lines (one per target Status, all refs on it), log
  requests. One command per issue otherwise, no loops, no placeholders.
- End with the commit list and the fetch commands for the user (global CLAUDE.md template).
