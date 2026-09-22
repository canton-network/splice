# Splice CI triage: onboarding

You are taking over the triage of failed splice GitHub Actions jobs (cn-test-failures refs) in your own sandbox.
Everything reproducible is on one git branch; this page is the short path into it.

## 1. Get the work

```
git fetch <remote> ray/ci-triage-2026-09-15 && git checkout ray/ci-triage-2026-09-15
cp -r ci-triage/skill/ci-triage ~/.claude/skills/
```
Then read, in this order: `ci-triage/HANDOVER.md` (status of every fix branch, open items, environment notes),
`ci-triage/README.md` (ref -> run -> job mapping and one line per ref), and
`ci-triage/skill/ci-triage/references/known-families.md` (flake families A to L with the grep that confirms each).

## 2. Run a triage

Ask the person filing the ref for the run URL and the job name; the tracker DACH-NY/cn-test-failures is not
readable with a sandbox token. Then:
```
/ci-triage <run id or url> <ref>
```
The skill fetches the job log and artifact, checks the family catalogue, chases the cause to a timestamped event,
writes a packet with every command and its verbatim output, adds README rows, and writes a fix branch when the fix
is test-side and fits on one screen. Run it as a background fork if you want to keep talking meanwhile. When a run
has several failed jobs and several refs, never assign refs by elimination; record "mapping open" until told.

## 3. Conventions that are not optional

- Pure ASCII in everything written to the repo: no em dashes, smart quotes, arrows, emoji.
- No AI attribution anywhere (no `Co-Authored-By: Claude`, no "generated with"); `Signed-off-by:` is the only trailer.
- Commits: one subject line plus `-s`. CI tag mandatory: `[skip ci]` on the triage branch (never on a PR branch, it
  makes the PR unmergeable), `[ci]` on fix branches, `[static]` for lint-only changes.
- No code comments in fixes; the reasoning goes in the packet and the commit subject.
- Fix branches: one branch per PR off `origin/main` (release lines: `git cherry-pick -x -s`), named
  `ray/fix-<ref>-<slug>` or `ray/backport-<ref>-<pr>-<release-line>` so the ref is visible in `git branch`.
- Root cause, not symptom: for a real failure, find what the stalled component was waiting on and why, with a
  timestamped event. A timeout is never the cause. An ignore pattern is a last resort and must argue why the line
  can never carry signal (two were rejected: 10084, 10010).
- Do not compile or start Canton in the sandbox unless asked; `apps-app/Test/scalafmtCheck` in a `git worktree`
  is the cheap check. State plainly what was and was not verified.

## 4. Facts about the environment that cost time to learn

- `canton/` in the repo is a stale rsync copy (`canton/VERSION` says 3.5.7-SNAPSHOT). What runs is pinned in
  `nix/canton-sources.json` at the run's sha. Cite Canton behaviour against the jar of that version (recipe in
  `references/recipes.md` section 6, tarballs from canton.io, about 300 MB each), and put the version next to any
  source citation. Runtime log evidence stands on its own; source citations do not.
- The sandbox root overlay has 0.2 to 2 GB free. Keep `TMPDIR`, artifacts and jars under `log/` on the repo mount.
  Never pipe a large `zcat` into `sort` (it spills to `/tmp` and kills every running command); count with `awk`.
  Never run `nix-collect-garbage`.
- GitHub job logs mask `{` and `}` as `***`. checkErrors prints every ignored line with the suffix
  `(ignore this line in check-sbt-output.sh)`; the real problems are the `@timestamp` lines without it.
- `[skip ci]` is GitHub's native suppression, so the required check never reports and the PR cannot merge. Zero
  test retries in this CI: any single WARN that is not on an ignore list fails the shard.
- Rate limiter rejections are metered, not logged; a 429 in the logs does not say which limiter fired.
- sbt: `direnv allow` then `USER=$(id -un) direnv exec . bash -c 'sbt --batch ...'` (the template's global
  CLAUDE.md has the full recipe). If the dev shell cannot realize because the overlay is full, source
  `ci-triage/skill/ci-triage/references/sandbox-sbt-env.sh` instead.
- `origin` (hyperledger-labs, SSH) is refused from a sandbox; use the HTTPS remote for canton-network/splice.
  DACH-NY repos need a token that covers that org (`sbx secret set <sandbox> github -t "$(gh auth token)"`).

## 5. Where the open work is

`ci-triage/HANDOVER.md` section 3 lists the fix branches that are not upstream yet and section 4 the items that
have no fix (mostly Canton-side: the BFT onboarding quorum stall 10165, the reference sequencer insert-block
storm 10139/10197, two log-level questions). Family A (ACS commitment mismatch after multi-hosting alice) was
fixed by PR 7435 on 2026-09-21; if main stays clean, close 10111 and its dups.
