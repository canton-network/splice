---
name: ci-triage
description: 'Triage one DACH-NY/cn-test-failures issue (auto-filed for a failed GitHub Actions or CircleCI job) into a reproducible evidence packet - resolve the issue to its exact CI job, fetch the job log and artifacts, isolate the flagged lines or failing assertion, check known flake families and recent issues for duplicates, find the root cause with commands and verbatim log output, record it in the triage dataset (DACH-NY/cn-test-failures), and suggest one action on the issue (comment, close as duplicate, Status) for the user to review. Use for "triage cn-test-failures #N", "is this a dup", "root cause of shard failure", checkErrors WARN/ERROR failures, integration test flakes. For the whole inbox use ci-triage-inbox; to write the fix use ci-triage-fix.'
argument-hint: '[ref] [repo=DACH-NY/cn-test-failures] [branch=app-dev/ci-triage] [dir=cn-test-failures] [access=ro|rw]'
---

# Triage one cn-test-failures issue

Context, access to private data and conventions: `<D>/CLAUDE.md` in the triage dataset (section Dataset: where it
is and how to set it up). Read it first.

Input: one ref. Read its body and comments with the method `probe_access.py` reports for cn-test-failures issues
(`<D>/CLAUDE.md`, Access). If no method has it, stop and ask the user to fix access or rerun the export.

Output: a packet `<D>/evidence/<ref>-<slug>.md`, its row in `<D>/evidence/INDEX.tsv`, a duplicate verdict,
and one suggested action on the issue (commands plus comment body) for the user. Everything you write must be reproducible by someone else:
every claim is a command plus its verbatim output.

When run as one of several parallel agents (ci-triage-inbox), follow the brief: it usually limits you to your own
packet and the round files of your group, and asks for the INDEX rows, catalogue notes and commands instead of
edits to shared files.

## Dataset

The triage records live in their own repository, not in splice: packets (`evidence/`), `evidence/INDEX.tsv`,
`known-families.md`, `STATE.md`, `rounds/`, `lessons/`, and `CLAUDE.md` with access, priority and conventions, all
under `ci-triage/`. The three ci-triage skills take its location as arguments (`key=value` in the skill
arguments; an inbox round passes them on to its background agents):

| Argument | Default | Meaning |
|---|---|---|
| `repo` | `DACH-NY/cn-test-failures` | GitHub repository of the dataset (private) |
| `branch` | `app-dev/ci-triage` | its branch |
| `dir` | `cn-test-failures` | the local clone; a relative path is taken from the directory that holds the main splice checkout, so it is shared by all worktrees and survives clearing `log/` |
| `access` | `ro` | `ro`: the sandbox can read the repository but not push, so the user pushes; `rw`: it pushes itself |

`<dir>` below is always the absolute path, and `<D>` (here and in the other two skills) is `<dir>/ci-triage`.
Arguments of this invocation: $ARGUMENTS (when another ci-triage skill or a brief sent you here, this line shows a
literal placeholder; take the arguments from that skill or the brief).

Setup, once per session, before reading anything in `<D>`:
1. `.claude/skills/ci-triage/scripts/probe_access.py`. The clone needs `cn-test-failures contents` in the `gh`
   column (git uses the sandbox token), or `ghx` after the router's sandbox setup. Without it, stop and tell the
   user: the sandbox token needs cn-test-failures Contents read (Contents write for `access=rw`), fine-grained,
   resource owner DACH-NY; the rest of the access setup is in `<D>/CLAUDE.md`, Access, once the clone exists.
2. Resolve `<dir>` and clone or update it (shell state does not persist; repeat the absolute path in every command):
   ```
   echo "$(cd "$(git rev-parse --git-common-dir)/../.." && pwd)/cn-test-failures"                   # default <dir>
   git clone --single-branch --branch <branch> https://github.com/<repo>.git <dir>                 # <dir> missing
   git -C <dir> fetch origin <branch> && git -C <dir> merge --ff-only origin/<branch>              # <dir> present
   ```
   When the merge is not a fast-forward, the clone has commits that were not handed over or not yet pushed: show
   `git -C <dir> log --oneline --graph origin/<branch>...HEAD` and ask the user before merging, rebasing or resetting.
3. Ask the user once per session for the path of their own clone of `<repo>` on the host, on `<branch>`
   (`<checkout>` below); commands that read dataset files on the host (`--body-file`, `--comment-file`) use it.

Commits: in `<dir>`, only paths under `ci-triage/`, `git -C <dir> add <paths> && git -C <dir> commit -s -m
'<subject>' -- <paths>` (never `-a`; on `index.lock` errors wait and retry). No CI tags.

Hand-over, after the commits a user needs (comment body files, packets) and before printing commands that use them:
- `access=rw`: `git -C <dir> push origin <branch>`. If it is rejected because the remote moved: `git -C <dir> fetch
  origin <branch> && git -C <dir> merge --no-edit origin/<branch>`, then push again; on a conflict stop and ask.
- `access=ro`: write a bundle of the commits the remote lacks into the clone's git directory, and print the commands
  that apply it:
  ```
  B=$(git -C <dir> rev-parse --absolute-git-dir)/ci-triage-$(date -u +%Y%m%dT%H%M%SZ).bundle
  git -C <dir> bundle create $B origin/<branch>..<branch>      # refuses an empty bundle: nothing to hand over
  echo "$SANDBOX_NAME"; git remote | sed -n 's/^sandbox-//p'  # the sandbox name
  ```
  The sandbox name is `$SANDBOX_NAME`; if that is empty, the one `sandbox-<name>` remote of the splice checkout;
  if there is none or several, ask. For the user, on the host (`sbx cp` takes `<sandbox>:<path>` like `docker cp`;
  check `sbx cp --help` if it complains):
  ```
  sbx cp <sandbox name>:<B> <checkout>/
  git -C <checkout> pull --ff-only origin <branch>
  git -C <checkout> pull --no-rebase --no-edit <checkout>/<bundle file name> <branch>
  git -C <checkout> push origin <branch>
  rm <checkout>/<bundle file name>
  ```
  The first pull brings the host clone up to the remote, which the bundle needs. `--no-rebase` matters even when
  the user's config says `pull.rebase=true`: a rebase would give the commits new ids and orphan them in the sandbox
  clone. Then delete `<B>` in the sandbox.

Exports: scripts that the user runs on the host write to `log/ci-triage-export/` in their splice checkout; read
them in the sandbox as `/run/sandbox/source/log/ci-triage-export/` (clone mode). Logs the user saves for a ref go
to `log/ci-triage-export/<ref>/`.

## Procedure

1. Record the issue description verbatim. From it take the run id (the number in the `GitHub Actions Run` link)
   and the job name (the `Job:` line). The issue's job name is the GHA job's display name plus the suffix
   ` in <workflow job group>`; the GHA API returns the full name with the reusable-workflow path as prefix
   (`Job: wall-clock-time (0) in scala_test` corresponds to `ci / scala_test_wall_clock_time / wall-clock-time (0)`).
   Resolve the job id with `references/recipes.md` section 1: strip the ` in <group>` suffix, then a job matches
   if its name equals the stripped name or ends with ` / <stripped name>`, and its conclusion is `failure`.
   Substring matching is wrong: `wall-clock-time (0)` is contained in `frontend-wall-clock-time (0)`, and `(1)` in
   `(11)`. Exactly one match: record the job id and attempt in the packet. Zero or several matches (for example the
   same job failed in two attempts of a re-run): list the candidates and stop until the user picks one. A
   hand-written issue that links a job URL names the job id directly.
2. GHA: Fetch the job log by job id (`references/recipes.md` section 1). Classify in one pass:
   - `Tests: succeeded N, failed 0` + `contains problems` = checkErrors failure: the only evidence is the
     flagged log lines. The console masks `{}` as `***` and prints every ignored line with the suffix
     `(ignore this line in check-sbt-output.sh)`; the real problems are the `@timestamp` lines WITHOUT it.
   - `*** FAILED ***` = assertion or command failure: take the failing clue and stack head.
   - no report, job cancelled or exit without summary = evidence loss (JVM exit, timeout); say so.
   canton-network-internal Actions: recipes 1 and 3 through the method chosen for that capability; without one, the
   logs the user saved in `log/ci-triage-export/<ref>/`. CircleCI: always the user's download there (recipe 14).
   If the needed logs are not reachable either way, name the exact job and step to download and stop.
3. Duplicate check BEFORE deep analysis: compare the flagged line and the shard's suite list against
   `<D>/known-families.md`, `<D>/evidence/INDEX.tsv` and recent issues (title, body, comments; recipe 11
   with `ghx` or `gh api`, else the recipe 9 export). A match still needs the confirming grep listed in the family. Say
   "duplicate of X" only after that grep. Also search closed issues for the most distinctive string of the failure (a repo, file or
   error name; `gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "<string>"'`): the
   exports and `known-families.md` miss older and hand-closed issues. For git, SSH, clone or API failures against
   GitHub, check for a GitHub incident covering the failure time: `.claude/skills/ci-triage/scripts/github_incidents.py <UTC time>`
   (www.githubstatus.com; older than about 2 months only on https://www.githubstatus.com/history). If X is closed as fixed, check that the run's sha contains the fix
   (step 6); if it does, this is a regression, not a duplicate.
4. Establish what runs: canton pin from `nix/canton-sources.json` at the run's sha, never from `canton/`.
   For Canton behaviour cite the jar (`references/recipes.md` section 6), and state the version next to every
   source citation.
5. Download the artifact gzipped to `log/<ref>/<artifact>/`, stream with `zcat`. When a run has several attempts,
   take the artifact of the attempt that failed (artifact creation time). Build the suite timeline from
   `canton_network_test.clog.gz`, then correlate flagged timestamps to it and to the canton log. Chase "what is it
   waiting on, and why" until the cause is a concrete event with a timestamp; do not stop at the timeout symptom.
6. Decide flake versus real, and where the fix belongs (test, splice app, Canton, infra). For a candidate
   fix that already exists on main, check `git merge-base --is-ancestor <sha> origin/<branch>`: a missing
   backport is the most common resolution for release-line failures.
7. Write the packet from `references/packet-template.md`: command, then verbatim output, per finding.
   Add or update the ref's row in `<D>/evidence/INDEX.tsv` (format in the template). New families or
   occurrences go into `<D>/known-families.md` (and its index table).
8. Issue commands for the user (never run them yourself):
   - Not a duplicate: a comment with the findings and the suggested fix, optionally a retitle to
     `<Suite or job>: <cause>` while the title still starts with `GHA Run` or `Pipeline`, and
     `direnv exec . .claude/skills/ci-triage/scripts/set_status.py Inbox Triaged <ref>` (recipe 12).
   - Duplicate: `direnv exec . .claude/skills/ci-triage/scripts/close_duplicates.py <parent> <ref>... --comment '<why>'` (a
     single line of plain text, inline, no body file), one line
     per parent with all its duplicates (closing moves them to `Closed`). The duplicate close links the occurrence to the parent; add a recurrence comment on the parent only
     when it recurs often and has neither an assignee nor an owner discussion.
   - No root cause: a comment with what was investigated and what is missing to decide, and no Status change
     unless the user chooses `Infrequent / no repro`.
   Write every comment in the shape of `references/issue-comment.md`, into a body file (self-contained for a senior engineer, no
   packet references, decisive log lines in a fenced block, Markdown paragraphs; it has an accepted example).
   Every claim in the comment must be reproducible from the packet alone: each fact, count, comparison and code
   reference has a packet section with the command and its verbatim output (a code claim: `git show <sha>:<file>`
   with the lines). A search result is listed per hit with one line on why it is or is not the same failure (title
   or the matching line), and only the hits that are the same failure are counted in the comment. A claim with no
   packet section is either added to the packet first or left out of the comment.
9. Fix. Decide where the fix belongs and state it in the packet's Verdict and in the comment, precisely enough to
   implement without the chat: file and change (for a backport the PR and the release line), and a suggested assignee only
   when the evidence points at one or two people: the PR that introduced the failure, the one clear owner git blame
   shows for the code the fix touches, or the component owner for infra or Canton (ci-triage-inbox, Assignee). No branch here: the fix agent
   (`ci-triage-fix`) picks the issue up from Status Triaged and agrees the change with the user first.
10. Commit in the dataset clone and hand it over (Dataset: Commits, Hand-over); under ci-triage-inbox the main
    thread does both. Report: one line per ref with the verdict, then the
    action from step 8 with the full comment bodies.

## Rules of evidence

- Root cause, not symptom: for a real failure, find what the stalled component was waiting on and why, with a
  timestamped event. Timeouts are never the cause.
- Runtime log lines stand on their own; source citations only against the jar of the Canton version that ran.
  `canton/` is a stale rsync copy: never cite its line numbers.
- Log-ignore additions are a last resort and must argue why the line can never carry signal. Ignore patterns hide
  the only signal on shards where the underlying bug does not wedge; rejected precedents: the IndexerState
  reconnect WARN (10084) and the P2P auth-token WARN (10010).
- The suite list of a checkErrors shard is not the culprit: the WARN's suite is the one running at the flagged
  timestamp, and commitment or ack WARNs often land minutes after the causing step.
- A packet without the commands that produced its conclusions is not accepted.
