# 10496 - docs job: sbt's npm install of the root workspace hangs until the 40 min sbt timeout (run 39963664449)

New family, infra/build tooling. Main 0c8c834609 (#7839), job `docs`, killed by the 40 min sbt timeout. The same
hang killed the docs job on 2026-10-22 (run 39763135051, cn-test-failures 10489, closed). Docs passed in 37 of the
last 40 main runs (one more had no docs job).

- Run: https://github.com/canton-network/splice/actions/runs/39963664449, main 0c8c834609 (#7839), job 120447813053 `docs`.
- Component: build tooling (`BuildCommon.npmInstall` -> `build-tools/npm-install.sh`).

## 1. Timeout, no test output

```
gh api repos/canton-network/splice/actions/jobs/120447813053/logs > log/10496/job.log
grep -a -n -E "Killed SBT|Npm install (called|completed)" log/10496/job.log | grep -v 'echo "' | sed -E 's/^([0-9]+):([0-9T:.-]+)Z /\1 \2 /' | cut -c1-140
```
```
10589 2026-10-23T13:50:54.9606552 Npm install called for /__w/splice/splice/token-standard/splice-api-token-metadata-v1/openapi-ts-client. Id: 0
10606 2026-10-23T13:51:03.2135362 Npm install completed for /__w/splice/splice/token-standard/splice-api-token-metadata-v1/openapi-ts-client. Id: 0
10607 2026-10-23T13:51:03.2136019 Npm install called for /__w/splice/splice/token-standard/cli. Id: 1
10621 2026-10-23T13:51:20.4090385 Npm install completed for /__w/splice/splice/token-standard/cli. Id: 1
12894 2026-10-23T13:57:15.6389632 Npm install called for /__w/splice/splice/apps/common/frontend/../... Id: 2
12927 Killed SBT after timeout 40m
```
Last sbt output is at 13:57:28 (`(docs) copying splice-api-token-allocation-instruction-v2`); nothing for 32 minutes.

## 2. Same hang on 2026-10-22; the green run never reaches install Id 2

```
gh api repos/canton-network/splice/actions/jobs/119771965599/logs > log/10496/other.log   # run 39763135051 (10489)
gh api repos/canton-network/splice/actions/jobs/120457432839/logs > log/10496/green.log   # run 39966442655, docs success
for f in other green; do grep -a -n -E "Npm install (called|completed)" log/10496/$f.log | cut -c1-140; done
```
```
== other
2026-10-22T10:33:14 Npm install called ... openapi-ts-client. Id: 0 / completed 10:33:24
2026-10-22T10:33:26 Npm install called ... token-standard/cli. Id: 1 / completed 10:33:40
2026-10-22T10:38:16 Npm install called for /__w/splice/splice/apps/common/frontend/../... Id: 2
(Killed SBT after timeout 40m at 11:11:50)
== green
Id 0 and Id 1 called and completed; no Id 2 (root workspace install cached)
```

## 3. The install has retries but no timeout

```
git show cn/main:build-tools/npm-install.sh | tail -4
git show cn/main:project/BuildCommon.scala | sed -n 1206,1217p
```
```
else
  # shellcheck disable=SC2015
  for _ in {1..5}; do npm ci --no-audit && break || sleep 15; done
fi
        println(s"Npm install called for ${npmRootDir.value}. Id: $i")
        BuildUtil.runCommandWithRetries(Seq(npmInstallScript.getAbsolutePath), log, None, Some(npmRootDir.value))
        println(s"Npm install completed for ${npmRootDir.value}. Id: $i")
```
A `npm ci` that stalls (rather than failing) is never retried; the sbt step timeout is the only bound, and npm's
output is not shown until the command ends, so the stall itself leaves no evidence.

## Verdict

New family: root-workspace `npm ci` hang in CI, twice in 40 main runs (10489, 10496). Not a test or app issue. Fix
in build tooling: bound each attempt (`timeout 10m npm ci --no-audit ...` in `build-tools/npm-install.sh`) so the
existing retry loop engages, and stream npm's output (or `--loglevel http`) so the next hang shows which request
stalls. Not verified: what npm was waiting on (no output captured).
