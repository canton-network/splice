# CI triage

This folder exists only on the shared triage branch (the current one is named in `HANDOVER.md`), which is never
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
| `issues/<ref>-<slug>.md` | evidence packets, one per issue or per group of issues |
| `issues/INDEX.tsv` | one row per triaged ref; columns in `skills/ci-triage/references/packet-template.md` |
| `known-families.md` | flake families with signature and confirming grep; check before any deep analysis |
| `HANDOVER.md` | current state: inbox, open questions, open fix branches |
| `history/` | Raymond's state up to 2026-11-02, read-only; `moved-paths.tsv` maps paths cited in old issue comments |
| `skills/` | the two skills; command recipes in `skills/ci-triage/references/recipes.md` |
| `export/` | gitignored; issue exports and logs the user saves on the host |

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

- Never modify issues or the board, even if a route would allow it. Print the commands (fish, for the host) and let
  the user run them; Status changes use `p48_status` (recipe 12).
- Never infer tracking information by elimination (which issue belongs to which run or job). Ask.
- Pure ASCII in every file, commit message, issue comment and PR text. No em dashes, smart quotes, arrows, emoji.
- No AI attribution anywhere (no Co-Authored-By, no "generated with"). `Signed-off-by` is the only trailer.
- Commits: one subject line plus `-s`. `[skip ci]` on the triage branch (never on a PR branch: it makes the PR
  unmergeable), `[ci]` on fix branches, `[static]` for lint-only.
- The triage branch carries only `ci-triage/`. Never commit memory files or CLAUDE.md files outside `ci-triage/`.
- Do not run sbt compiles or start Canton unless asked; triage works from the run's logs and artifacts.
- Artifacts the sandbox downloads go to `log/<ref>/` (gitignored), with `TMPDIR` under `log/` too; keep `.clog.gz`
  compressed and stream with `zcat`. Never put tokens (e.g. a contents `download_url`) into files.
- Fix branches: one per PR, stacked follow-ups on the same branch, named `<user>/fix-<ref>-<slug>` or
  `<user>/backport-<ref>-<pr>-<release-line>` (`<user>` = your git prefix), in their own worktree.
