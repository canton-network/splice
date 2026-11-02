---
name: ci-triage
description: Triage one DACH-NY/cn-test-failures issue (auto-filed for a failed GitHub Actions or CircleCI job) into a reproducible evidence packet - resolve the issue to its exact CI job, fetch the job log and artifacts, isolate the flagged lines or failing assertion, check known flake families for duplicates, find the root cause with commands and verbatim log output, record it on the shared triage branch, suggest updates, and put small test-side fixes on a branch. Use for "triage cn-test-failures #N", "work through the CI inbox", "is this a dup", "root cause of shard failure", checkErrors WARN/ERROR failures, integration test flakes.
---

ref = the cn-test-failures issue number

# Background

CI runs in several places:
- GitHub Actions in the `canton-network/splice` repository
- GitHub Actions in the `DACH-NY/canton-network-internal` repository
- CircleCI jobs executing cluster deployments and cluster tests that are defined in the `DACH-NY/canton-network-internal` repository

Each failing CI job automatically creates a GitHub issue in the `DACH-NY/cn-test-failures` repository.
The issues contain links to the corresponding CI job logs and artifacts.

The status of CI is tracked in the `DACH-NY/projects/48` project.
Each GitHub issue is assigned a `Status` field in the project:
- `Inbox` is the default status for new issues created by failing CI jobs.
- `Vulnerability Inbox` is the default status for new issues created by blackduck dependency scans.
- `Triaged` is the status for issues whose root cause has been identified.
- `In progress` is the status for issues that have a human assigned to working on it.
- `Closed` is the status for issues that have been resolved. Don't set this manually; it is set automatically when the issue is closed.
- `Infrequent / no repro` is the status for issues that we intentionally choose to ignore because they occur rarely or cannot be reproduced

# Access

- `canton-network/splice`: everything is public, including output of GHA jobs
- `DACH-NY/cn-test-failures`: private repository, the sandbox proxy should have a read/write token for issues
- `DACH-NY/canton-network-internal`: private repository, the sandbox proxy should have a read-only token for repository content
- CircleCI: you don't have direct access; ask the user to download the job logs and artifacts manually

Neither you nor the user's host CLI has project-level access to the `DACH-NY/projects/48` project.
If a user needs to manually update an issue `Status`, ask them to do it through the GitHub web interface.

# CI failure triage for splice

Input: one `cn-test-failures` issue number. If you can't read the issue content, stop immediately and ask the user to fix access.

Output: one evidence packet per issue in `ci-triage/`, a row in `ci-triage/README.md`, a duplicate verdict,
and optionally a fix branch. Everything you write must be reproducible by someone else: every claim is a command
plus its verbatim output. Read `references/conventions.md` once before starting; it overrides defaults.

The skill lives on main; the triage data does not. Packets, `ci-triage/README.md`, `ci-triage/HANDOVER.md` and the
flake family catalogue `ci-triage/known-families.md` live on the shared triage branch, which is never merged. If it
is not checked out, ask the user which triage branch is current before writing anything.

## Procedure

1. Record the issue description verbatim. From it take the run id (the number in the `GitHub Actions Run` link)
   and the job name (the `Job:` line). The issue's job name is the GHA job's display name plus the suffix
   ` in <workflow job group>`; the GHA API returns the full name with the reusable-workflow path as prefix
   (`Job: wall-clock-time (0) in scala_test` corresponds to `ci / scala_test_wall_clock_time / wall-clock-time (0)`).
   Resolve the job id with `references/recipes.md` section 1: strip the ` in <group>` suffix, then a job matches
   if its name equals the stripped name or ends with ` / <stripped name>`, and its conclusion is `failure`.
   Substring matching is wrong: `wall-clock-time (0)` is contained in `frontend-wall-clock-time (0)`, and `(1)` in `(11)`.
   Exactly one match: record the job id and attempt in the packet. Zero or several matches (for example the same
   job failed in two attempts of a re-run): list the candidates and stop until the user picks one. Never pick by
   elimination or by timing.
2. GHA: Fetch the job log by job id (`references/recipes.md` section 1). Classify in one pass:
   - `Tests: succeeded N, failed 0` + `contains problems` = checkErrors failure: the only evidence is the
     flagged log lines. The console masks `{}` as `***` and prints every ignored line with the suffix
     `(ignore this line in check-sbt-output.sh)`; the real problems are the `@timestamp` lines WITHOUT it.
   - `*** FAILED ***` = assertion or command failure: take the failing clue and stack head.
   - no report, job cancelled or exit without summary = evidence loss (JVM exit, timeout); say so.
3. Duplicate check BEFORE deep analysis: compare the flagged line and the shard's suite list against
   `ci-triage/known-families.md` on the triage branch. A match still needs the confirming grep listed there.
   Say "duplicate of X" only after that grep.
4. Establish what runs: canton pin from `nix/canton-sources.json` at the run's sha, never from `canton/`.
   For Canton behaviour cite the jar (`references/recipes.md` section 6), and state the version next to every
   source citation.
5. Download the artifact gzipped to `log/<ref>/<artifact>/` (gitignored), stream with `zcat`.
   Build the suite timeline from `canton_network_test.clog.gz`, then correlate flagged timestamps to it and
   to the canton log. Chase "what is it waiting on, and why" until the cause is a concrete event with a
   timestamp; do not stop at the timeout symptom.
6. Decide flake versus real, and where the fix belongs (test, splice app, Canton, infra). For a candidate
   fix that already exists on main, check `git merge-base --is-ancestor <sha> origin/<branch>`: a missing
   backport is the most common resolution for release-line failures.
7. Write the packet from `references/packet-template.md`: command, then verbatim output, per finding.
   Add the README row (mapping table, overview table, cross-cutting notes when a pattern spans refs).
   Add new families or occurrences to `ci-triage/known-families.md`.
8. Suggest how to handle the GitHub issue. Do not modify the issue yourself, instead print CLI commands
   that can be used by the user.
   - If the issue is not a duplicate, the user should:
     - Add a comment that summarizes the findings and any suggested fixes.
       Keep it concise and focused on the key points. For a simple issue (such as a clearly missing synchronization in a test),
       one paragraph should be sufficient. For more complex edge cases, include more details.
     - Move the issue to `Triaged`.
   - If the issue is a duplicate, the user should:
     - Add comment explaining why it is a duplicate.
     - Close the issue as a duplicate (this will automatically move the issue to `Closed`).
   - If you cannot determine a root cause, the user should:
     - Add a comment summarizing what was investigated and why the root cause could not be determined.
9. Fix, only when it is test-side and small enough to review in one screen: one branch per PR off
   `origin/main` (or the release line for a backport, `cherry-pick -x -s`), in its own worktree so the triage
   branch stays checked out (`references/recipes.md` section 8), named
   `<user>/fix-<ref>-<slug>` (backports: `<user>/backport-<ref>-<pr>-<release-line>`), where `<user>` is your own
   short git prefix, so the branch carries the cn-test-failures ref; when one branch fixes several refs, use the
   parent ref and list the others in the README row. Single-subject commit with a CI tag and DCO sign-off, no
   code comments, no AI attribution. State plainly what was and was not verified.
   Do not compile or start Canton unless asked; cheap checks only (`scalafmtCheck`, `prettier --check`,
   config greps).
10. Commit the packet on the triage branch with `[skip ci]` and `-s`. Report: one line per ref with
    duplicate verdict and fix state, then the branch tips.

## Anti-patterns

- Proposing an ignore pattern for a WARN whose cause is not understood. Ignore patterns hide the only signal on
  shards where the underlying bug does not wedge (see 10010 in the catalogue).
- Citing `canton/` source line numbers: it is a stale rsync copy.
- Treating the suite list of a checkErrors shard as the culprit: the WARN's suite is the one running at the
  flagged timestamp, and commitment or ack WARNs often land minutes after the causing step.
- Pasting only conclusions. A packet without the commands that produced them is not accepted.
