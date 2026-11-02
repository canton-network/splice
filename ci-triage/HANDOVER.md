# CI triage handover (state as of 2026-11-02)

Everything reproducible about the splice CI failure triage since 2026-09-08 lives on this branch,
`ray/ci-triage-2026-09-15`. A new person needs: this branch, the skill directories it carries, a sandbox with `gh`
access to canton-network/splice and DACH-NY, and the cn-test-failures inbox. Downloaded artifacts and Canton jars
are not part of the handover; every packet contains the commands that fetched them.

Section 0 is the current state. Sections 3 and 4 are the snapshot of 2026-09-22 and were not rechecked since.

## 0. Current state (2026-11-02)

### How the work runs now

Since October the unit of work is the inbox: items of board https://github.com/orgs/DACH-NY/projects/48/views/1
with Status = Inbox. `/ci-triage-inbox` (skill in `ci-triage/skill/ci-triage-inbox/`) fetches it, splits it into
already-triaged items and new groups, triages each group in a background fork with `/ci-triage`, and writes one
dated packet dir per round (`ci-triage/2026-10-30/`, `ci-triage/2026-10-30b/`, `ci-triage/2026-11-02/`). Each
dir has a `README.md` with the verdict per issue and `host-commands-*.md` with state-guarded `gh` commands
(close dup, retitle "<Suite/job>: <cause>", root-cause comment) that a person with DACH-NY rights runs on the host.
The assistant never closes, comments or pushes itself.

`scripts/inbox_fetch.py` calls `ghx <api path>`, a token router on Raymond's host. Without it, create a shim that
forwards to `gh api` with a token that covers DACH-NY:
```
printf '#!/bin/sh\nexec gh api "$@"\n' | sudo tee /usr/local/bin/ghx && sudo chmod +x /usr/local/bin/ghx
```
Then: `python3 -I ci-triage/skill/ci-triage-inbox/scripts/inbox_fetch.py --min-number 10900 --triage-dir ci-triage`.

### Round 2026-11-02 (in progress): `ci-triage/2026-11-02/`

37 inbox items from 10900 on: 3 triaged earlier, 34 new.

| Issues | Group | State |
|---|---|---|
| 11108-11116, 11119-11127, 11129-11135 | mainnet-Daml nightlies 41171022068, 41275427340, 41391921766 (same sha 3525c637db) | all 25 dups: 16 of 10927 (X2), 6 of 10933 (traffic), 3 of 10930 (coupon); none fixed on main, so about 8 new issues per night until they are. X1 (10934) and X3 (10937) confirmed fixed at PV 36. `nightly-a.md` |
| 11128 (+ dup 11136) | ScanPartyMonitorScriptIntegrationTest (#8107) | new family Z, test flake: /v0/events caps at the last DSO-visible update; fix: tap after createTransferOffer; owner stephencompall-DA |
| 11104 | LsuIntegrationTest, log check only | new family AA: sv4Validator faucet trigger ERROR after 35 SEQUENCER_BACKPRESSURE retries while late sv4 sequencer is blacklisted; fix: pause that trigger until sv4 upgrades; owner Martin Florian |
| 11105 | wall-clock-time, log check only | Canton 3.7.0: Internal ACS Updates Stream terminated abruptly, commitment matcher stays Failed; first hit, to Canton team if it repeats |
| 11107 | simtime | dup of 10907 (julientinguely-da) |
| 11117 | Devnet backup_status_cluster 10-31 | stays open: first night after the Devnet LSU switch to serial 7 (cni #7836/#7837), cause open, needs log; owner suggestion Krzysztof |
| 11118 | Devnet backup_cluster 10-31 | dup of 11117 |
| 11106, 11137 | CircleCI deploy_sv_reonboard 87744, 87814 | not 10844 (#8125 deployed); intermittent (2 of 7 runs), needs log |

Not run yet: every command in `ci-triage/2026-11-02/host-commands-*.md`. The log fetches for the four infra items
are at the top of `host-commands-c.md` (CircleCI needs `CIRCLECI_TOKEN`; cni Actions logs need `gh` on the host).
All issue commands of the round are in one script, `ci-triage/2026-11-02/run-issues.zsh` (41 state-guarded steps).

Fix branches pushed to canton-network/splice without a PR (all apply cleanly on main 2026-11-02, none compiled
against current main): `ray/fix-10197-bft-read-confirmation-wait` (10197 open, one-line wait), 
`ray/fix-reset-topology-plugin-no-exit` (10139 open), `ray/fix-topology-init-limit` (10140 closed, likely obsolete),
`ray/fix-fail-fast-init` (uncompiled, no proving test). Not pushed: `ray/fix-localnet-protocol-version` (moves
`CANTON_PROTOCOL_VERSION` to the SV's `initial-protocol-version` in LocalNet; untested). Superseded, drop:
`ray/fix-gha-runner-version-operator` (#7672 merged).

### Open items with an owner question (live state 2026-11-02)

| Issue | Subject | Owner |
|---|---|---|
| 10912 | ScanEventHistoryIntegrationTest: background reward/licence tx lands after the pre-tap cursor | rautenrieth-da (assigned) |
| 10915 | Devnet backup_status_cluster: 18:00 check finds stale backups when the 16:00 backup has not finished | none; suggestion Krzysztof |
| 10917 | DockerComposeValidatorFrontendIntegrationTest: portfolio app 'Failed to list contracts' | none; recheck after #8130 |
| 10918 | Compose/LocalNet wallet frontend: tap row not found in transaction history | none; root cause not found |
| 10919 | ExpiryWithIgnoredAmuletVersionIntegrationTest: startup times out, splitwellValidator DB lock | none; lock holder unknown |
| 10927 | PV 36 nightly: joining SV outbox cannot push identity (X2) | moritzkiefer-da (assigned) |
| 10930, 10933 | MainNet-packages tests: no RewardCouponV2; traffic purchase UNKNOWN_INFORMEES | none; suggestion Simon Meier |

Closed last week, for context: 10934 (X1, fixed by #8149, prove on the next mainnet-Daml nightly at PV 36),
10937 (X3), 10922 (cluster lock reaper), the 10939-11070 compile-break batch (#8150).

## 1. What is where

| Item | Location | Notes |
|---|---|---|
| Evidence packets (43) | `ci-triage/<ref>-<slug>.md` | One per ref, or one per family with numbered occurrence sections (`10155-10158-...` holds family A, ten hits) |
| Index | `ci-triage/README.md` | Ref -> run -> job mapping, one-line overview per ref, cross-cutting notes, fix-branch table (the table below supersedes its status column) |
| Triage skill | `ci-triage/skill/ci-triage/` | `SKILL.md` procedure, `references/{conventions,recipes,packet-template,known-families}.md`. Install: `cp -r ci-triage/skill/ci-triage ~/.claude/skills/`, then `/ci-triage <run id or url> <ref>` |
| Flake families A-L | `ci-triage/skill/ci-triage/references/known-families.md` | Signature, confirming grep, parent ref, dups, fix per family. Check it before deep analysis |
| Fix branches | local branches `ray/fix-<ref>-<slug>` (Raymond's prefix; yours will be `<user>/`) in the sandbox that produced them | Status table in section 3; lift with `git fetch sandbox-<name> <branch>` on the host, then push |
| sbt without the nix dev shell | `ci-triage/skill/ci-triage/references/sandbox-sbt-env.sh` | Only when `direnv exec . sbt` cannot realize the dev shell (root overlay full); paths are those of the splice-ready template image |

Not on the branch, by design: `CLAUDE.md` files (local), the memory files of the assistant, `log/` (12 GB of
artifacts and jars).

## 2. Moving the work

From the sandbox that holds the branch, on the host (sandbox name is `$SANDBOX_VM_ID` inside it; here `cn`):
```
git fetch sandbox-cn ray/ci-triage-2026-09-15
git push origin ray/ci-triage-2026-09-15
```
The branch carries only `[skip ci]` commits and must never be merged; it lives as a plain branch on
canton-network/splice. Colleague, in a fresh sandbox:
```
git fetch origin ray/ci-triage-2026-09-15 && git checkout ray/ci-triage-2026-09-15
cp -r ci-triage/skill/ci-triage ci-triage/skill/ci-triage-inbox ~/.claude/skills/
```
Continue committing packets on the same branch with `git commit -s -m "[skip ci] ..."`, one subject line, ASCII only.

## 3. Fix branch status (snapshot 2026-09-22)

Checked 2026-09-22 with `git cherry <base> <branch>` (patch present upstream) and the PR list of canton-network/splice.

| Branch | Ref(s) | Upstream | Action |
|---|---|---|---|
| ray/fix-10173-unhide-expire-coupon-ttl | 10173 (= 9491, #7206) | PR 7417 merged 09-21 | close refs |
| ray/fix-10174-lsu-source-ignore-regex | 10174 | PR 7423 merged 09-18 | closed |
| ray/fix-10175-unclaimed-activity-record-keep-merge-paused | 10175 | PR 7425 merged 09-21 | closed |
| ray/fix-10179-member-traffic-status-consistent-read | 10179 | PR 7428 merged 09-21 | close ref |
| ray/fix-multihost-acs-mismatch | 10111 + 9 dups (family A) | PR 7435 merged 09-21 | watch main for a week, then close 10111, 10129, 10146, 10155, 10158, 10162, 10164, 10167, 10178, 10182 |
| ray/fix-venue-allocation-wait | 8784 | PR 7416 merged 09-18 | closed |
| ray/fix-auth0-relogin-retry | 10143 | PR 7412 merged 09-18 | close ref |
| ray/fix-round-opening-wait-budget | 9740, 10170 | PR 7400 merged 09-18 | closed |
| ray/fix-sv-ui-test-timeouts | 10141, 10145 | PR 7380 merged 09-18 | close refs |
| ray/fix-bulk-storage-test-stubbing-race | 10149 | PR 7374 merged 09-17 (merged version differs from the branch) | closed |
| ray/fix-multiarch-check-retry | 10150, 10172 | PR 7414 merged 09-18 (merged version differs) | closed |
| ray/fix-scalafix-copyresources-race | 10136 | #7176 merged 09-10 | closed |
| ray/fix-sv-ui-preflight-rate-limit | (preflight, 09-09) | upstream by patch | done |
| ray/backport-7261-release-line-0.8.3, ray/backport-7299-release-line-0.8.3 | 10154/10166/10171, 10169 | PRs 7401, 7402 (0.8.3) and 7405, 7406 (0.8.x) merged 09-18 | closed |
| ray/fix-10183-reset-namespace-late-proposer | 10183 | PR 7441 OPEN | review, merge |
| ray/fix-10184-mediator-pruning-backoff-ignore | 10184 | PR 7440 OPEN | decide: sim-time-only ignore pattern |
| ray/fix-10176-sanity-check-pause-timeout (e59f6538c0) | 10176 | not pushed | compile, PR. scalafmt only |
| ray/fix-10197-bft-read-confirmation-wait (964e7df114) | 10197 | not pushed | compile, PR. scalafmt only |
| ray/fix-reset-topology-plugin-no-exit (743babe3f0) | 10137, 10139, 10183 (shared symptom) | not pushed | compiled in sandbox; PR. Turns the teardown `sys.exit(1)` into a suite failure |
| ray/fix-topology-init-limit (72dc373508) | 10140 | not pushed | config only; PR |
| ray/fix-fail-fast-init (2832e6da66, 2 commits) | 10088-A, 10180 (#7289) | not pushed | uncompiled, no proving test; `ray/fix-fail-fast-init-preRebase` is a superseded draft, drop it |
| ray/fix-summarizing-round-log-noise (2fb77e0be2) | 10121, 10142 | not pushed | uncompiled; app-side log level change, needs owner review |

## 4. Open items without a fix branch (snapshot 2026-09-22)

- 10165 (umbrella for 10094, 10153, 10161, 10137): BFT 1 -> N sequencer onboarding step loses strong quorum, sv1
  blacklisted for epochs, 120 s ack deadlines and a 38 s HTTP timeout. Canton-side; not fixed as of the canton
  mirror state 2026-09-15.22. Paste-ready issue text in `10165-issue-body.md`, repro in `10165-repro.md`.
- 10180: validator init reads the topology store by logical synchronizer id while its participant is mid-LSU;
  app fix described in the packet (wrap `listAllTransactions` in `NodeInitializer.rotateOwnerToKeyMappingNotSignedByKeys`
  with `retryProvider.retry`, NOT_FOUND is already retryable). Third variant of the 10088-B / 10174 restart window.
- 10176 upstream half: vendored `EnvironmentSetup.manualDestroyEnvironment` runs `beforeEnvironmentDestroyed`
  outside its `try`, so one failing teardown plugin leaks the environment and the Prometheus port. Canton test framework.
- 10139 / 10197 (family L): reference block sequencers on one Postgres retry `insert block` on SQLSTATE 40001 with
  exponential backoff; slow ordering or teardown exits. Canton / test-infra sizing.
- 10147, 10084: Canton log-level questions (BFT P2P Connecting WARN at shutdown; IndexerState reconnect-drain WARNs).
  Ignore patterns were rejected for both.
- 10144: GetPreferredPackages metadata-view race, dup of cn-test-failures 9136, Canton.
- 10185-10193, 10195: one GitHub 504 on a nix flake input took eleven shards of one run; infra, rerun. Ask the
  runner owners to cache `nix-systems/default` and `numtide/flake-utils`.

## 5. Environment notes for a fresh sandbox

- The tracker DACH-NY/cn-test-failures is not readable with the sandbox token; refs arrive as
  `(run URL, job name, ref)` from the person filing them. Never map refs to jobs by elimination when a run has
  several failed jobs; record "mapping open" until told.
- `canton/` in the repo is a stale rsync copy (VERSION 3.5.7-SNAPSHOT); what runs is `nix/canton-sources.json` at the
  run's sha. Cite Canton behaviour against the jar of that version (`recipes.md` section 6); jars come from
  `https://www.canton.io/releases/canton-open-source-<version>.tar.gz` (about 300 MB each, keep them under `log/`).
- Root overlay is small (0.2 to 2 GB free). Put `TMPDIR`, artifacts and jars on the repo mount (`log/`). Never pipe a
  large `zcat` into `sort`, it spills to `/tmp` and kills every running command; count with `awk`. Never run
  `nix-collect-garbage`.
- Job logs: GitHub masks `{` and `}` as `***`; ignored checkErrors lines carry the suffix
  `(ignore this line in check-sbt-output.sh)`, the real problems are the `@timestamp` lines without it.
- Fix branches: one branch per PR off `origin/main` (or the release line for a backport, `cherry-pick -x -s`),
  named `<user>/fix-<ref>-<slug>`; single `[ci]` commit, `-s`, no code comments, no AI attribution. `[skip ci]` makes a
  `<user>` is your own short git prefix; the existing branches use `ray`.
  PR unmergeable; `[static]` is the cheap mergeable tag. Do not compile or start Canton in the sandbox unless asked;
  `apps-app/Test/scalafmtCheck` in a `git worktree` is the cheap check.
- To fetch a branch from the host repo inside a `--clone` sandbox: `git fetch /run/sandbox/source <branch>`.
  `origin` (hyperledger-labs, SSH) is refused from the sandbox; use the HTTPS remote for canton-network/splice.
- Run a `/ci-triage` as a background fork when the person wants to keep talking; independent shell steps in parallel.
