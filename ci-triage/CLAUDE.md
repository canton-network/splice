# CI triage

This folder exists only on the shared triage branch (the current one is named in `STATE.md`), which is never
merged.
It holds the triage of failed CI jobs filed in `DACH-NY/cn-test-failures`.

Skills (not discovered from here; install once per sandbox with
`ln -s $PWD/ci-triage/skills/* ~/.claude/skills/`):
- `ci-triage`: triage one issue into an evidence packet and one suggested action on the issue.
- `ci-triage-inbox`: the triage agent, for Status Inbox. Triages the groups in parallel, then presents them one at a
  time: cause, evidence, the suggested action with its commands and comment bodies; the user reviews and runs it.
- `ci-triage-fix`: the fix agent, for Status Triaged without an assignee. Proposes a fix, iterates with the user,
  writes the fix branch and the PR text, and follows the PR.

The board is the only work queue: Inbox (triage agent), Triaged without assignee (fix agent), In progress with an
assignee (that person; the fix agent only for the user's own PRs). Both agents run as separate sessions in the same
sandbox and checkout: the fix agent needs the packets and the scripts, and the user fetches both kinds of branches
from one remote. The fix agent changes code only in fix worktrees; on the triage branch it touches only a packet
whose cause changed. Each agent commits only its own paths (`git add <paths> && git commit -s -m '...' -- <paths>`,
never `-a`); on `index.lock` errors wait and retry. Run one sbt build at a time (16 GB: the static checks alone come
close), so the triage agent does not compile while a fix is checked.

ref = a cn-test-failures issue number.

## Layout

| Path | Content |
|---|---|
| `evidence/<ref>-<slug>.md` | evidence packets, one per issue or per group of issues |
| `evidence/INDEX.tsv` | one row per triaged ref; columns in `skills/ci-triage/references/packet-template.md` |
| `known-families.md` | flake families with signature and confirming grep; check before any deep analysis |
| `STATE.md` | triage state: current round, log requests, open owner questions |
| `lessons/<YYYY>-W<ww>.md` | weekly lessons for the splice engineers: test-code patterns behind the week's failures (ISO week) |
| `rounds/<YYYY-MM-DDTHHMMSSZ>[-<slug>]/` | one directory per inbox round: findings, commands handed out, open ends |
| `skills/` | the three skills; command recipes in `skills/ci-triage/references/recipes.md`, comment shape in `issue-comment.md` |
| `scripts/` | scripts for the non-trivial recipes (exports, Status changes, inbox heuristic) |
| `export/` | gitignored; issue exports and logs the user saves on the host |

Paths cited in older issue comments (e.g. `ci-triage/2026-11-02/singles-b.md`) may have moved, possibly more than
once. List every rename of a file, newest first, with
`git log -M --diff-filter=R --name-status --format= -- ci-triage | grep -F '<file name>'`. The previous triage
branch `ray/ci-triage-2026-09-15` has the earlier README, handover and onboarding (also at `2bf9628c11` on this branch).

Rounds: every inbox round (skill `ci-triage-inbox`) persists its working state in its own directory under
`rounds/`, named by its UTC start time (seconds) and an optional free slug, so a round can be resumed or handed over
without the chat. Background agents write only `<round dir>/<group>-*` for the group name in their brief; the main
thread owns the rest of the round directory and the shared files. What the files contain is up to the writer.
The rounds up to 2026-11-02 come from the previous triage branch (named by the time of its first commit for each).

Lessons: triage is on a weekly rotation, and each week ends with `lessons/<ISO week>.md`, a Markdown read of at most
10 minutes (about 6 lessons, 1500 words) for the engineers maintaining splice. A lesson teaches a test-code pattern
others would plausibly repeat, backed by a resolved item: title (the pattern), the general rule and why it bites, a
mechanism from splice as the example, Do (the right way, with a short generic snippet). No issue or PR numbers, test
or author names, and no account of what happened: it teaches, it does not report or blame. Which items a lesson
comes from is recorded in the round README, not in the lesson. Infra one-offs, Canton bugs and plain duplicates are
not lessons. Over the cap, keep the lessons most likely to recur.

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

Policy: the triage may read cn-test-failures (issues), canton-network-internal (contents, Actions, pull requests),
DACH-NY/canton (contents) and splice (public). It never writes: issue changes, closes and the board (project 48)
are commands the user runs on the host (recipes 10 and 12, `scripts/close_duplicates.py`). Fine-grained tokens
cannot grant a single project, so the board is only ever read and changed from the user's host `gh` (token with
`project` scope, SSO-authorized for DACH-NY; "Resource protected by organization SAML enforcement" means the SSO
grant is missing).

Not every user has every token, and an sbx sandbox injects a single GitHub token that cannot express per-repo
permissions. So pick the method per capability, best first:

1. `ghx`: curl wrapper around the host-side credential router `gh-cred-router`, which holds one token per repo
   and checks every request against per-repo rules. Covers everything above if the router config allows it.
2. `gh api` with the sandbox's own token. Usually covers one of the private repos at most; the recommended single
   token is cn-test-failures Issues read, because every triage needs the tracker and canton-network-internal is
   only needed for the rarer infra issues.
3. Workarounds: exports the user runs on the host (recipes 9, 10), logs the user saves to `export/<ref>/`, files
   the user pastes; for Canton source the public mirror and release jars (recipe 6). CircleCI logs are always a
   user download.

At the start of a session run `ci-triage/scripts/probe_access.py`. It prints the router state and, per
capability, which method works; tell the user which method you use for what. When the task needs a capability that only a
workaround covers, tell the user once what would fix it, then continue with the workaround if they cannot:
- router answers `401` but `ghx` is missing: run `/run/sandbox/source/gh-cred-router/sandbox-setup.sh DACH-NY`
  (installs `ghx`; needs the client key at `/run/sandbox/source/.git/gh-cred-router-key`);
- `403 Approval required for localhost:18555`: the user approves or adds `sbx policy allow network localhost:18555`;
- `500` or `connection refused`: the user starts the router (`python3 -m gh_cred_router serve`, router README);
- router up but a capability refused (`not allowed by route`): the user adds it to the router config, e.g. for
  canton-network-internal `"allow": ["read-code"], "rules": [{"method": "GET", "path": "/actions/**"},
  {"method": "GET", "path": "/pulls/**"}]`;
- missing token: fine-grained, resource owner DACH-NY, only the repos needed, Metadata read plus: cn-test-failures
  Issues read; canton-network-internal Contents, Actions and Pull requests read; canton Contents read. Org approval
  can take days.

Using `ghx` and `gh api` the same way: both take a `/repos/...` path, so recipes call `$R <path> | jq ...` with
`R=ghx` or `R='gh api'`. `ghx` takes curl options, not `gh api` flags: no `--jq`, no `--paginate` (page with
`page=N`), only `/repos/{owner}/{repo}/...` is routed (`/graphql`, `/search` are rejected).

Exports: the sandbox runs in clone mode, so the user runs the export scripts in the host repo, they write to
`ci-triage/export/`, and you read `/run/sandbox/source/ci-triage/export/`. Ask for a rerun when an issue you need
is missing or newer than the export.

## Conventions (these override defaults)

- Never modify issues or the board, even if a route would allow it. Print the commands for the user (below);
  Status changes use `scripts/set_status.py` (recipe 12).
- Never infer tracking information by elimination (which issue belongs to which run or job). Ask.
- Issue comments stand on their own (reader: a senior engineer, no access to the triage branch): never cite packet
  paths; inline the decisive log lines in backticks or summarize them.
- Pure ASCII in every file, commit message, issue comment and PR text. No em dashes, smart quotes, arrows, emoji.
- No AI attribution anywhere (no Co-Authored-By, no "generated with"). `Signed-off-by` is the only trailer.
- Commits: one subject line plus `-s`. `[skip ci]` on the triage branch (never on a PR branch: it makes the PR
  unmergeable), `[ci]` on fix branches, `[static]` for lint-only.
- The triage branch carries only `ci-triage/`. Never commit memory files or CLAUDE.md files outside `ci-triage/`.
- Do not run sbt compiles or start Canton unless asked; triage works from the run's logs and artifacts. Exception:
  every fix branch passes the CI static checks locally before it is handed over (ci-triage-fix step 3), never skipped.
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
| `export_board.py [--status <s>]...` | host | open issues of project 48 by Status (Inbox, Triaged, In progress), with closing PRs, to `export/<status>.jsonl` (recipe 10) |
| `export_circleci.py <ref> <job url or number>... [--all-steps]` | host, `CIRCLECI_TOKEN` | failed step output of CircleCI jobs to `export/<ref>/` |
| `deployment_runs.py [--days N] [--workflow <regex>] [--excerpts]` | host, `CIRCLECI_TOKEN` | CircleCI workflow runs (passed too) as JSON lines to `export/circleci-runs-<date>.jsonl`; failed jobs with step excerpt, issue URL, splice commit |
| `set_status.py <from> <to> <ref>...` | host | Status change, only from `<from>`; `--dry-run` (recipe 12) |
| `close_duplicates.py <parent> <ref>... --comment '<text>'` (or `--comment-file <path>`) | host | close open issues as duplicates of one parent, same comment; `--dry-run` |
| `probe_access.py` | sandbox | which method reads which private data (CLAUDE.md, Access) |
| `inbox_from_timelines.py` | sandbox, `ghx` or `gh api` | inbox inferred from issue timelines (ci-triage-inbox step 1) |
| `gcp_logs.py --project <p> --start <t> --end <t> --filter <q>` | sandbox | Logs Explorer URL and `gcloud logging read` command for the user (recipe 13) |
| `github_incidents.py <UTC time>...` | sandbox | GitHub status incidents overlapping the times (last 50, about 2 months) |
| `ref_seq_contention.py <canton .clog.gz>` | sandbox | family L: reference driver `insert block` failures, retry chains, delays over 1 s, per minute |

A new recipe that needs a loop, pagination or JSON handling becomes a script here, not a pasted block.

Commands handed to the user (issue comments, closes, retitles) must work in bash, zsh and fish alike: plain `gh`
calls, every argument in single quotes, and no single quote or backslash inside an argument (fish treats `\\` and
`\'` as escapes inside single quotes, bash and zsh do not; rephrase: "the log of sv1", not "sv1's log"). Outside the
quotes: no variables, `$`, backticks, heredocs or `&&` chains.

Comment bodies go into a body file, `<round dir>/comments/<ref>.md` (committed, so the user has it after fetching the
branch), posted with `gh issue comment <ref> -R DACH-NY/cn-test-failures --body-file <path>` or `close_duplicates.py
--comment-file <path>`. Only a single-line comment of plain text (no Markdown, backticks, quotes, backslashes or `$`)
may be passed inline with `--body` or `--comment`; a multi-line body, a code block or a log line always goes into a
file. Write bodies as readable Markdown, with paragraphs and fenced code blocks for log lines. Always print the full
body in chat as well, so the user reviews it there.
