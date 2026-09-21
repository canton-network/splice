# 10185-10193, 10195 - run 35613990408 (release-line-0.8.x, backport of #7433): eleven shards fail in nix environment setup, GitHub archive download returns HTTP 504

Infra, family J. Nothing ran: every failed shard died in the runner's nix flake setup while fetching the flake input
`nix-systems/default` from GitHub, which answered HTTP 504 after the input was missing from the runner's local binary
cache and from cache.nixos.org. The head commit 183a5f972e (#7436) changed `cluster/expected/observability/expected.json`
and `cluster/pulumi/observability/src/observability.ts` only, so the failure is unrelated to the change. Raymond's refs
and jobs, recorded verbatim: 10185 docker-compose (0), 10186 dyn-sync-params-reconciliation (0), 10187 roll-forward-lsu (0),
10188 docker-canton-simtime (0), 10189 resource-intensive (0), 10190 frontend-wall-clock-time (0), 10191
frontend-wall-clock-time (1), 10192 resource-intensive (1), 10193 roll-forward-lsu (1), 10195 wall-clock-time (8).
wall-clock-time (5) (job 106380357275) failed identically and has no ref in the list.

- Run: https://github.com/canton-network/splice/actions/runs/35613990408, release-line-0.8.x 183a5f972e ("Backport PR
  #7433 to release-line-0.8.x (#7436)", merged 2026-09-21T14:42:41Z). Run still in progress when triaged.
- Component: infra (GitHub archive downloads during nix flake evaluation on the self-hosted runners).

## 1. All eleven failed jobs, first error line

```
gh run view 35613990408 --repo canton-network/splice --json jobs --jq '.jobs[]|select(.conclusion=="failure")|"\(.databaseId) \(.name)"'
for j in 106380215111 106380236928 106380302871 106380304242 106380304422 106380329683 106380329713 106380344502 106380344685 106380357187 106380357275; do gh api repos/canton-network/splice/actions/jobs/$j/logs | sed -E 's/\x1b\[[0-9;]*m//g' | grep -a -m1 -E 'unable to download|Tests: succeeded|FAILED \*\*\*|contains problems' | sed -E 's/^[^Z]*Z //' | cut -c1-170 | sed "s/^/$j: /"; done
```
```
106380215111 ci / scala_test_docker_compose / docker-compose (0)
106380236928 ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)
106380302871 ci / scala_test_record_time_tolerance / dyn-sync-params-reconciliation (0)
106380304242 ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (1)
106380304422 ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (0)
106380329683 ci / scala_test_resource_intensive / resource-intensive (0)
106380329713 ci / scala_test_resource_intensive / resource-intensive (1)
106380344502 ci / scala_test_roll_forward_lsu / roll-forward-lsu (1)
106380344685 ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)
106380357187 ci / scala_test_wall_clock_time / wall-clock-time (8)
106380357275 ci / scala_test_wall_clock_time / wall-clock-time (5)
<all eleven>: warning: error: unable to download 'https://github.com/nix-systems/default/archive/da67096a3b9bf56a91d16901293e51ba5b49a27e.tar.gz': HTTP error 504
```
No `Tests:` summary, no `FAILED`, no `contains problems` in any of them: sbt never started.

## 2. Where in the job, and the cache misses before the download

```
gh api repos/canton-network/splice/actions/jobs/106380357275/logs > log/10192/job-wct5.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10192/job-wct5.log | grep -a -B8 -m1 'unable to download' | sed -E 's/^[^Z]*Z //' | cut -c1-200
gh api repos/canton-network/splice/actions/jobs/106380357275 --jq '"started \(.started_at) completed \(.completed_at)"'
```
```
querying info about '/nix/store/yj1wxm9hh8610iyzqnz75kvs6xl8j3my-source' on 'file:///cache/nix/binary_cache'...
querying info about '/nix/store/yj1wxm9hh8610iyzqnz75kvs6xl8j3my-source' on 'https://cache.nixos.org'...
downloading 'https://cache.nixos.org/yj1wxm9hh8610iyzqnz75kvs6xl8j3my.narinfo'...
unpacking 'github:nix-systems/default/da67096a3b9bf56a91d16901293e51ba5b49a27e?narHash=sha256-Vy1rq5AaRuLzOxct8nz4T6wlgyUR7zLU309k9mBC768%3D' into the Git cache...
downloading 'https://github.com/nix-systems/default/archive/da67096a3b9bf56a91d16901293e51ba5b49a27e.tar.gz'...
warning: error: unable to download 'https://github.com/nix-systems/default/archive/da67096a3b9bf56a91d16901293e51ba5b49a27e.tar.gz': HTTP error 504
started 2026-09-21T14:51:49Z completed 2026-09-21T14:57:11Z
```
The flake input is fetched straight from GitHub because neither the runner's `file:///cache/nix/binary_cache` nor
cache.nixos.org had the source path. A GitHub 504 at that moment fails every shard that starts in the window.

## 3. The change under test is unrelated

```
gh pr view 7436 --repo canton-network/splice --json files,mergedAt --jq '{m:.mergedAt,f:[.files[]|"\(.path) +\(.additions)-\(.deletions)"]}'
git show 183a5f972e --stat -- nix flake.nix flake.lock .github | wc -l
```
```
{"m":"2026-09-21T14:42:41Z","f":["cluster/expected/observability/expected.json +3-3","cluster/pulumi/observability/src/observability.ts +4-4"]}
0
```

## Verdict

- Infra flake, family J, same class as 10133 (ghcr.io image pull timeout) and 10150 (Docker Hub 502). Not a splice
  bug and not caused by #7436. Ten refs plus one unreferenced job, all one event.
- Fix: none in the repo. `gh run rerun 35613990408 --repo canton-network/splice --failed` once the run has completed;
  if GitHub still returns 504 the rerun fails the same way.
- Worth raising with the runner owners: the flake input `nix-systems/default` (and `numtide/flake-utils`, downloaded
  just before it) is fetched from GitHub on every job because it is not in the runner binary cache; pre-populating
  the cache would remove this failure class.
- Not verified: whether main's runs in the same window failed identically (see README cross-cutting note if checked).
