# 10301 - docker-compose (0): two sbt servers boot concurrently under `make docker-build -j8`, project load fails, all retries attach to the hung server (run 37627325417)

New (family U). main 8e8821d75e, job `docker-compose (0)`. No test ran: the job failed in the "Run bash command"
step that builds the docker images (`make docker-build -j8`). Make started two independent `sbt --client` targets
in the same millisecond; each started its own sbt server, both compiled `project/` and loaded `build.sbt`
concurrently in the same checkout, and one failed with `NoClassDefFoundError: $0e4c0d00b4672e54c705$` (a
synthetic class of a compiled `build.sbt` expression) and stopped at the interactive "Project loading failed:
(r)etry" prompt. Neither client got a server. The five retries of the step (`cmd_retry_count: 5`) never booted a
fresh server: each client printed the same boot log within 2 ms and waited out its 300 s, consistent with
attaching to the server still hung at the prompt. The step ran 1838 s and failed. The sibling shard
`docker-compose (1)` of the same run hit the same race window (two clients, two "starting sbt server") but only one
server booted and it passed. Build infra flake, not test or app code.

- Run: https://github.com/canton-network/splice/actions/runs/37627325417, main 8e8821d75e ("Fix stale effective date validation in switchover times (#7653)"), job 112812648466 `ci / scala_test_docker_compose / docker-compose (0)`.
- Runtime canton: 3.6.1 (not involved; Canton started at 13:48:19 and kept running).
- Component: infra (Makefile `docker-build` with parallel `sbt --client` targets; `.github/actions/nix/run` retry).

Setup, from the repo root:
```
REF=10301; RUN=37627325417; mkdir -p log/$REF
gh api repos/canton-network/splice/actions/jobs/112812648466/logs > log/$REF/job.log
gh api repos/canton-network/splice/actions/jobs/112812648283/logs > log/$REF/sibling-dc1.log
L=log/$REF/job.log
```

## 1. Classification: no test report, the build step failed

```
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Process completed with exit code 1.
```
The failing step is the image build, with 5 retries:
```
sed -E 's/\x1b\[[0-9;]*m//g' $L | sed -n '10696,10714p' | grep -a -E 'make|cmd_retry_count'
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -E 'end-action id=__self.__self_6.__run'
```
```
make docker-build -j8
2026-10-07T13:48:46.8969169Z   cmd_retry_count: 5
2026-10-07T14:19:25.4203460Z ##[end-action id=__self.__self_6.__run;outcome=failure;conclusion=failure;duration_ms=1838522]
```
The tracker issue for this job is the one in the user's tuple:
```
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -o 'cn-test-failures/issues/[0-9]*' | sort -u
```
```
cn-test-failures/issues/10301
```

## 2. Two sbt clients start two servers in the same millisecond

The Makefile at the run sha has two independent rules calling `sbt --client` (DARs and party-allocator), so
`make -j8` runs them in parallel:
```
git show 8e8821d75e:Makefile | sed -n '24,35p'
```
```
$(app-bundle): $(canton-amulet-dar) $(wallet-payments-dar)
	sbt --client --batch bundle

$(canton-amulet-dar) $(wallet-payments-dar) &:
	sbt --client --batch 'splice-amulet-daml/damlBuild; splice-wallet-payments-daml/damlBuild'

$(load-tester):
	cd "${SPLICE_ROOT}/load-tester" && npm ci && npm run build

$(party-allocator):
	sbt --client --batch 'party-allocator/npmBuild'

```
```
sed -E 's/\x1b\[[0-9;]*m//g; s/\[0J//g' $L | grep -a -E "^\S+Z sbt --client|starting sbt server|done compiling|loading settings for project root|resolving key references|NoClassDefFoundError: |Project loading failed|failed to connect to server|did not start within" | sed -n '1,16p' | cut -c1-160
```
```
2026-10-07T13:48:48.6435706Z sbt --client --batch 'splice-amulet-daml/damlBuild; splice-wallet-payments-daml/damlBuild'
2026-10-07T13:48:48.6507780Z sbt --client --batch 'party-allocator/npmBuild'
2026-10-07T13:48:48.7114760Z [info] starting sbt server in the background
2026-10-07T13:48:48.7188793Z [info] starting sbt server in the background
2026-10-07T13:49:08.1832452Z [info] done compiling
2026-10-07T13:49:10.1741498Z [info] done compiling
2026-10-07T13:49:13.4043607Z [info] loading settings for project root from build.sbt...
2026-10-07T13:49:14.1157089Z [info] resolving key references (133764 settings) ...
2026-10-07T13:49:14.7435833Z [info] loading settings for project root from build.sbt...
2026-10-07T13:49:15.2396606Z [info] resolving key references (133764 settings) ...
2026-10-07T13:49:21.0933015Z [error] java.lang.NoClassDefFoundError: $0e4c0d00b4672e54c705$
2026-10-07T13:49:21.0954561Z [warn] Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
2026-10-07T13:49:21.2763611Z [error] failed to connect to server
2026-10-07T13:53:48.7300584Z sbt server did not start within 300 seconds
2026-10-07T13:53:48.7301413Z java.lang.NoClassDefFoundError: $0e4c0d00b4672e54c705$
2026-10-07T13:53:48.7307256Z [error] failed to connect to server
```
The `done compiling` (2.0 s apart) and `loading settings for project root` (1.3 s apart) pairs come from two
separate JVMs loading the same build in the same `project/target`. One client fails at 13:49:21, the other waits
the full 300 s.

## 3. The missing class is a compiled build.sbt expression

```
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -A1 -m1 '^\S*Z java.lang.NoClassDefFoundError' | sed -E 's/^[^Z]*Z //'
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -m1 'Caused by: java.lang.ClassNotFoundException' | sed -E 's/^[^Z]*Z //'
git show 8e8821d75e:build.sbt | sed -n '2751p'
```
```
java.lang.NoClassDefFoundError: $0e4c0d00b4672e54c705$
	at $8263bcc2959ca611ae21$.$anonfun$$sbtdef$1(/home/runner/_work/splice/splice/build.sbt:2751)
Caused by: java.lang.ClassNotFoundException: $0e4c0d00b4672e54c705$
Global / excludeLintKeys += `root` / wartremoverErrors
```
`$<hash>$` classes are sbt's per-expression classes for `build.sbt`, kept in `project/target/config-classes`. A
class one JVM expects there and cannot load while another JVM writes the same directory is a concurrent-write
symptom. Nothing in the build definition is wrong: the sibling shard at the same sha loads it (section 5). This
packet does not show which file was missing or truncated: the runner's `project/target` is not uploaded.

## 4. The retries never boot a fresh server

All twelve errors in the step name the same class, only attempt 1 starts servers, and every later client prints a "booting up" log:
```
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -o 'NoClassDefFoundError: \$[0-9a-f]*\$' | sort | uniq -c
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -c 'sbt server is booting up'
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -c 'starting sbt server in the background'
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -o 'Attempt [0-9]* failed with exit code [0-9]*'
```
```
     12 NoClassDefFoundError: $0e4c0d00b4672e54c705$
10
2
Attempt 1 failed with exit code 2
Attempt 2 failed with exit code 2
Attempt 3 failed with exit code 2
Attempt 4 failed with exit code 2
Attempt 5 failed with exit code 2
Attempt 6 failed with exit code 2
```
Six attempts (one plus `cmd_retry_count: 5`), two clients each: the two "starting sbt server" lines are both from
attempt 1, and the ten "booting up" lines are the ten clients of attempts 2-6.
A retry prints a complete boot, including a 3.982 s compile and the failure, within 2 ms:
```
sed -E 's/\x1b\[[0-9;]*m//g; s/\[0J//g' $L | sed -n '12346,12358p' | cut -c1-150
```
```
2026-10-07T13:53:55.2978507Z [info] sbt server is booting up
2026-10-07T13:53:55.2988547Z [info] welcome to sbt 1.12.14 (N/A Java 21.0.12.1)
2026-10-07T13:53:55.2989336Z [info] loading settings for project splice-build from plugins.sbt...
2026-10-07T13:53:55.2990249Z [info] loading project definition from /home/runner/_work/splice/splice/project
2026-10-07T13:53:55.2992159Z [info] compiling 9 Scala sources to /home/runner/_work/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-10-07T13:53:55.2993538Z [info] Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.21. Compiling...
2026-10-07T13:53:55.2993923Z [info]   Compilation completed in 3.982s.
2026-10-07T13:53:55.2994204Z [info] done compiling
2026-10-07T13:53:55.2995085Z [info] loading settings for project root from build.sbt...
2026-10-07T13:53:55.2995492Z [info] resolving key references (133764 settings) ...
2026-10-07T13:53:55.2996339Z [error] java.lang.NoClassDefFoundError: $0e4c0d00b4672e54c705$
2026-10-07T13:53:55.2997955Z [warn] Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
```
A real boot took 33 s in attempt 1 (13:48:48 to 13:49:21). Every later attempt fails 300 s after it starts
(13:58:55, 14:04:02, 14:09:09, 14:14:17, 14:19:24). The clients are replaying the boot log of a server that is still alive and blocked on the prompt
above. This is inferred from the log; the runner's process list is not available. The retry wrapper in
`.github/actions/nix/run` re-runs the command but does not stop that server, so all five retries are spent on it.

## 5. Sibling shard: same race window, one server, passes

```
sed -E 's/\x1b\[[0-9;]*m//g; s/\[0J//g' log/$REF/sibling-dc1.log | grep -a -m10 -E "^\S+Z sbt --client|starting sbt server|compiling [0-9]+ Scala sources to .*/project/|loading settings for project root|sbt server started at" | cut -c1-160
```
```
2026-10-07T13:50:35.9715581Z sbt --client --batch 'splice-amulet-daml/damlBuild; splice-wallet-payments-daml/damlBuild'
2026-10-07T13:50:35.9783534Z sbt --client --batch 'party-allocator/npmBuild'
2026-10-07T13:50:36.0403261Z [info] starting sbt server in the background
2026-10-07T13:50:36.0430750Z [info] starting sbt server in the background
2026-10-07T13:50:48.1657003Z [info] compiling 9 Scala sources to /home/runner/_work/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-10-07T13:50:48.1658120Z [info] compiling 9 Scala sources to /home/runner/_work/splice/splice/project/target/scala-2.12/sbt-1.0/classes ...
2026-10-07T13:51:00.9399258Z [info] loading settings for project root from build.sbt...
2026-10-07T13:51:00.9399723Z [info] loading settings for project root from build.sbt...
2026-10-07T13:51:11.0790281Z [info] sbt server started at local:///home/runner/.sbt/1.0/server/967ba6c70f2e473e1e91/sock
2026-10-07T13:51:11.0790891Z [info] sbt server started at local:///home/runner/.sbt/1.0/server/967ba6c70f2e473e1e91/sock
```
Here every boot line appears twice within 0.1 ms and both clients report the same socket: one server booted and
both clients followed its output. In the failing shard the pairs are seconds apart (section 2), so two servers
booted. Whether one or two servers boot depends on timing, which makes this a flake on every run of the
docker-compose shards.

## 6. The race is old; nothing in this run's change set introduced it

```
git log --format='%h %ad %s' --date=short -S"party-allocator/npmBuild" 8e8821d75e -- Makefile
```
```
b57c453e72 2025-09-12 Fix missing make definition for party-allocator bundle (#2231)
```

## Verdict

- New family U (build infra), not a duplicate. The only catalogue entry close to it is J (infra), whose entries
  are external outages; this one is caused by our own Makefile.
- Flake: whether the race hits depends on timing. It is reachable on every `make docker-build -j8` from a cold
  checkout, i.e. when no sbt server is running and both `sbt --client` targets are out of date.
- Fix location: build infra. This is not a test-side change, so no branch was written. Suggested changes:
  1. Serialize the sbt client targets so only one server boots, for example an order-only prerequisite
     `$(party-allocator): | $(canton-amulet-dar) $(wallet-payments-dar)` in `Makefile`. Alternatively, boot the
     server once (`sbt --client --batch about`) before `make docker-build -j8` in the docker-compose job.
  2. Make the retry useful: stop any sbt server between attempts (`sbt --client shutdown || pkill -f sbt-launch`)
     in the retry loop of `.github/actions/nix/run`, or at least for this command. Today, one hung server turns
     the five retries into 25 extra minutes of waiting.
- Not verified:
  - which file in `project/target/config-classes` was missing (the directory is not in the artifact);
  - that the hung server was the one from attempt 1 (no process list);
  - the Makefile change, which has not been run.
- Resolution: rerun the job.
