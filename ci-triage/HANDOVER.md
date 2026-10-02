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
| Ref status check | `ci-triage/cn-test-failures-status.sh` | Lists the tracker state of every ref recorded here; see section 5 |
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

## 3. Fix branch status

Checked 2026-10-02 with `git cherry origin/main <branch>`, `gh pr list --head <branch>` and the PR authors' lists.

Open work:

| Branch | Ref(s) | Upstream | Action |
|---|---|---|---|
| s11/fix-10271-issuing-round-wait-budget | 10271 | PR 7618 OPEN, approved | merge, then close 10271 |
| s11/fix-10270-tap-amulets-wait-for-tap | 10270, 10272 (family R) | PR 7619 OPEN, review required | review, merge, close 10270; 10272 then fails fast at the tap (section 4) |
| ray/fix-10183-reset-namespace-late-proposer | 10183 | PR 7441 OPEN | review, merge |
| s11/fix-10235-sanity-check-skip-uninitialized-scans (b63ea6493f) | 10235 cascade | not pushed | compiled; push, PR |
| s11/fix-10204-upload-logs-after-sanitize-failure (a16e747a69) | 10204 (evidence loss) | not pushed | push, PR |
| ray/fix-10176-sanity-check-pause-timeout (origin e59f6538c0) | 10176 | on origin, no PR | PR (local tip only adds a merge of main) |
| ray/fix-10197-bft-read-confirmation-wait (origin 964e7df114) | 10197 | on origin, no PR | PR |
| ray/fix-reset-topology-plugin-no-exit (origin 743babe3f0) | 10137, 10139, 10183 | on origin, no PR | PR; turns the teardown `sys.exit(1)` into a suite failure |
| ray/fix-fail-fast-init (origin 2832e6da66, 2 commits) | 10088-A, 10180, 10269 (issue #7289, open) | on origin, no PR | PR; every family H `NodeBase` exit costs a whole shard until this lands |
| ray/fix-topology-init-limit (origin 72dc373508) | 10140 | on origin, no PR | check whether #7426 (test ignore, merged 09-21) made it unnecessary; PR or delete |

Done or obsolete:

| Branch | Ref(s) | Upstream | Action |
|---|---|---|---|
| s11/fix-10236-scan-snapshot-before-skips-unindexed | 10236, 10237, 10241, 10242 | PR 7527 merged 09-30 | close refs |
| s11/fix-10236-scan-snapshot-wait-for-index (7ae2e53dd4) | 10236 (test side) | superseded by 7527 | delete branch |
| s11/fix-10238-single-forced-acs-snapshot | 10238, 10247, 10257 | PR 7548 merged 09-30 | close refs; the scan millisecond table naming stays with the #6515 owner |
| s11/fix-10248-vite-config-import-meta-dirname | 10248 | PR 7536 merged 09-30 | close ref |
| ray/fix-10184-mediator-pruning-backoff-ignore | 10184 | PR 7440 merged 09-22 | close ref |
| ray/fix-summarizing-round-log-noise (2fb77e0be2) | 10121, 10142 | same fix merged as PR 7303 on 09-16 | close refs, delete branch |
| Earlier ray fixes (see the README 2026-09-17 to 09-22 tables) | 10173, 10174, 10175, 10179, family A (10111 + 9), 8784, 10143, 9740, 10170, 10141, 10145, 10149, 10150, 10172, 10136, 10154/10166/10171, 10169 | PRs 7417, 7423, 7425, 7428, 7435, 7416, 7412, 7400, 7380, 7374, 7414, #7176, 7401/7402/7405/7406 merged 09-10 to 09-21 | close whatever is still open |

## 4. Open items without a fix branch

- Family L, reference sequencer `insert block` SQLSTATE 40001 retry storm (10139, 10197, 10256, 10271, 10273): several
  reference sequencers serialize block inserts in one Postgres. 10271 and 10273 show a new shape: only
  globalSequencerSv1's own inserts stall (backoff to 5.6 s and 8.9 s, the others stay under 1.3 s) while ordering
  continues. 10273 crossed the sv-app's 38 s HTTP timeout, so no test budget helps. Canton / test-infra sizing.
- Family B / 10165 umbrella (10094, 10153, 10161, 10137, 10212, 10225): BFT 1 -> N onboarding loses quorum, sv1 is
  blacklisted, acks wait out their 120 s deadline. Canton-side. Paste-ready issue text in `10165-issue-body.md`.
- Family P, 10235: participant reconnect never completes after a sequencer-alias-only change (Canton
  3.6.0-snapshot.20260928). Canton owner; the cascade half has `s11/fix-10235-...`.
- Family H2 variants (10088-B, 10174, 10180, 10269): an app restarted while its participant is mid-LSU fails init on an
  unretried call in the disconnect/connect gap (13 ms in 10269, about 470 ms in 10180). 10269 is the SV app
  (`JoiningNodeInitializer.scala:466`, `getPhysicalSynchronizerId`); 3 of 162 LSU jobs since 09-27. App fix described:
  retry NOT_FOUND across SV and validator init while mid-LSU.
- 10272 remainder: the validator's tap retry budget (12 retries, about 13.6 s) is shorter than the BFT scan lag after an
  AmuletRules upgrade; owner of the validator wallet. Test option: re-tap in `tapAmulets` on
  `LOCAL_VERDICT_INACTIVE_CONTRACTS`.
- Family Q, 10264-10268: cluster deploys fail because #7546 reads `messages.confirmationResponse`, missing from the
  internal DevNet `sequencer-rate-limits.json`. Owner of #7546 (configs-private#3673, or tolerate absent keys).
- 10238 product half: per-table ACS snapshot table and index names use `toEpochMilli`; two snapshots in one millisecond
  collide. #6515 owner.
- 10233/10234: scan serves HTTP after its DB closed during teardown (`NodeBootstrapBase.onClosed` order). App fix
  described.
- 10214: `DbUnavailablePartiesStore` queries are not scoped by `store_id`; deterministic when the two suites share a
  shard. Production fix described.
- 10176 upstream half: vendored `EnvironmentSetup.manualDestroyEnvironment` runs `beforeEnvironmentDestroyed` outside
  its `try`. Canton test framework.
- Infra, rerun only: 10249 (CPU-saturated GKE node on cimain; bootstrap DAR-upload timeout turns slow init into a
  restart loop), 10185-10193/10195 (GitHub 504 on a nix flake input), 10204 (slow runner, logs lost).
- Self-hosted docker-large runners: 6 of about 480 docker-compose/docker-no-canton jobs from 09-28 to 09-30 went silent
  during `make docker-build -j8` and were cancelled at 45 min + 10 min (no ref filed; job list in the 2026-09-30 chat
  only). Suspected pod OOM or eviction, unconfirmed.
- 10147, 10084: Canton log-level questions; ignore patterns rejected. 10144: dup of cn-test-failures 9136, Canton.

## 5. Which refs are still open

The tracker (DACH-NY/cn-test-failures) is not readable from the sandbox. On a host whose `gh` login can read it, from
the splice repo root:
```
ci-triage/cn-test-failures-status.sh          # from a checkout of this branch, or
log/cn-test-failures-status.sh                # the copy outside the branch
```
It takes every ref recorded here (README rows, packet names, related refs in known-families, plus any arguments),
queries the tracker in batches of 50 through GraphQL, writes `log/cn-test-failures-status.tsv`
(ref, state, state reason, closed at, updated at, labels, title) and prints the open refs. Needs `gh` and `python3`.
Hand the TSV back to the assistant to prune closed refs from section 3 and 4.

## 6. Environment notes for a fresh sandbox

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
