---
name: ci-triage-fix
description: The fix agent for triaged cn-test-failures issues - work through ci-triage/fix-queue.tsv (written by the ci-triage-inbox agent), write test fixes and backports on fix branches in their own worktrees, pass the CI static checks, hand the user the fetch commands and a PR description that closes the issue, then follow each PR (CI checks, review comments, merge) until the issue is closed. Tracks every fix in ci-triage/fixes.tsv. Does not triage the inbox. Use for "work on the fix queue", "implement the triaged fixes", "what fixes are open", "PR 8238 failed static_tests", "/ci-triage-fix".
---

# Fix triaged CI failures

Context, access and conventions: `ci-triage/CLAUDE.md` on the triage branch. Branch mechanics and the static checks:
`ci-triage/skills/ci-triage/references/recipes.md` section 8. The triage agent (`ci-triage-inbox`) decides what is
wrong; this agent turns its decisions into PRs.

## Ownership (two agents share the triage branch)

The fix agent owns `ci-triage/fixes.tsv` and `ci-triage/fixes/`; everything else under `ci-triage/` belongs to the
triage agent (read it, never edit it). Code changes happen only in fix worktrees (`../fix-<ref>`,
`../backport-<ref>`), never in the triage checkout. Both agents may run at the same time in the same checkout, so commit only your own paths:
`git add <paths> && git commit -s -m '[skip ci] fixes: <ref> <state>' -- <paths>`; on `index.lock` errors wait a few
seconds and retry. Never commit with `-a` or a bare `git add -A` on the triage branch.

When a queued spec turns out to be wrong (the fix does not address the evidence, or the cause looks different once
you are in the code), do not re-triage: set the fix to `blocked` with the reason and tell the user, who hands it back
to the triage agent.

## The tracker

`ci-triage/fixes.tsv`, one row per fix branch, sorted by ref, tab-separated, columns:
`ref branch base tip pr state updated note`
- `ref`: the queue's parent ref; `base`: `main` or the release line, with the sha the branch started from;
  `tip`: the sha handed to the user last; `pr`: splice PR number once the user opened it.
- `state`: `branch` (written, checks passed, waiting for the user to push), `pr` (open), `merged`, `blocked`
  (reason in `note`), `dropped` (with the reason).
`ci-triage/fixes/<ref>.md` per fix: the PR title and description (posted with `--body-file`), what was verified
locally with the commands and their result, and later the PR history (CI failures, review rounds).

## Pass

### 1. Read the queue

- `fix-queue.tsv`, last row per ref (later rows correct earlier ones; `withdrawn` removes the ref).
- Pending = queue refs without a `fixes.tsv` row, plus rows whose queue spec is newer than the tracker row.
- Open = tracker rows in state `branch` or `pr`.
- Print one table: ref, kind, auto, owner, one-line spec, tracker state. Pending `auto = yes` rows of kind `test` or
  `backport` are worked on now without asking, oldest first; the rest are listed:
  - `auto = no`, kind `test` or `backport`: offered; written only when the user says so.
  - `prod`, `infra`, `canton`, `owner`: not written; listed with the suggested owner, unless the user asks for a
    branch (then as for `auto = no`, and production changes get a code-reviewer pass before hand-over).

### 2. Write a fix

- Read the packet and its INDEX row. Check that main does not already contain a fix (`git log origin/main` for the
  test file since the failing sha); if it does, record `dropped` with the commit.
- Worktree and branch per recipe 8: `<user>/fix-<ref>-<slug>` off a fresh `origin/main` (HTTPS fetch in the
  sandbox), or `<user>/backport-<ref>-<pr>-<release-line>` with `cherry-pick -x -s`. One branch per queue row; a
  correction to an open PR is a follow-up commit on the same branch.
- Implement the spec, matching the surrounding code; no code comments unless the code would mislead without one.
- Static checks, mandatory, never skipped (recipe 8, including the sandbox memory fallback; say plainly when lint
  ran only partially). Run the touched test locally only when it is cheap (unit, store, a 1-SV integration test);
  otherwise leave it to the PR's CI and say so.
- Commit `[ci] <one subject line>` with `-s`, no other trailers.

### 3. Hand over a branch

- Write `fixes/<ref>.md`: PR title (`<Suite>: <change>`), description (what failed, in two or three sentences without
  triage-branch references, the change, how it was verified, and a last line
  `Fixes DACH-NY/cn-test-failures#<ref>` plus one such line per covered ref), and the verification record.
- Add or update the tracker row (`branch`), commit the two files on the triage branch.
- Print: the commit list and fetch block (global CLAUDE.md template) for the fix branch, the diff (or its stat when
  over one screen), what was and was not verified, then the PR command and the full description in a `~~~` fence:
  ```
  gh pr create -R canton-network/splice --head <branch> --title '<title>' --body-file ci-triage/fixes/<ref>.md
  ```
  (the user fetches the triage branch first to have the file; the title follows the shell rules of CLAUDE.md).

### 4. Follow open PRs

For each tracker row in state `branch` or `pr` (splice is public: `gh api repos/canton-network/splice/...`):
- `branch` and a PR now exists for the branch (`pulls?head=canton-network:<branch>&state=all`): record the PR
  number, state `pr`.
- Failed checks: read the failed job's log (recipe 1). Caused by the change (format, compile, the touched test):
  fix with a follow-up commit and hand over the new tip. Unrelated flake: say which job and why it is unrelated,
  and let the user re-run it; it is the triage agent's if it is filed in cn-test-failures.
- Review comments: show them with a proposed answer or follow-up commit; changes only after the user agrees.
- Never rewrite or force-push a branch whose PR has reviews; after the user said a PR text is accepted, do not
  change it (it would re-run CI).
- Merged: state `merged`. Check that the `Fixes` lines closed the issues (`gh api
  repos/DACH-NY/cn-test-failures/issues/<ref> --jq .state`); for an issue still open, hand the user its close
  command. Remove the worktree (the branch stays).
- Closed without merge: ask the user why, then `dropped` or `blocked` with the reason.

### 5. Report

One line per tracker change, then the commands (fetch blocks, PR commands, re-runs, closes) in one fenced block,
then the triage-branch commit list. A pass with nothing to do says so in one line.

## Running continuously

The pass can run under `/loop` (for example every hour): new queue rows and PR checks are readable from the
sandbox. Pushing and opening PRs stay with the user.
