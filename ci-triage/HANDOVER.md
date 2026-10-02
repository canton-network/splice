# CI triage handover (state as of 2026-10-02)

Everything reproducible about the splice CI failure triage from 2026-09-08 to 2026-10-02 lives on this branch,
`app-dev/ci-triage` (renamed from `s11/ci-triage-2026-10-02`; based on `rautenrieth-da/ci-triage-2026-09-28`, which
in turn continued `ray/ci-triage-2026-09-15`). Origin/main is merged in up to 4a7f355b17. A new person needs: this
branch, a splice checkout of main (the skill lives there), `gh` access to canton-network/splice, and the
`(run URL, job name, ref)` tuple for each new ref. Downloaded artifacts and Canton jars are not part of the handover;
every packet contains the commands that fetched them.

## 1. What is where

| Item | Location | Notes |
|---|---|---|
| Evidence packets (65 files) | `ci-triage/<ref>-<slug>.md` | One per ref, or one per family with numbered occurrence sections (`10155-10158-...` family A, `10264-10268-...` family Q) |
| Index | `ci-triage/README.md` | Per-date sections: ref -> run -> job mapping, one-line overview per ref, cross-cutting notes, fix-branch tables (section 3 below supersedes their status columns) |
| Flake families A-R | `ci-triage/known-families.md` | Signature, confirming grep, parent ref, occurrences, fix per family. Check it before deep analysis |
| Ref status check | `ci-triage/cn-test-failures-status.py` | Lists the tracker state of every ref recorded here; see section 4 |
| Triage skill | `.claude/skills/ci-triage/` on main (#7502) | Not on this branch any more; run `/ci-triage (<run url>, <job>, <ref>)` from a main checkout |
| sbt without the nix dev shell | `ci-triage/sandbox-sbt-env.sh` | Only when `direnv exec . sbt` cannot realize the dev shell |

Not on the branch, by design: `CLAUDE.md` files, the assistant's memory files, `log/` (artifacts, jars, user-supplied
CircleCI step logs and Cloud Logging exports).

## 2. Moving the work

The branch is not pushed. It is a worktree of the host repo, so the ref is already visible on the host:
```
git push origin app-dev/ci-triage
```
It carries only `[skip ci]` commits and must never be merged. Continue committing packets on it with
`git commit -s -m "[skip ci] ..."`, one subject line, ASCII only. Merge origin/main into it from time to time; nothing
outside `ci-triage/` differs from main, so the merge is conflict-free.

## 3. Open refs and what each needs

Tracker state from `log/cn-test-failures-status.tsv` (2026-10-02 23:04): 29 of 98 refs open, 31 closed completed,
36 closed duplicate, 1 not planned; 10312 is not a cn-test-failures issue (cn-internal). Branch state checked the same
day with `git cherry origin/main <branch>` and `gh pr list --head <branch>`.

Close now (fix merged or no code change):

| Ref(s) | Why |
|---|---|
| 10184 | PR 7440 (`ray/fix-10184-mediator-pruning-backoff-ignore`) merged 09-22 |
| 10185-10193, 10195 | GitHub 504 on a nix flake input took one run's shards; infra, rerun, no code change |
| 10249 | CPU-saturated GKE node on cimain; infra, rerun (deployment robustness notes are in the packet) |

Waiting on a PR:

| Ref | Branch | Upstream | Action |
|---|---|---|---|
| 10271 | s11/fix-10271-issuing-round-wait-budget | PR 7618 OPEN, approved | merge, close |
| 10270 (10272 closed as its dup) | s11/fix-10270-tap-amulets-wait-for-tap | PR 7619 OPEN, review required | merge, close. 10272's remainder, the validator tap retry budget (about 13.6 s) versus BFT scan lag after an AmuletRules upgrade, has no ref; raise with the validator wallet owner if it recurs |
| 10183 | ray/fix-10183-reset-namespace-late-proposer | PR 7441 OPEN | review, merge, close |

Fix branch exists, no PR yet:

| Ref(s) | Branch | Action |
|---|---|---|
| 10176 | ray/fix-10176-sanity-check-pause-timeout (origin e59f6538c0) | PR. Local tip only adds a merge of main. Upstream half (vendored `EnvironmentSetup.manualDestroyEnvironment` runs `beforeEnvironmentDestroyed` outside its `try`) is Canton |
| 10197 (family L) | ray/fix-10197-bft-read-confirmation-wait (origin 964e7df114) | PR |
| 10180, 10269 (family H + H2) | ray/fix-fail-fast-init (origin 2832e6da66, 2 commits; splice issue #7289 open) | PR; until it lands every `NodeBase` init exit costs a 60 min shard. The H2 cause itself (an app restarted mid-LSU hits an unretried call: `NodeInitializer.rotateOwnerToKeyMappingNotSignedByKeys` in 10180, `JoiningNodeInitializer.scala:466` in 10269) needs an app fix: retry NOT_FOUND across SV and validator init while mid-LSU |
| 10204 | s11/fix-10204-upload-logs-after-sanitize-failure (a16e747a69, not pushed) | push, PR (evidence loss only; the slow runner itself was infra) |
| 10235 | s11/fix-10235-sanity-check-skip-uninitialized-scans (b63ea6493f, not pushed, compiled) | push, PR for the cascade; the participant reconnect hang (family P) is Canton's |

No fix branch; owner outside the test code:

| Ref(s) | Owner | Summary |
|---|---|---|
| 10139, 10273 (family L) | Canton / test infra | Reference sequencers serialize `insert block` in one Postgres and retry SQLSTATE 40001 with growing backoff. 10271 and 10273 add a sv1-only shape (only globalSequencerSv1's inserts stall, backoff 5.6 s and 8.9 s, others under 1.3 s). 10273 crossed the sv-app's 38 s HTTP timeout, so no test budget helps |
| 10165 (family B umbrella), 10227 | Canton | BFT 1 -> N onboarding loses quorum and blacklists sv1; acks wait out 120 s. 10227 adds: no sequencer failover on the topology broadcast path. Issue text in `10165-issue-body.md` |
| 10233 | splice app (`NodeBootstrapBase.onClosed`) | Scan serves HTTP after its DB closed in teardown; close `httpAdminService` before the node |
| 10084, 10147 | Canton | Log-level questions (IndexerState reconnect-drain WARNs; BFT P2P `Connecting (unchanged)` at shutdown); ignore patterns rejected |
| 9136 | Canton | GetPreferredPackages metadata-view race (10144 closed as its dup) |

Branches that can be deleted: `s11/fix-10236-scan-snapshot-wait-for-index` (superseded by PR 7527),
`ray/fix-summarizing-round-log-noise` (same fix merged as PR 7303), and every branch whose PR merged
(`s11/fix-10236-scan-snapshot-before-skips-unindexed`, `s11/fix-10238-single-forced-acs-snapshot`,
`s11/fix-10248-vite-config-import-meta-dirname`, `ray/fix-10184-...`). `ray/fix-reset-topology-plugin-no-exit`
(origin 743babe3f0, no PR) and `ray/fix-topology-init-limit` (origin 72dc373508, no PR) serve closed refs (10137 dup;
10140 completed, likely by #7426): PR them as hardening or delete.

Untracked: 6 of about 480 docker-compose/docker-no-canton jobs from 09-28 to 09-30 went silent during
`make docker-build -j8` on `self-hosted-docker-large` runners and were cancelled at 45 min + 10 min (suspected pod OOM
or eviction, unconfirmed; no ref filed).

## 4. Rechecking the tracker

The tracker is not readable from the sandbox. On a host whose `gh` login can read it, from the splice repo root:
```
python3 ci-triage/cn-test-failures-status.py                                      # in a checkout of this branch
python3 <(git show app-dev/ci-triage:ci-triage/cn-test-failures-status.py)        # from any other checkout
```
It takes every ref recorded here (README rows, packet names, known-families, plus any arguments), queries the tracker
in batches of 50 through GraphQL, writes `log/cn-test-failures-status.tsv` (ref, state, state reason, closed at,
updated at, labels, title; `--out` to change) and prints the open refs. Needs `gh` and `python3`. Hand the TSV back
to update section 3.

## 5. Environment notes for a fresh sandbox

- Refs arrive as `(run URL, job name, ref)`. Never map refs to jobs by elimination when a run has several failed jobs.
- CircleCI jobs (cluster deploys, preflights) are not reachable from the sandbox: the user saves the step log under
  `log/<ref>/`. Those directories belong to the host user and are read-only for the sandbox; write fetch scripts to
  `log/` itself (`log/<ref>-fetch.sh`, see `log/10270-10272-fetch.sh`) and batch every `gcloud logging read` into one
  script for the user to run.
- Cluster names: cimain = `cn-cimainnet`, ciperiodic = `cn-ciperiodicnet`, project `da-cn-ci`; the runbook validator's
  namespace is `validator`, SVs are `sv-1`, `sv-2`, `sv-3`, `sv-da-1`.
- `canton/` in the repo is a stale copy; what runs is `nix/canton-sources.json` at the run's sha. Cite Canton behaviour
  against the jar of that version (`recipes.md` section 6).
- Disk: the root overlay is shared by all sandbox sessions and fills up (it hit 100 % on 2026-10-02 during a compile).
  Put `TMPDIR`, artifacts, jars and compile worktrees on the repo volume under `log/` (`log/tmp`, `log/wt/<name>`);
  `git worktree move` cannot cross filesystems, so remove and re-add. Never pipe a large `zcat` into `sort`. Never run
  `nix-collect-garbage`.
- Builds are allowed for fixes and repros, one at a time:
  `flock <repo>/log/build.lock bash -l -c 'cd <worktree> && direnv exec . <cmd>'`.
- Do not touch `/Users/stephencompall/ide/splice-pr7600` or session `63c6c576`'s scratchpad (the 7600 review).
- Job logs: GitHub masks `{` and `}` as `***`; ignored checkErrors lines carry the suffix
  `(ignore this line in check-sbt-output.sh)`, the real problems are the `@timestamp` lines without it.
- Fix branches: one branch per PR off `origin/main` (or the release line for a backport, `cherry-pick -x -s`), named
  `<user>/fix-<ref>-<slug>` in a separate worktree. Commits: one subject with `[ci]` (`[static]` for docs only, never
  `[skip ci]` on a PR branch), `-s`, and the user's `Assisted-by:` trailer; no code comments.
- Secret scan of this branch (2026-10-02, detect-secrets plus credential-format regexes over every version of every
  file and the commit messages): no secrets. Non-secret identifiers present: one Auth0 test-user subject (10270) and
  the public commit metadata of #7546 (10264-10268).
