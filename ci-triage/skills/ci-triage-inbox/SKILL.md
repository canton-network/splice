---
name: ci-triage-inbox
description: Work through the Status = Inbox items of the cn-test-failures board (DACH-NY project 48) - fetch the inbox, separate items that are already triaged, waiting on purpose or waiting for logs from new ones, group the new ones by run, sha and failure signature, then either (batch mode) triage all groups in parallel background agents with the ci-triage skill, merge them in one commit and hand the user one block of issue commands, or (step mode) walk the user through one item at a time - cause, evidence, resolution and only that item's commands - and resolve it before moving to the next; findings and commands are persisted per round in ci-triage/rounds/ so a round can be resumed or handed over. Use for "triage the inbox", "work through the CI inbox", "what's in the inbox", "go through the inbox one by one", "/ci-triage-inbox".
---

# Work through the CI inbox

Context, access to private data and conventions: `ci-triage/CLAUDE.md` on the triage branch. Per-issue work follows
the `ci-triage` skill; this skill is the wrapper around it for a whole inbox. Recipes: `ci-triage/skills/ci-triage/references/recipes.md`.

## Modes

| Mode | For | Shape |
|---|---|---|
| batch | users who review the outcome as a whole | all groups in parallel (step 4), one merge (step 6), one hand-over with every command (step 7) |
| step | users who want to understand and resolve every issue before the next | one item at a time (step 5): cause, evidence and resolution, only that item's commands, wait for the user, merge and commit that item, then the next |

Pick the mode from the user's words ("one by one", "each issue", "step by step": step; "all at once", "just the
commands", "batch": batch); otherwise ask once, after the classification (step 2). Record the mode in the round
README. The user may switch at any point; the switch applies to the items not yet presented.

## 0. The round directory

Every round persists its working state in `ci-triage/rounds/<YYYY-MM-DDTHHMMSSZ>[-<slug>]/` (UTC start time,
`date -u +%Y-%m-%dT%H%M%SZ`; the slug is optional and free). What goes in there is up to whoever writes it; the
test is that someone resuming the round without the chat (another person, a recreated sandbox) can see what was
found, what was handed to the user, and what is still open. Ownership, so parallel agents never collide:
- each background agent gets a group name in its brief and writes only `<round dir>/<group>-*` (plus its
  evidence packets in `evidence/`);
- the main thread owns every other file in the round directory and the shared files (`evidence/INDEX.tsv`,
  `known-families.md`, `STATE.md`).
Nothing in a round directory is executed by an agent; commands in it are for the user.

## 1. Fetch

- Run `ci-triage/scripts/probe_access.py` and tell the user which method you use per capability (CLAUDE.md,
  Access); ask once for a better method where the round needs one, and continue with the workaround otherwise.
- Read `ci-triage/STATE.md`: the current round (if one is still open, read its directory and continue it
  instead of starting over), items left open on purpose, open owner questions.
- Otherwise create the round directory now and name it in `STATE.md`.
- The inbox: ask the user for a fresh recipe 10 export and read `/run/sandbox/source/ci-triage/export/inbox.jsonl`.
  Without the user but with read access to cn-test-failures issues (`ghx` or `gh api`), use the timeline
  heuristic, and say that the list is inferred:
  ```
  ci-triage/scripts/inbox_from_timelines.py --min-number <oldest open item of the last round> --triage-dir ci-triage --json log/inbox-heuristic.json
  ```
  It lists open issues whose board Status only the automation bot ever changed (= still Inbox), flagged `T` when the
  number already appears in `ci-triage/**.md`; it reads only the first 100 timeline events per issue, and old
  bulk-filed items match too, hence `--min-number`.
- Recent issues for the duplicate checks: recipe 11 (`ghx` or `gh api`), else the recipe 9 export (about two
  weeks back).
- State the export time and the item count before going on.

## 2. Classify every inbox item

One table, one row per item, exactly one class each:

| Class | Test | Action |
|---|---|---|
| done | has a row in `evidence/INDEX.tsv` or a root-cause comment, and no newer failure | check the comment against the packet or the family's confirming grep; propose the Status (`Triaged`, or close as duplicate); no new packet |
| waiting | STATE.md lists it as open on purpose (owner question, fix PR pending, umbrella) | leave it; mention it |
| needs-log | the evidence is only where no available method reaches (CircleCI always; canton-network-internal Actions without `ghx` or a token for it) | name the exact job id and step for the user to save to `export/<ref>/`; one request for all of them |
| assigned | assigned to a person with an active discussion | skip unless asked; mention it |
| new | everything else | group (step 3) |

`Vulnerability Inbox` items are not part of this triage.

## 3. Group the new items

- Same run (per-job issues of one workflow run): one group; check every job's first error, one verdict per batch.
- Same sha, same test or same flagged line across runs: one group. Grep the signature against `known-families.md`
  and `INDEX.tsv` first, and put the suspected family into the group's brief.
- Resolve each issue's job id now (ci-triage step 1); a mapping that needs the user stops that group, not the round.
- Largest groups first. A group is the unit of one agent; a single issue is a group of one.

## 4. Batch mode: triage the groups in parallel

Launch one background agent per group, all in one message. Prefer forks (they inherit this conversation, including
CLAUDE.md and the classification); a fresh agent must be told to read `ci-triage/CLAUDE.md` and the ci-triage skill
first. Brief each with:
- the refs with run id, job id and job name, the suspected family, and the packet file name to write
  (`ci-triage/evidence/<ref>[-<ref>...]-<slug>.md`);
- its group name and the round directory: it may write `<round dir>/<group>-*` and nothing else besides its
  packets;
- follow the ci-triage skill, steps 1 to 9, with these limits:
  - do not edit `INDEX.tsv`, `known-families.md` or `STATE.md`, do not commit, do not create or switch branches
    (other agents work in the same checkout);
  - a fix is described precisely (file, line, change), not written; say whether it qualifies for an automatic
    fix branch (ci-triage step 9: established cause, test-only, one screen), which the main thread then creates;
  - make available to the main thread, in its round files or its final message: the verdict per ref, the INDEX.tsv
    rows (exact TSV), text for `known-families.md` (an occurrence note, or a new family section with the next free
    letter, plus its index row), and the issue commands (CLAUDE.md, Commands for the user; comments in the shape of
    `ci-triage/skills/ci-triage/references/issue-comment.md`).

While the agents run, do not do the same work in the main thread. Report each agent's verdict in one or two lines
when it lands. Two agents proposing the same new family letter, or the same parent ref for different causes, are
reconciled in step 6.

## 5. Step mode: one item at a time

An item is one group from step 3, or one item of class done (its cause comes from an earlier round, so the user
sees it too). Waiting, assigned and needs-log items are listed once, not presented one by one, unless the user asks.

- Queue: show it once, one line per item (refs, class, one-line signature, suspected family), new groups largest
  first, then the done items. Write it into the round README with a state column (open, presented, resolved,
  skipped) so the round can be resumed.
- Triage the current item with the ci-triage skill, in the main thread or in a fork briefed as in step 4. At most the
  next item may be prepared in a background fork while the user reads; present only one item at a time, and redo
  a prepared item when the user's feedback on the current one changes it.
- Present the item in at most one screen, in this order:
  1. What failed: run, job, test and the failing assertion or flagged line, verbatim.
  2. Cause: the mechanism in a few sentences, with the two or three log lines that prove it (verbatim, with
     timestamps); say what is verified and what is not.
  3. Why this and not something else: the family and duplicate check, and the alternatives ruled out.
  4. Resolution: duplicate of, family (existing or new), the fix (file, change, owner) and the target Status, and
     the proposed lesson in one line, or "Lesson: none" (`ci-triage/CLAUDE.md`, Lessons). When
     the fix qualifies (ci-triage step 9: established cause, test-only, one screen), create the fix branch before
     presenting and show its diff here; otherwise offer it.
  5. Its commands only (comment, close as duplicate, retitle, Status; no new comment on an issue whose existing
     root-cause comment still holds), in one `~~~` fenced block, after re-checking
     the live state of its refs (step 7, first bullet). Comments follow
     `ci-triage/skills/ci-triage/references/issue-comment.md`. Print the full body of every comment in chat for
     review, also when it is posted from a body file and also when it is unchanged from an earlier message.
- Stop and wait. The user may ask, disagree, ask for a fix branch, edit the commands, skip, or report that the
  commands ran. Do not present the next item before the user says so.
- Resolve: apply the user's corrections to the packet, merge this item (step 6, for its rows only), record its
  outcome in the round README (verdict, commands handed out, ran or skipped, the user's corrections), add the
  accepted lesson to `ci-triage/lessons/<ISO week>.md`, and commit:
  `[skip ci] <round dir name>: <refs> <verdict>`. A correction that applies to later items (wording, a family
  judgement, a command convention) is noted in the README and applied to the rest of the queue.
- A later item that is a duplicate of a parent already handled says so in one line and reuses its parent's
  resolution.
- End of queue: one block with the log requests for the needs-log items, the list of waiting and assigned items,
  and the commit list with the fetch commands (global CLAUDE.md template); then close the round in `STATE.md` as in
  step 7.

## 6. Merge (main thread only)

- Check each returned packet and round file: ASCII only, commands with verbatim output, file names as briefed.
- Add the rows to `evidence/INDEX.tsv` (keep it sorted by ref, one row per ref, 11 tab-separated columns), the family
  text and index rows to `known-families.md`, lessons to `lessons/<ISO week>.md` (`ci-triage/CLAUDE.md`, Lessons;
  in batch mode listed in the hand-over for the user to accept), and refresh `STATE.md`: the inbox table by class with the next
  action per item, open fix branches, open owner questions.
- Write the round's own summary into the round directory (main-thread files), then commit: in batch mode one
  commit for the round, `[skip ci] <round dir name>: <one line per verdict group>`; in step mode one per item
  (step 5). Commit again whenever the round's state changes
  (hand-over, user feedback), so an interrupted round can be resumed from the branch.
- Fix branches per ci-triage step 9: created without asking for established, test-only, one-screen fixes (batch
  mode: by the main thread after the merge, since agents share the checkout), otherwise only when the user asks; one
  worktree per branch. List them in `STATE.md` (open fix branches) and in the hand-over.

## 7. Hand over (batch mode)

- Re-check the live state of every target first (recipe 11, or a fresh recipe 9 export) and drop what is already done;
  say "already done: ..." for those.
- Write the hand-over into the round directory and commit it, then print it: the verdict table (ref, class or
  verdict, parent, action), what needs an owner, then one fenced block with every command in this order: comments, `close_duplicates.py` lines (one per parent, all its
  duplicates and one shared comment), retitles, `set_status.py` lines (one per target Status, all refs on it), log
  requests. One command per issue otherwise, no loops, no placeholders.
- End with the commit list and the fetch commands for the user (global CLAUDE.md template).
- When the user reports what ran (or a later export shows it), record that in the round directory and close the
  round in `STATE.md`.
