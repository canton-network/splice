---
name: ci-triage-inbox
description: 'The triage agent for the cn-test-failures board (DACH-NY project 48) - find the root cause of every Status = Inbox item. Fetch the inbox export, group the items by run, sha and failure signature, triage the groups in parallel background agents with the ci-triage skill, then present them to the user one at a time - evidence, cause and one suggested action (comment and Triaged, close as duplicate, Infrequent / no repro, a log request or a question) with its commands and the full comment text - discuss, apply the user''s corrections, and commit. Does not write fixes (that is ci-triage-fix). Use for "triage the inbox", "empty the inbox", "what''s in the inbox", "/ci-triage-inbox".'
argument-hint: '[repo=DACH-NY/cn-test-failures] [branch=app-dev/ci-triage] [dir=cn-test-failures] [access=ro|rw]'
---

# Triage the CI inbox

Context, access and conventions: `<D>/CLAUDE.md` in the triage dataset. Where the dataset is, its arguments
(`repo`, `branch`, `dir`, `access`; given here: $ARGUMENTS), setup, commits and hand-over: section Dataset of the
`ci-triage` skill (`.claude/skills/ci-triage/SKILL.md`); `<D>` is `<dir>/ci-triage`. Per-issue work follows the
`ci-triage` skill; this skill is the wrapper for a whole inbox. Recipes: `.claude/skills/ci-triage/references/recipes.md`.

Goal: every Inbox item leaves Inbox with a root cause (or the decision that none is worth finding). Per item the
output is one evidence packet, written without the user, and one suggested action, which the user reviews and runs.
Fixes are not written here: the comment says what the fix is (file, change, owner), and the fix agent (`ci-triage-fix`)
picks the issue up from Status Triaged.

## Suggested actions

One per item or group; an action may be several commands.

| Action | When | Commands |
|---|---|---|
| root cause | mechanism established | comment, retitle while the title starts with `GHA Run` or `Pipeline`, `set_status.py Inbox Triaged`; `--add-assignee <owner(s)>` from the owner search (below); none when no owner is found |
| duplicate | same failure as a parent, confirming grep done (ci-triage step 3) | the triage comment (nine sections, as for a root cause; `comments/<ref>.md`) on the duplicate itself, then `close_duplicates.py <parent> <ref> --comment '<one line>'`. Nothing is posted on the parent, not even a recurrence comment: it is the origin and stays as it is (the user decides about it separately); the close already links the two. The parent's script is not extended with its duplicates that arrive later: each new duplicate gets its own `ci-<ref>.sh`. The comment is posted even when the duplicate is already closed (the script posts first, closes only while open) |
| already fixed | the fix is on main (`git merge-base --is-ancestor`) and the run predates it | comment, close as completed |
| one-off | infra or GitHub incident that recovered, or a first hit without a pattern | external incident: comment and close (the user closes these directly); first hit: comment, `set_status.py Inbox Triaged` (`Infrequent / no repro` only after about 2 weeks without a duplicate, never for a new issue) |
| log request | the evidence is only where no method reaches (CircleCI always) | the exact job and step to save to `log/ci-triage-export/<ref>/` on the host; the item stays in Inbox until the log is there |
| question | the job mapping is ambiguous, or deciding needs something only the user knows | the question; the item stays in Inbox |

Before a hand-over script is given to the user, run `.claude/skills/ci-triage/scripts/lint_hand_over.py <script>`: it
checks that every issue with a root-cause comment is retitled while its title still starts with `GHA Run` or `Pipeline`,
that the comment file exists and ends with the hidden marker, and that the status is set (or the issue closed). Fix
what it reports. Write the script to `/home/agent/ci-<ref>.sh` (one file per issue or group, named after the ref),
never to a shared `ci.sh`: other sessions in the same sandbox overwrite it.

Assignee: always look for the owner of every issue that goes to Triaged, and suggest all of them when there are
several. Find them from the history, not from the failing test alone:
- the PR that introduced the failure (the first failing sha, a bisect over the runs, the commit that changed the
  failing code or config);
- the history of the files the fix touches (`git log`, `git blame`: who wrote and who recently changed them), the
  related functional changes (the PR that added the feature, check, workflow step or config the failure is about),
  and recent fixes of the same area or failure family (`git log -S`, `known-families.md`, `evidence/INDEX.tsv`,
  closed issues and their fix PRs);
- for a fix outside splice (infra, workflows, config, Canton), the history of that repository's files the same way
  (`gh api repos/<repo>/commits?path=<file>`), or the component owner.
Authorship alone does not make someone the owner; decide from the root cause which case it is, and say which:
- the failure comes from a specific change (it started with that change, or after it, e.g. a new check, a refactor,
  a bump that did not update every place): the person who made or merged that change, because the fix completes it;
- it comes from a recent change to code that had worked: the author of the recent change, not the original author of
  the code;
- it comes from old, long-standing code (a latent race, an old timeout): not its original author by default, but
  whoever maintains or recently changed that area, and the author only when still active there.
A reviewer who only approved a change, or someone whose recent edits to the same file are unrelated to the cause, is
not an owner.
For old code, the owner is who owns the behaviour that fails: the author of the mechanism who is still active, or the
person who made the latest decision about it (on its tracker, its design issue). Who last edited a nearby line or the
list the fix extends is not the owner by that alone; recency only breaks a tie between people who own the behaviour
(11780: the breaker's author, who decided its tracker, not the author of a later one-entry ignore).
An owner is a critical decision: when the reviewer changes it, or the main thread wants to change it after the triage,
it is a contested owner. Present the candidates with their evidence and let the user choose; do not pick one alone. Name the evidence next to every suggested login (the PR or commit) and the case it falls under. External contributors: when the person found is an external contributor (not a member of the organisation; a
login ending in `-da` is only a hint, check with `gh api orgs/<org>/members/<login>` or the PR's `author_association`),
do not assign them; assign the internal owner or reviewer of that test or production change instead: the reviewers
who approved and the person who merged the PR (`gh pr view <n> --json reviews,mergedBy,author`), the `CODEOWNERS`
entry of the files, or else the internal people who most recently changed or reviewed the same files or the same
area. Name the external PR and the reason next to the internal login.
Prefer people active on the code recently over
the original author of old code; skip bots and release-merge commits. If no owner can be found after this search,
leave the issue unassigned: it then goes to the fix agent (Triaged without assignee). Never guess a login. Duplicates
are not assigned; the parent is.

Without a root cause after a reasonable effort (about 30 minutes of agent work per group), say what was found and
what would decide it, and suggest root cause with an owner (recurring) or one-off (first hit). Do not hold an item
back because the analysis is unfinished.

## 1. Fetch

- Set up the dataset clone (ci-triage, Dataset; it starts with `probe_access.py`), then tell the user which method
  you use per capability (`<D>/CLAUDE.md`, Access).
- Read `<D>/STATE.md` and the last round's README: actions handed out but not run, open log requests.
- Create the round directory `<D>/rounds/<YYYY-MM-DDTHHMMSSZ>[-<slug>]/` (UTC start, `date -u
  +%Y-%m-%dT%H%M%SZ`) and name it in `STATE.md`. Someone resuming it without the chat must see what was found,
  what was handed out and what is open. Background agents write only `<round dir>/<group>-*` and their packets.
- The inbox: `/run/sandbox/source/log/ci-triage-export/inbox.jsonl` (recipe 10). If it is older than the newest issue
  (recipe 11), say so and ask for a fresh export; meanwhile the timeline heuristic gives a list, labelled inferred:
  ```
  .claude/skills/ci-triage/scripts/inbox_from_timelines.py --min-number <oldest open item of the last round> --triage-dir <D> --json log/inbox-heuristic.json
  ```
- Recent issues for the duplicate checks: recipe 11, else the recipe 9 export.
- Items whose action from an earlier round is only waiting to be run: re-list that action, do not triage again.
- State the export time and the item count.

## 2. Group and queue

- Same run (per-job issues of one workflow run): one group. Same sha, same test or same flagged line across runs:
  one group. Grep the signature against `known-families.md` and `evidence/INDEX.tsv` and note the suspected family.
- Resolve each issue's job id (ci-triage step 1); an ambiguous mapping becomes a question.
- Order by priority (`<D>/CLAUDE.md`, Priority): count the duplicates and the job's runs for every group, and
  give the rank and its reason in the queue line.
- Show the queue once, one line per group (refs, signature, suspected family, rank and why), and write it into the round README
  with a state column (open, presented, handed out, ran, skipped).

## 3. Triage in parallel

Launch one background agent per group, all in one message (forks inherit this context; a fresh agent reads
`<D>/CLAUDE.md` and the ci-triage skill first). Brief: the dataset arguments (`dir` at least; the clone is already
set up, so agents do not fetch), the refs with run id, job id and job name, the
suspected family, the packet file name (`<D>/evidence/<ref>[-<ref>...]-<slug>.md`), its group name and the
round directory, and the limits:
- ci-triage steps 1 to 8; write only the packet, `<round dir>/<group>-*` and the comment body files under
  `<round dir>/comments/`; no edits to shared files, no commits, no branches;
- return the verdict per ref, the INDEX.tsv rows, the `known-families.md` text, the lesson candidate or
  "Lesson: none", and the suggested action with its commands.
Do not repeat an agent's work in the main thread. Start presenting as soon as the first group in queue order lands.

Models (the Agent tool's `model`); name the plan in the queue line.
- Simple tasks, `sonnet`: confirming a duplicate of a known family (step 3 grep, then the comment), rewriting an
  old-format comment whose verdict is settled, fetching logs, resolving runs and jobs, counting rates, writing GCP
  or `gh` export scripts.
- Reasoning, `opus`: a new failure or a first hit; a cause that rests on timing across several logs (a race, a
  stall, an ordering step); a cause in code or version logic (11811); a performance regression (11784); a contested
  owner. A `sonnet` agent that returns "not established" or a "likely" link it could not show hands the group to
  `opus`.
- Final review, `opus`, for every group before it is presented, by a fresh agent that did not triage it. Brief: the
  packet, the comment and the suggested action, plus access to the raw logs and code; the job is to try to refute,
  not to confirm. It checks the final conclusion and every critical decision:
  - the conclusion: the mechanism is shown in the logs or code it names; it accounts for the size of the failure
    (ci-triage, Rules of evidence: Magnitude); each quoted number means what the comment says it means;
  - duplicate or not: the signature really matches the parent; the parent is open, or closed without a fix the run
    lacks; the parent is the origin (oldest open issue of the cause);
  - the fix: it would bring the failure under its limit or remove it, and it is in the right place (test, app,
    Canton, infra, a backport);
  - the owner: the case under the Assignee rules (specific change, recent change, old code), not authorship or
    approval alone; external contributors replaced;
  - the action: retitle, status, assignee, close; nothing posted on a parent.
  It returns "no change" or each disagreement with the evidence that settles it. Who fixes the drift:
  - small drift (a number, a metric's scope, a quote, an owner reason, a title): the main thread checks the
    reviewer's evidence and fixes the comment, the packet and the script itself;
  - large drift (the mechanism, the duplicate decision, the parent, the fix or the owner changes): the reviewer
    agent rewrites the packet and the comment (SendMessage to it; it has the evidence and is not anchored on the
    first answer), never the agent that triaged it; then a short check of only the changed parts by the main thread
    or a fresh agent;
  - a disagreement that neither side's evidence settles: a question to the user with both positions and their
    evidence; the group is not handed out until it is decided.
  Present with "Reviewed: no change" or the list of changes and who made them. A duplicate of a known family with a clean
  confirming grep gets a short review (signature, parent state and fix, the close comment), not a re-triage.
Precedents: the first pass had the mechanism wrong in 11766, 11773 and 11774, and 11784 was posted blaming a partition
probe that explains about 90 ms of a 1.9 s regression.

## 4. Present one group at a time

Write in the style of the comment (`issue-comment.md`, Style): short, direct sentences, one idea each. Use the sections of the comment (`ci-triage/references/issue-comment.md`, Shape), in this order, with a diagram in
Test Environment and Actual Behavior in almost every case, even a simple one (Mermaid, rendered by the host, syntax
checked):
1. `## Summary`: one line, what broke and how bad (run, job, test, the failing assertion or flagged line verbatim).
2. Test Purpose.
3. Test Environment: the architecture the job runs in (network diagram, failing part marked).
4. Expected Behavior.
5. Actual Behavior: what happened, with the two or three log lines that prove it (verbatim, with timestamps, each
   with its log file name) and a timeline diagram when the order matters.
6. Reproduction Steps, or Repro Rate when it is flaky (hits and runs, first occurrence or not).
7. Root Cause: the mechanism; what is verified and what is not; why this and not something else (the family and
   duplicate check, the alternatives ruled out).
8. Fix, as the comment states it (file, change), or why none is needed; the proposed lesson in one line or
   "Lesson: none".
9. Owner(s) with the reason for each, or none found.
10. The suggested action: its commands in one fenced block, after re-checking the live state of its refs and after
   committing and handing over every file the commands use (body files, packets), so the user has them; then the
   full body of every comment in a `~~~` fence, also when it is posted from a body file and also when it is
   unchanged from an earlier message (the user sometimes posts by hand with changed wording).

Before presenting a group a background agent triaged, check that every claim in its comment has a packet section with
the command and output that support it (ci-triage step 8), and grep every log line its comment and packet quote against the
log it names: a quote must be a real line (shortened only with `...`), never reconstructed from several lines or
from a truncated message. Check its interpretations of names and versions the same way (e.g. "latest" against
DarResources).

Then stop. The user may question the analysis, ask for more digging, disagree, edit the action, skip, or report what
ran. A discussion that changes the cause changes the packet, the comment and the action before anything else
happens. Do not present the next group before the user says so.

## 5. Resolve the group

- Apply the user's corrections to the packet and the body files (a claim corrected or added in the main thread gets its
  command and output in the packet too, so packet and comment stay in step); a correction that applies to later items (wording,
  a family judgement, a command convention) goes into the round README as a rule and is applied to the rest.
- Merge (main thread only): rows into `evidence/INDEX.tsv` (sorted by ref), the family text into `known-families.md`,
  the accepted lesson into `lessons/<ISO week>.md` (`<D>/CLAUDE.md`, Lessons), log requests and owner
  questions into `STATE.md`.
- Record in the round README: verdict, action handed out, ran or skipped, the user's corrections.
- Commit `<round dir name>: <refs> <verdict>` in the dataset clone, only the triage paths (ci-triage, Dataset), and
  hand it over before presenting the next commands that use the committed files.
- A later group that is a duplicate of one already handled says so in one line, gets its own triage comment and its own script, and does not touch the parent or its script.

## 6. End of round

One block with the open log requests and questions, the commit list and the hand-over (ci-triage, Dataset: the
push, or the bundle with its `sbx cp` and `git pull` commands). When the user reports what ran, or the next
export shows it, record that in the round README.

If the user wants everything at once instead, present the verdict table (ref, action, parent or family, one-line
cause) and then every action with its comment bodies in queue order, in one hand-over.
