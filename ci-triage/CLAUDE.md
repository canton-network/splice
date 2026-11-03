# CI triage

This folder exists only on the shared triage branch (the current one is named in `STATE.md`), which is never
merged.
It holds the triage of failed CI jobs filed in `DACH-NY/cn-test-failures`.

Skills (not discovered from here; install once per sandbox with
`ln -s $PWD/ci-triage/skills/* ~/.claude/skills/`):
- `ci-triage`: triage one issue into an evidence packet.
- `ci-triage-inbox`: work through the board's inbox: fetch, group, triage the groups in parallel, hand over.

ref = a cn-test-failures issue number.

## Layout

| Path | Content |
|---|---|
| `evidence/<ref>-<slug>.md` | evidence packets, one per issue or per group of issues |
| `evidence/INDEX.tsv` | one row per triaged ref; columns in `skills/ci-triage/references/packet-template.md` |
| `known-families.md` | flake families with signature and confirming grep; check before any deep analysis |
| `STATE.md` | current state: inbox, open questions, open fix branches |
| `history/rounds/` | Raymond's per-round READMEs and the host commands he ran, read-only |
| `skills/` | the two skills; command recipes in `skills/ci-triage/references/recipes.md` |
| `scripts/` | scripts for the non-trivial recipes (exports, Status changes, inbox heuristic) |
| `export/` | gitignored; issue exports and logs the user saves on the host |

Paths cited in older issue comments (e.g. `ci-triage/2026-11-02/singles-b.md`) may have moved, possibly more than
once. List every rename of a file, newest first, with
`git log -M --diff-filter=R --name-status --format= -- ci-triage | grep -F '<file name>'`. Raymond's README,
handover and onboarding are on `ray/ci-triage-2026-09-15` (also at `2bf9628c11` on this branch).

## Background

CI runs in GitHub Actions of `canton-network/splice` and `DACH-NY/canton-network-internal`, and in CircleCI
(cluster deployments and cluster tests defined in canton-network-internal). Every failing job files an issue in
`DACH-NY/cn-test-failures` with links to the job and its logs.

The board is project 48 (`DACH-NY/projects/48`). Its `Status` field:
- `Inbox`: default for issues filed by failing CI jobs.
- `Vulnerability Inbox`: default for blackduck dependency scans; not part of this triage.
- `Triaged`: root cause identified.
- `In progress`: a person is assigned and working on it.
- `Infrequent / no repro`: deliberately ignored, rare or not reproducible.
- `Closed`: set automatically when the issue is closed; never set it by hand.

## Access

- `canton-network/splice`: public, including GHA logs and artifacts; use `gh api`.
- `cn-test-failures`, `canton-network-internal`: private. Read them through `ghx` or the user's exports (below).
- CircleCI and canton-network-internal Actions logs: no access; the user saves them to `export/<ref>/` on the host.
- Project 48: never from the sandbox. The user's host `gh` reads and changes it (token with `project` scope,
  SSO-authorized for DACH-NY; "Resource protected by organization SAML enforcement" means the SSO grant is missing).

Pick the source for private data once per session and say which one you use:
```
command -v ghx && ghx /repos/DACH-NY/cn-test-failures | jq -r .full_name
```
- Prints `DACH-NY/cn-test-failures`: use `ghx` (recipe 11). It is a curl wrapper around Raymond's host-side
  credential router `gh-cred-router` (README there has the setup): path only, curl options, pipe to `jq`, page with
  `page=N`; no `--jq`, no `--paginate`; only `/repos/{owner}/{repo}/...` is routed. If it fails, check
  `curl -s -o /dev/null -w '%{http_code}\n' http://host.docker.internal:18555/api/repos/DACH-NY/canton`:
  `401` router up (run the router's sandbox setup), `500`/`connection refused` router not running,
  `403 Approval required for localhost:18555` sandbox network policy missing. Tell the user.
- Otherwise: the user's exports. The sandbox runs in clone mode, so the user writes to `ci-triage/export/` in the
  host repo and you read `/run/sandbox/source/ci-triage/export/`. Recipe 9 exports recent issues, recipe 10 the
  inbox; ask for a rerun when an issue you need is missing or newer than the export.

## Conventions (these override defaults)

- Never modify issues or the board, even if a route would allow it. Print the commands for the user (below);
  Status changes use `scripts/set_status.py` (recipe 12).
- Never infer tracking information by elimination (which issue belongs to which run or job). Ask.
- Pure ASCII in every file, commit message, issue comment and PR text. No em dashes, smart quotes, arrows, emoji.
- No AI attribution anywhere (no Co-Authored-By, no "generated with"). `Signed-off-by` is the only trailer.
- Commits: one subject line plus `-s`. `[skip ci]` on the triage branch (never on a PR branch: it makes the PR
  unmergeable), `[ci]` on fix branches, `[static]` for lint-only.
- The triage branch carries only `ci-triage/`. Never commit memory files or CLAUDE.md files outside `ci-triage/`.
- Do not run sbt compiles or start Canton unless asked; triage works from the run's logs and artifacts.
- Artifacts the sandbox downloads go to `log/<ref>/` (gitignored), with `TMPDIR` under `log/` too; keep `.clog.gz`
  compressed and stream with `zcat`. Never pipe a large `zcat` into `sort` (it spills to `/tmp`); count with `awk`.
  Never put tokens (e.g. a contents `download_url`) into files.
- Fix branches: one per PR, stacked follow-ups on the same branch, named `<user>/fix-<ref>-<slug>` or
  `<user>/backport-<ref>-<pr>-<release-line>` (`<user>` = your git prefix), in their own worktree.

## Scripts and commands for the user

Users have different terminals but the same nix dev shell (`nix/shell.nix`: python3, gh, jq, bash). Anything more
than a one-liner is a script in `ci-triage/scripts/`: Python 3 standard library plus `gh`, no shell-specific syntax,
so it runs on every dev machine. The user runs them from the repo root with `direnv exec . ci-triage/scripts/<name>`,
which works from any shell. Scripts that write a file write it under `ci-triage/export/` and print where.
Scripts are fully typed and pass the same check as the repo's scripts (`scripts/check-python-types.sh`):
`direnv exec . bash -c 'cd ci-triage/scripts && mypy --config-file= --python-version 3.14 --strict --explicit-package-bases *.py'`.

| Script | Where | What |
|---|---|---|
| `export_issues.py --since <date>` | host | recent issues with comments (recipe 9) |
| `export_inbox.py` | host | Status = Inbox items of project 48 (recipe 10) |
| `set_status.py <from> <to> <ref>...` | host | Status change, only from `<from>`; `--dry-run` (recipe 12) |
| `close_duplicates.py <parent> <ref>... --comment '<text>'` | host | close open issues as duplicates of one parent, same comment; `--dry-run` |
| `inbox_from_timelines.py` | sandbox, `ghx` | inbox inferred from issue timelines (ci-triage-inbox step 1) |

A new recipe that needs a loop, pagination or JSON handling becomes a script here, not a pasted block.

One-liners handed to the user (issue comments, closes, retitles) must work in bash, zsh and fish alike: plain `gh`
calls, every argument in single quotes, and no single quote, backslash or newline inside an argument (rephrase:
"the log of sv1", not "sv1's log"). Outside the quotes: no variables, `$`, backticks, heredocs or `&&` chains.
