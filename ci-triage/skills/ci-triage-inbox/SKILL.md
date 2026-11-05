---
name: ci-triage-inbox
description: The triage agent for the cn-test-failures board (DACH-NY project 48) - find the root cause of every Status = Inbox item. Fetch the inbox export, group the items by run, sha and failure signature, triage the groups in parallel background agents with the ci-triage skill, then present them to the user one at a time - evidence, cause and one suggested action (comment and Triaged, close as duplicate, Infrequent / no repro, a log request or a question) with its commands and the full comment text - discuss, apply the user's corrections, and commit. Does not write fixes (that is ci-triage-fix). Use for "triage the inbox", "empty the inbox", "what's in the inbox", "/ci-triage-inbox".
---

# Triage the CI inbox

Context, access and conventions: `ci-triage/CLAUDE.md` on the triage branch. Per-issue work follows the `ci-triage`
skill; this skill is the wrapper for a whole inbox. Recipes: `ci-triage/skills/ci-triage/references/recipes.md`.

Goal: every Inbox item leaves Inbox with a root cause (or the decision that none is worth finding). Per item the
output is one evidence packet, written without the user, and one suggested action, which the user reviews and runs.
Fixes are not written here: the comment says what the fix is (file, change, owner), and the fix agent (`ci-triage-fix`)
picks the issue up from Status Triaged.

## Suggested actions

One per item or group; an action may be several commands.

| Action | When | Commands |
|---|---|---|
| root cause | mechanism established | comment, retitle while the title starts with `GHA Run` or `Pipeline`, `set_status.py Inbox Triaged`; `--add-assignee` when there are one or two clear candidates (below) |
| duplicate | same failure as a parent, confirming grep done (ci-triage step 3) | `close_duplicates.py <parent> <refs>`; a recurrence comment on the parent only when it has neither an assignee nor an owner discussion |
| already fixed | the fix is on main (`git merge-base --is-ancestor`) and the run predates it | comment, close as completed |
| one-off | infra or GitHub incident that recovered, or a first hit without a pattern | comment, `set_status.py Inbox 'Infrequent / no repro'` |
| log request | the evidence is only where no method reaches (CircleCI always) | the exact job and step to save to `export/<ref>/`; the item stays in Inbox until the log is there |
| question | the job mapping is ambiguous, or deciding needs something only the user knows | the question; the item stays in Inbox |

Assignee: suggest one (or two) only when the evidence points at a person: the PR that introduced the failure (the
first failing sha, a bisect over the runs, the commit that changed the failing code), the author of the code or test
the fix touches when git blame shows one clear owner, or the owner of the component when the fix is outside splice
(infra, Canton). Name the evidence next to the suggestion. Otherwise leave it unassigned: the issue then goes to the
fix agent (Triaged without assignee).

Without a root cause after a reasonable effort (about 30 minutes of agent work per group), say what was found and
what would decide it, and suggest root cause with an owner (recurring) or one-off (first hit). Do not hold an item
back because the analysis is unfinished.

## 1. Fetch

- Run `ci-triage/scripts/probe_access.py` once per session and tell the user which method you use per capability
  (CLAUDE.md, Access).
- Read `ci-triage/STATE.md` and the last round's README: actions handed out but not run, open log requests.
- Create the round directory `ci-triage/rounds/<YYYY-MM-DDTHHMMSSZ>[-<slug>]/` (UTC start, `date -u
  +%Y-%m-%dT%H%M%SZ`) and name it in `STATE.md`. Someone resuming it without the chat must see what was found,
  what was handed out and what is open. Background agents write only `<round dir>/<group>-*` and their packets.
- The inbox: `/run/sandbox/source/ci-triage/export/inbox.jsonl` (recipe 10). If it is older than the newest issue
  (recipe 11), say so and ask for a fresh export; meanwhile the timeline heuristic gives a list, labelled inferred:
  ```
  ci-triage/scripts/inbox_from_timelines.py --min-number <oldest open item of the last round> --triage-dir ci-triage --json log/inbox-heuristic.json
  ```
- Recent issues for the duplicate checks: recipe 11, else the recipe 9 export.
- Items whose action from an earlier round is only waiting to be run: re-list that action, do not triage again.
- State the export time and the item count.

## 2. Group and queue

- Same run (per-job issues of one workflow run): one group. Same sha, same test or same flagged line across runs:
  one group. Grep the signature against `known-families.md` and `evidence/INDEX.tsv` and note the suspected family.
- Resolve each issue's job id (ci-triage step 1); an ambiguous mapping becomes a question.
- Order by priority (`ci-triage/CLAUDE.md`, Priority): count the duplicates and the job's runs for every group, and
  give the rank and its reason in the queue line.
- Show the queue once, one line per group (refs, signature, suspected family, rank and why), and write it into the round README
  with a state column (open, presented, handed out, ran, skipped).

## 3. Triage in parallel

Launch one background agent per group, all in one message (forks inherit this context; a fresh agent reads
`ci-triage/CLAUDE.md` and the ci-triage skill first). Brief: the refs with run id, job id and job name, the
suspected family, the packet file name (`ci-triage/evidence/<ref>[-<ref>...]-<slug>.md`), its group name and the
round directory, and the limits:
- ci-triage steps 1 to 8; write only the packet, `<round dir>/<group>-*` and the comment body files under
  `<round dir>/comments/`; no edits to shared files, no commits, no branches;
- return the verdict per ref, the INDEX.tsv rows, the `known-families.md` text, the lesson candidate or
  "Lesson: none", and the suggested action with its commands.
Do not repeat an agent's work in the main thread. Start presenting as soon as the first group in queue order lands.

## 4. Present one group at a time

In at most one screen, in this order:
1. What failed: run, job, test and the failing assertion or flagged line, verbatim.
2. Cause: the mechanism in a few sentences with the two or three log lines that prove it (verbatim, with
   timestamps); what is verified and what is not.
3. Why this and not something else: the family and duplicate check, the alternatives ruled out.
4. Fix, as the comment states it (file, change, owner), or why none is needed; the proposed lesson in one line or
   "Lesson: none".
5. The suggested action: its commands in one fenced block, after re-checking the live state of its refs; then the
   full body of every comment in a `~~~` fence, also when it is posted from a body file and also when it is
   unchanged from an earlier message (the user sometimes posts by hand with changed wording).

Then stop. The user may question the analysis, ask for more digging, disagree, edit the action, skip, or report what
ran. A discussion that changes the cause changes the packet, the comment and the action before anything else
happens. Do not present the next group before the user says so.

## 5. Resolve the group

- Apply the user's corrections to the packet and the body files; a correction that applies to later items (wording,
  a family judgement, a command convention) goes into the round README as a rule and is applied to the rest.
- Merge (main thread only): rows into `evidence/INDEX.tsv` (sorted by ref), the family text into `known-families.md`,
  the accepted lesson into `lessons/<ISO week>.md` (`ci-triage/CLAUDE.md`, Lessons), log requests and owner
  questions into `STATE.md`.
- Record in the round README: verdict, action handed out, ran or skipped, the user's corrections.
- Commit `[skip ci] <round dir name>: <refs> <verdict>`, only the triage paths (CLAUDE.md, two agents).
- A later group that is a duplicate of one already handled says so in one line and reuses its parent's action.

## 6. End of round

One block with the open log requests and questions, the commit list and the fetch commands (global CLAUDE.md
template). When the user reports what ran, or the next export shows it, record that in the round README.

If the user wants everything at once instead, present the verdict table (ref, action, parent or family, one-line
cause) and then every action with its comment bodies in queue order, in one hand-over.
