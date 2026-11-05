---
name: ci-triage-fix
description: The fix agent for cn-test-failures issues with Status Triaged and no assignee - pick one from the board export (or the issue the user names), read its root cause from the issue comments and the evidence packet if there is one, propose a fix and iterate on it with the user, then write it on a fix branch in its own worktree, pass the CI static checks, and hand over the fetch commands, the PR title and description (closing the issue) and the board commands; also follows the user's open fix PRs (CI failures, review comments). Use for "fix a triaged issue", "what can be fixed", "fix #11165", "PR 8238 failed static_tests", "/ci-triage-fix".
---

# Fix a triaged CI failure

Context, access and conventions: `ci-triage/CLAUDE.md` on the triage branch. Branch mechanics and the static checks:
`ci-triage/skills/ci-triage/references/recipes.md` section 8. The root cause comes from the triage (issue comments,
packets); this skill turns it into a PR, together with the user.

## 1. Candidates

- Source: `/run/sandbox/source/ci-triage/export/triaged.jsonl` and `in-progress.jsonl` (recipe 10, `export_board.py`;
  ask for a fresh one when it is older than a day), or the issue the user names (read it live, recipe 11).
- Candidates: Triaged, no assignee, no open entry in `closingPullRequests`. Issues with an assignee belong to that
  person; issues with an open closing PR are in flight (step 5).
- For each candidate, the fix as the issue states it: the last root-cause comment (any author: issues are also
  triaged by hand), and the packet when `evidence/INDEX.tsv` has the ref. Classify: test (test code or test
  config), backport (a fix on main missing on a release line), production (splice code, Daml, deployment config),
  not ours (infra, Canton, an external owner: suggest assigning instead), unclear (no fix stated).
- Show one line per candidate, in priority order (`ci-triage/CLAUDE.md`, Priority): ref, title, kind, the fix in a
  few words, failures in the last week against the job's runs, and the rank with its reason. Recount: an issue
  triaged as a rare flake may have gathered duplicates since. The user picks; the rest of this skill is one issue at
  a time.

## 2. Propose

- Read the issue, its duplicates and the packet; check that main has no fix yet (`git log origin/main` on the
  touched files since the failing sha; `git merge-base --is-ancestor` for backports).
- Present in one screen: the cause in two or three sentences with the decisive log line, the proposed change (file,
  diff sketch), the alternatives considered and why this one, what it does not cover, and the suggested owner (git
  blame of the lines it touches). Give the board command to take the issue:
  ```
  gh issue edit <ref> -R DACH-NY/cn-test-failures --add-assignee @me
  direnv exec . ci-triage/scripts/set_status.py Triaged 'In progress' <ref>
  ```
- Wait for the user. Expect disagreement: the user often wants a smaller or different change. Iterate until the user
  agrees on the change. If the discussion changes the root cause, update the packet (only that file, committed on
  the triage branch with `-- <path>`) and give the correction as a comment for the user to review and post.
- A draft branch may be written before the user agrees, to make the proposal concrete; it is only a proposal and is
  rewritten freely until handed over.

## 3. Write

- Worktree and branch per recipe 8: `<user>/fix-<ref>-<slug>` off a fresh `origin/main` (HTTPS fetch in the
  sandbox), or `<user>/backport-<ref>-<pr>-<release-line>` with `cherry-pick -x -s`. One branch per PR.
- Match the surrounding code; a code comment only when the code would mislead without it (the user may ask for one).
- Static checks, mandatory, never skipped (recipe 8, with the sandbox memory fallback; say when lint ran only
  partially). Run the touched test locally only when cheap (unit, store, a 1-SV integration test); otherwise leave
  it to the PR's CI and say so.
- Commit `[ci] <one subject line>` with `-s`, no other trailers. Production changes get a code-reviewer pass first.

## 4. Hand over

Print, in this order:
1. The commit list and the fetch block (global CLAUDE.md template) for the fix branch, and the diff (or its stat
   when longer than a screen).
2. What was and was not verified.
3. The PR title (`<Suite or component>: <change>`) and the description in a `~~~` fence: what failed in two or three
   sentences (no triage-branch references), the change, how it was verified, and as last lines
   `Fixes DACH-NY/cn-test-failures#<ref>`, one per issue it fixes. The merge then closes the issues.

## 5. Follow open PRs

When the user asks (or names a PR): the user's In progress issues with an open `closingPullRequests` entry, or the
PR named. splice is public, `gh api repos/canton-network/splice/...` reads it.
- Failed checks: read the failed job's log (recipe 1). Caused by the change (format, compile, the touched test): a
  follow-up commit and a new hand-over. Unrelated flake: say which job and why it is unrelated, so the user re-runs
  it; a filed flake belongs to the triage agent.
- Review comments: show them with a proposed answer or follow-up commit; changes only after the user agrees.
- Never rewrite or force-push a branch whose PR has reviews, and do not change an accepted PR text (it re-runs CI).
- Merged: check that the issues closed (`gh api repos/DACH-NY/cn-test-failures/issues/<ref> --jq .state`) and give
  the close command for any still open. Remove the worktree; the branch stays.
