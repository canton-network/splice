---
name: ci-triage
description: Triage one DACH-NY/cn-test-failures issue (auto-filed for a failed GitHub Actions or CircleCI job) into a reproducible evidence packet - resolve the issue to its exact CI job, fetch the job log and artifacts, isolate the flagged lines or failing assertion, check known flake families and recent issues for duplicates, find the root cause with commands and verbatim log output, record it on the triage branch, suggest issue updates, and put small test-side fixes on a branch. Use for "triage cn-test-failures #N", "is this a dup", "root cause of shard failure", checkErrors WARN/ERROR failures, integration test flakes. For the whole inbox use ci-triage-inbox.
---

# Triage one cn-test-failures issue

Context, access to private data and conventions: `ci-triage/CLAUDE.md` on the triage branch. Read it first; if the
triage branch is not checked out, ask the user which one is current before writing anything.

Input: one ref. Read its body and comments with the method `probe_access.py` reports for cn-test-failures issues
(CLAUDE.md, Access). If no method has it, stop and ask the user to fix access or rerun the export.

Output: a packet `ci-triage/evidence/<ref>-<slug>.md`, its row in `ci-triage/evidence/INDEX.tsv`, a duplicate verdict,
issue commands for the user, and optionally a fix branch. Everything you write must be reproducible by someone else:
every claim is a command plus its verbatim output.

When run as one of several parallel agents (ci-triage-inbox), follow the brief: it usually limits you to your own
packet and the round files of your group, and asks for the INDEX rows, catalogue notes and commands instead of
edits to shared files.

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
   logs the user saved in `export/<ref>/`. CircleCI: always the user's download in `export/<ref>/`. If the needed
   logs are not reachable either way, name the exact job and step to download and stop.
3. Duplicate check BEFORE deep analysis: compare the flagged line and the shard's suite list against
   `ci-triage/known-families.md`, `ci-triage/evidence/INDEX.tsv` and recent issues (title, body, comments; recipe 11
   with `ghx` or `gh api`, else the recipe 9 export). A match still needs the confirming grep listed in the family. Say
   "duplicate of X" only after that grep. Also search closed issues for the most distinctive string of the failure (a repo, file or
   error name; `gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "<string>"'`): the
   exports and `known-families.md` miss older and hand-closed issues. For git, SSH, clone or API failures against
   GitHub, check https://www.githubstatus.com/history for an incident covering the failure time. If X is closed as fixed, check that the run's sha contains the fix
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
   Add or update the ref's row in `ci-triage/evidence/INDEX.tsv` (format in the template). New families or
   occurrences go into `ci-triage/known-families.md` (and its index table), an open fix branch into `STATE.md`.
8. Issue commands for the user (never run them yourself):
   - Not a duplicate: a comment with the findings and the suggested fix, optionally a retitle to
     `<Suite or job>: <cause>` while the title still starts with `GHA Run` or `Pipeline`, and
     `direnv exec . ci-triage/scripts/set_status.py Inbox Triaged <ref>` (recipe 12).
   - Duplicate: `direnv exec . ci-triage/scripts/close_duplicates.py <parent> <ref>... --comment '<why>'` (a
     single line of plain text; anything longer `--comment-file <round dir>/comments/<parent>-dups.md`), one line
     per parent with all its duplicates (closing moves them to `Closed`). When the parent is open and recurs often, a short recurrence comment on it.
   - No root cause: a comment with what was investigated and what is missing to decide, and no Status change
     unless the user chooses `Infrequent / no repro`.
   Write every comment in the shape of `references/issue-comment.md`, into a body file (self-contained for a senior engineer, no
   packet references, decisive log lines in a fenced block, Markdown paragraphs; it has an accepted example).
9. Fix. Create the branch without asking when all of these hold: the root cause is established with evidence, the
   fix touches only test code or test configuration (`src/test/`, test resources, test scripts; no production code,
   no Daml, no deployment config), and it is small enough to review in one screen. Otherwise describe the fix
   (file, change, owner) and create a branch only when asked. One branch per PR off `origin/main`
   (or the release line for a backport, `cherry-pick -x -s`), in its own worktree so the triage branch stays checked
   out (`references/recipes.md` section 8). When one branch fixes several refs, name it after the parent ref and
   list the others in the INDEX rows. Single-subject commit, no code comments. State plainly what was and was not
   verified. Static checks are mandatory before a fix branch is handed to the user, never skipped: run
   `sbt --batch formatFix`, then the CI static check (`references/recipes.md` section 8) and commit what formatFix
   changed; it must exit 0 with no further diff. Integration tests may be left to CI when they are expensive. Production changes are
   described and left to the owner unless asked.
10. Commit on the triage branch with `[skip ci]` and `-s`. Report: one line per ref with duplicate verdict and fix
    state, then the commands from step 8, then the branch tips.

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
