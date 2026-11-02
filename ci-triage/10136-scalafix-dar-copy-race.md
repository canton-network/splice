# 10136 - scalafixAll races copyResources on a .dar staging file (run 35052914872)

Branch release-line-0.8.1, sha 66a5e3f02b "Backport PR #7310 to release-line-0.8.1 (#7313)",
post-merge CI 2026-09-16T03:43Z. One failed job: static_tests. No run artifacts; the only evidence is
the job console log.

Ref mapping: Raymond confirmed 2026-09-16 that this run (35052914872, release-line-0.8.1, static_tests) is
ref 10136. An earlier draft of this packet carried 10135.

## Categorization

- Failure type: static check (sbt `lint` -> `scalafixAll`), not a test
- Component: build (sbt task graph: sbt-scalafix classpath vs `copyResources` of `apps-app`)
- Verdict: flake (task-ordering race inside one sbt evaluation), not a regression from the backport

## Setup

```
gh api repos/canton-network/splice/actions/jobs/104656956648/logs > job.log
# sibling passing static_tests job, per-step file from the run log zip (jobs/<id>/logs was truncated):
gh api repos/canton-network/splice/actions/runs/35052864473/logs > sibling.zip && unzip -q sibling.zip -d sibling
# passing step log = 'sibling/43_ci _ static_tests _ static_tests.txt'
```

## 1. Failed job and step

```
gh run view 35052914872 --repo canton-network/splice --json headBranch,headSha,jobs \
  --jq '"\(.headBranch) \(.headSha[0:10])", (.jobs[] | select(.conclusion=="failure") | "\(.databaseId) \(.name)")'
```
```
release-line-0.8.1 66a5e3f02b
104656956648 ci / static_tests / static_tests
```
```
gh api repos/canton-network/splice/actions/jobs/104656956648 \
  --jq '.steps[] | select(.conclusion!="skipped") | "\(.number) \(.conclusion) \(.name) \(.started_at[11:19])-\(.completed_at[11:19])"' \
  | grep -v success
```
```
14 failure SBT-based static checks 03:49:58-04:01:30
```
Every other step passed. The step is defined in the workflow as one sbt invocation:

```
git grep -n -A3 'SBT-based static checks' 66a5e3f02b -- .github/workflows/build.static_tests.yml
```
```
66a5e3f02b:.github/workflows/build.static_tests.yml:149:      - name: SBT-based static checks
66a5e3f02b:.github/workflows/build.static_tests.yml-150-        uses: ./.github/actions/sbt/execute_sbt_command
66a5e3f02b:.github/workflows/build.static_tests.yml-151-        with:
66a5e3f02b:.github/workflows/build.static_tests.yml-152-          cmd: "Test/compile lint updateTestConfigForParallelRuns updateDarResources"
```

## 2. The error as logged, and the sbt task that failed

```
grep -n '\[error\]\|Attempt [0-9]\|##\[error\]' job.log | cut -c1-200
```
```
11916:2026-09-16T04:01:28.5868097Z [error] error: Unable to load symbol table: /__w/splice/splice/apps/app/target/scala-2.13/classes/splice-wallet-0.1.22.dar.7c2208c0.tmp
11919:2026-09-16T04:01:29.5957464Z [error] (apps-app / scalafixAll) scalafix.sbt.ScalafixFailed: CommandLineError
11920:2026-09-16T04:01:29.5957956Z [error] Total time: 32 s, completed Sep 16, 2026, 4:01:28 AM
11921:2026-09-16T04:01:30.6036340Z Attempt 1 failed with exit code 1, retrying
11923:2026-09-16T04:01:30.6119256Z ##[error]Error: failed to run script step (id b01c53a0-...): Error: step failed with return code 1
11924:2026-09-16T04:01:30.6225929Z ##[error]Process completed with exit code 1.
```
These are the only `[error]` lines in the whole 12k-line log. The failing task is `apps-app / scalafixAll`
(inside the `lint` alias). scalafix-cli aborted because a file it found while indexing the classpath,
`apps/app/target/scala-2.13/classes/splice-wallet-0.1.22.dar.7c2208c0.tmp`, was gone by the time it read
it. The `.<8 hex>.tmp` suffix is an sbt staging file (section 3).

Timeline inside the step: `Test/compile` finished at 04:00:17 (`[success] Total time: 572 s`), the
scalafmt checks ran 04:00:22-04:01:00, `scalafixAll` started 04:01:01 and died at 04:01:28, ~3 s after
another project's resource generators ran inside the same evaluation:

```
grep -n '\[success\] Total time\|Running scalafix on\|(docs) generating daml/splice-wallet$\|Unable to load' job.log \
  | sed -n '2,3p;/Running scalafix/{p;q}' ; grep -n '(docs) generating daml/splice-wallet$\|Unable to load' job.log | tail -2
```
```
11811:2026-09-16T04:00:17.9808286Z [success] Total time: 572 s (0:09:32.0), completed Sep 16, 2026, 4:00:17 AM
11817:2026-09-16T04:00:23.0198937Z [success] Total time: 5 s, completed Sep 16, 2026, 4:00:22 AM
11854:2026-09-16T04:01:01.3171464Z [info] Running scalafix on 2 Scala sources
11887:2026-09-16T04:01:25.5631608Z [info] (docs) generating daml/splice-wallet
11916:2026-09-16T04:01:28.5868097Z [error] error: Unable to load symbol table: /__w/splice/splice/apps/app/target/scala-2.13/classes/splice-wallet-0.1.22.dar.7c2208c0.tmp
```

### 2b. The "contained different content than was expected" lines are noise

```
grep -c 'contained different content' job.log; grep -c 'ErrorResponse.scala contained different' job.log
sed -n 9571,9575p job.log | sed 's/\x1b\[[0-9;]*m//g' | grep -v ' $' | cut -c1-200
```
```
169
2
2026-09-16T03:53:21.6208209Z Warning:
2026-09-16T03:53:21.6209340Z   The file /__w/splice/splice/apps/scan/target/scala-2.13/src_managed/main/org/lfdecentralizedtrust/splice/http/v0/definitions/GetSpliceInstanceNamesResponse.scala contained different content than was expected.
2026-09-16T03:53:21.6210710Z   Existing file: org.lfdecentralizedtrust.splice.scan.admin.http.Sc
2026-09-16T03:53:21.6211301Z   New file     : cats.syntax.either._\nimport io.circe.syntax._\nimpo
```
They are guardrail `Warning:` lines emitted at 03:53 during `Test/compile` (8 minutes before the failure)
and they are not errors. Cause: `apps-scan` runs guardrail `ScalaServer` and `ScalaClient` on the same
`scan.yaml` into the same package, the server variant with an extra `imports` list, so the second pass
rewrites every `definitions/*.scala` with a different import header:

```
git show 66a5e3f02b:build.sbt | awk '/lazy val `apps-scan`/,/^lazy val `apps-scan-/' | sed -n 22,33p
```
```
          ScalaServer(
            new File(s"apps/scan/src/main/openapi/scan.yaml"),
            pkg = "org.lfdecentralizedtrust.splice.http.v0",
            modules = List("pekko-http-v1.0.0", "circe"),
            imports = List("org.lfdecentralizedtrust.splice.scan.admin.http.ScanJsonSupport._"),
            customExtraction = true,
          ),
          ScalaClient(
            new File(s"apps/scan/src/main/openapi/scan.yaml"),
            modules = List("pekko-http-v1.0.0", "circe"),
            pkg = "org.lfdecentralizedtrust.splice.http.v0",
          ),
```
The passing sibling job on release-line-0.8.0 prints the identical 169 warnings (section 5), so they are
deterministic and unrelated to the failure.

## 3. The code that produced the error (versions: sbt 1.12.14 / sbt-io 1.12.2, sbt-scalafix 0.14.2, scalafix 0.14.2)

```
git show 66a5e3f02b:project/build.properties; git show 66a5e3f02b:project/plugins.sbt | grep -n scalafix
gh api -H 'Accept: application/vnd.github.raw' 'repos/sbt/sbt/contents/project/Dependencies.scala?ref=v1.12.14' | grep -n ioVersion | head -1
```
```
sbt.version=1.12.14
4:addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.2")
16:  private val ioVersion = nightlyVersion.getOrElse("1.12.2")
```

(a) The `.tmp` file is sbt-io 1.12.2's atomic-write staging file. `copyResources` -> `Sync.copy` ->
`IO.copyFile` -> `writeFileAtomically`, which stages to `<name>.<uuid[0:8]>.tmp` in the target directory
and then renames:

```
gh api -H 'Accept: application/vnd.github.raw' 'repos/sbt/sbt/contents/main/src/main/scala/sbt/Defaults.scala?ref=v1.12.14' | grep -n 'def copyResourcesTask\|Sync.sync'
gh api -H 'Accept: application/vnd.github.raw' 'repos/sbt/sbt/contents/main-actions/src/main/scala/sbt/Sync.scala?ref=v1.12.14' | grep -n 'IO.copyFile'
gh api -H 'Accept: application/vnd.github.raw' 'repos/sbt/io/contents/io/src/main/scala/sbt/io/IO.scala?ref=v1.12.2' | grep -n 'writeFileAtomically(targetFile)\|def writeFileAtomically\|\.tmp\|ATOMIC_MOVE'
```
```
2548:  def copyResourcesTask =
2565:      Sync.sync(cacheStore, fileConverter = converter)(mappings)
91:      IO.copyFile(source, target, true)
467:  def writeFileAtomically[T](to: File)(write: File => T): T = {
475:    val staging = new File(parent, s"${prefix}${u}.tmp").toPath()
485:            StandardCopyOption.ATOMIC_MOVE
924:    writeFileAtomically(targetFile) { staging =>
```
`splice-wallet-0.1.22.dar.7c2208c0.tmp` = `<name>.` + 8 chars of a UUID + `.tmp`, exactly this pattern.
The dar is an `apps-app` resource because the whole historic dar directory is an unmanaged resource dir:

```
git grep -n 'daml/dars" }' 66a5e3f02b -- build.sbt
git ls-tree -l 66a5e3f02b daml/dars/splice-wallet-0.1.22.dar | awk '{print $4" bytes"}'; git ls-tree 66a5e3f02b daml/dars/ | wc -l
```
```
66a5e3f02b:build.sbt:2437:      Compile / unmanagedResourceDirectories += { file(file(".").absolutePath) / "daml/dars" },
1723255 bytes
222
```
222 dars (180 MB total) are synced into `apps/app/target/scala-2.13/classes/` by `apps-app / Compile /
copyResources`, each through a `.tmp` staging file.

(b) sbt-scalafix builds the semantic classpath from `dependencyClasspath` plus the `classDirectory`
*setting*, and explicitly avoids `fullClasspath`. So `apps-app / Compile / scalafix` reads
`apps/app/target/scala-2.13/classes` without depending on `apps-app / Compile / copyResources`:

```
gh api -H 'Accept: application/vnd.github.raw' \
  'repos/scalacenter/sbt-scalafix/contents/src/main/scala/scalafix/sbt/ScalafixPlugin.scala?ref=v0.14.2' | sed -n 524,527p
```
```
          // don't use fullClasspath as it results in a cyclic dependency via compile when scalafixOnCompile := true
          val classpath =
            (config / dependencyClasspath).value.map(_.data.toPath) :+
              (config / classDirectory).value.toPath
```
`scalafixAll` joins the Compile and Test `scalafix` tasks of every project into one evaluation
(`scalafixAllTask`, lines 388-418 of the same file, `.joinWith(_.join...)`). `apps-app / Test / scalafix`
depends on `Test / dependencyClasspath` -> `Compile / exportedProducts` -> `Compile / copyResources`, which
is what puts `.tmp` files into `classes/` while the Compile-scope scalafix of the same project walks it.

(c) scalafix-cli wraps the exception from indexing the classpath, and the message of a
`java.nio.file.NoSuchFileException` is just the path, which is what we see:

```
gh api -H 'Accept: application/vnd.github.raw' \
  'repos/scalacenter/scalafix/contents/scalafix-cli/src/main/scala/scalafix/internal/v1/Args.scala?ref=v0.14.2' | sed -n 200,211p
```
```
  def configuredSymtab: Configured[SymbolTable] = {
    Try(
      ClasspathOps.newSymbolTable(
        classpath = validatedClasspath,
        out = out
      )
    ) match {
      case Success(symtab) =>
        Configured.ok(symtab)
      case Failure(e) =>
        ConfError.message(s"Unable to load symbol table: ${e.getMessage}").notOk
```

## 4. What the backport changed

```
gh pr view 7313 --repo canton-network/splice --json title,mergedAt,mergeCommit,files \
  --jq '{title,mergedAt,sha:.mergeCommit.oid[0:10],files:[.files[].path]}'
gh pr view 7310 --repo canton-network/splice --json title,baseRefName,mergedAt,mergeCommit --jq '"\(.title) base=\(.baseRefName) merged=\(.mergedAt) sha=\(.mergeCommit.oid[0:10])"'
gh pr diff 7313 --repo canton-network/splice | grep -c '^[-+][^-+]'
```
```
{"files":["cluster/expected/infra/expected.json","cluster/pulumi/infra/src/istio.ts","docs/src/sv_operator/sv_helm.rst"],"mergedAt":"2026-09-16T03:44:01Z","sha":"66a5e3f02b","title":"Backport PR #7310 to release-line-0.8.1"}
Switch istio chart repo base=main merged=2026-09-15T16:04:05Z sha=b27ab3929d
16
```
Pulumi infra + docs only (istio chart repo URL). No Scala, no openapi yaml, no dars, no build files touched.
The change cannot alter `ErrorResponse.scala` generation or the sbt task graph.

## 5. Sibling branch and main comparison

Same PR backported to release-line-0.8.0 (#7314, run 35052864473, same minute) passed static_tests, and
main's post-merge run for the original #7310 merge passed it too:

```
gh run view 35052864473 --repo canton-network/splice --json headBranch,jobs \
  --jq '.headBranch, (.jobs[] | select(.name|test("static_tests / static_tests") or .conclusion=="failure") | "\(.databaseId) \(.name) \(.conclusion)")'
gh run list --repo canton-network/splice --commit b27ab3929d09ad9ca3db046935286c3047e0e52c --json databaseId,workflowName --jq '.[] | select(.workflowName|test("post-merge")) | .databaseId' \
  | xargs -I{} gh run view {} --repo canton-network/splice --json jobs --jq '.jobs[] | select(.name|test("static_tests / static_tests")) | "run {} job \(.databaseId) \(.conclusion)"'
```
```
release-line-0.8.0
104656805021 ci / static_tests / static_tests success
104657010559 ci / scala_test_wall_clock_time / wall-clock-time (8) failure
run 34992614501 job 104460789587 success
```
(The 0.8.0 run failed a different job, wall-clock-time(8); that is ref 10137, packet `10137-sv3-init-timeout-bft-onboarding-wedge.md`.)

The passing 0.8.0 static_tests log has the identical guardrail warnings and no symbol-table error, and the
same interleaving of resource generation inside `scalafixAll`; the race window is present on every run, the
collision is not:

```
f='sibling/43_ci _ static_tests _ static_tests.txt'
grep -c 'contained different content' "$f"; grep -c 'ErrorResponse.scala contained different' "$f"; grep -c 'Unable to load symbol table' "$f"
grep -n 'Running scalafix on\|(docs) generating daml/splice-wallet$' "$f" | sed -n '12,14p' | cut -c1-90
```
```
169
2
0
11906:2026-09-16T04:00:25.9195781Z [info] Running scalafix on 27 Scala sources
11910:2026-09-16T04:00:37.9957973Z [info] (docs) generating daml/splice-wallet
11915:2026-09-16T04:00:40.0095108Z [info] Running scalafix on 50 Scala sources
```

## 6. The fix already exists on main (#7176) and is missing on both release lines

Raymond's pointer: `[ci] Order apps-app compile after copyResources (#7176)`, commit 4031327bc4, merged
2026-09-10T19:58Z. It targets this exact race and names the same symptom:

```
git show 4031327bc4 -- build.sbt | sed -n '/^@@/,$p'
```
```
@@ -2435,6 +2435,11 @@ lazy val `apps-app`: Project =
       assembly / assemblyJarName := "splice-node.jar",
       // include historic dars in the jar
       Compile / unmanagedResourceDirectories += { file(file(".").absolutePath) / "daml/dars" },
+      // scalafix walks classDirectory but is only ordered after compile, not copyResources, so a
+      // DAR copy can land mid-walk and delete the .tmp it stages through, failing scalafix with
+      // "Unable to load symbol table". Ordering compile after copyResources avoids the overlap.
+      Compile / compile := (Compile / compile).dependsOn(Compile / copyResources).value,
+      Test / compile := (Test / compile).dependsOn(Test / copyResources).value,
     )
```

Which branches contain it (compare API: status `behind` = the commit is an ancestor of the branch,
`diverged` = it is not):

```
for b in main release-line-0.8.1 release-line-0.8.0 release-line-0.7.5; do printf '%-22s ' $b; \
  gh api "repos/canton-network/splice/compare/$b...4031327bc4" --jq '"\(.status) ahead=\(.ahead_by) behind=\(.behind_by)"'; done
```
```
main                   behind ahead=0 behind=35
release-line-0.8.1     diverged ahead=53 behind=14
release-line-0.8.0     diverged ahead=60 behind=11
release-line-0.7.5     diverged ahead=127 behind=8
```

The failed sha and the sibling 0.8.0 sha have no copyResources ordering in build.sbt at all; main's tip
does:

```
git show 66a5e3f02b:build.sbt | grep -n 'copyResources'; git show 7cafbf8ef1:build.sbt | grep -n 'copyResources'
gh api repos/canton-network/splice/contents/build.sbt?ref=main -H 'Accept: application/vnd.github.raw' | grep -n 'copyResources'
```
```
(no matches for 66a5e3f02b)
(no matches for 7cafbf8ef1)
2441:      Compile / compile := (Compile / compile).dependsOn(Compile / copyResources).value,
2442:      Test / compile := (Test / compile).dependsOn(Test / copyResources).value,
```

No backport PR for #7176 exists:

```
gh pr view 7176 --repo canton-network/splice --json number,title,mergedAt,baseRefName,labels \
  --jq '"#\(.number) \(.title) merged=\(.mergedAt) base=\(.baseRefName) labels=\([.labels[].name]|join(","))"'
gh pr list --repo canton-network/splice --state all --search "7176 in:title" --json number --jq 'length'
```
```
#7176 [ci] Order apps-app compile after copyResources merged=2026-09-10T19:58:38Z base=main labels=
0
```

Why the edge in #7176 is sufficient even though it is on `compile`, not `scalafix`: per the comment in
4031327bc4, scalafix "is only ordered after compile, not copyResources" (semantic rules need the
semanticdb output of `compile`), so `compile -> copyResources` transitively orders every dar sync before
scalafix starts walking `classDirectory`. The `inspect tree` check below verifies the edge per branch. The sibling 0.8.0
run and the main run of the same content passing (section 5) is consistent with a probabilistic race
that is unfixed on 0.8.0 and fixed on main.

## Root cause / hypothesis

Proven from the log and the pinned sources:
- The step failed on exactly one error: `apps-app / scalafixAll` -> scalafix-cli
  `Unable to load symbol table: .../apps/app/target/scala-2.13/classes/splice-wallet-0.1.22.dar.7c2208c0.tmp`.
- `<name>.<8 hex>.tmp` inside `classes/` is the sbt-io 1.12.2 `writeFileAtomically` staging file used by
  `copyResources`; `daml/dars` (222 dars, 180 MB) is an unmanaged resource directory of `apps-app`.
- sbt-scalafix 0.14.2 passes `classDirectory` (a setting) to scalafix-cli and deliberately does not depend
  on `fullClasspath`/`products`, so `apps-app / Compile / scalafix` has no ordering edge to
  `apps-app / Compile / copyResources`.
- The backport is pulumi/docs only; the same commit passed static_tests on release-line-0.8.0 and on main.
- The guardrail "contained different content" warnings are present identically (169) in the passing run.

Inferred:
- Within the single `scalafixAll` evaluation, `apps-app / Test / scalafix` (via `Test / dependencyClasspath`
  -> `Compile / exportedProducts` -> `copyResources`) re-synced dars into `classes/` while the
  `apps-app / Compile / scalafix` task was indexing that directory. scalafix-cli's classpath walk listed
  the staging file, it was atomically renamed before being read, `NoSuchFileException(path)` propagated,
  and scalafix reported it as the symbol-table error. sbt 1.12's `copyResources` re-running the dar sync in
  that evaluation is evidenced by the `.tmp` file's existence, not by a log line (no `--debug`).

Not caused by the backport content. It is the task-graph race that #7176 (4031327bc4) fixed on main on
2026-09-10 by ordering `apps-app` `compile` after `copyResources`; that fix was never backported, so
release-line-0.8.1 and release-line-0.8.0 (and 0.7.5) still carry the race (section 6). The large
number and size of dars in `apps-app` resources widens the window.

## Reproduction / verification

Deterministic check of the ordering edge (no CI needed): on release-line-0.8.x the first count is 0
(edge missing), on main with #7176 it is >0.
```
USER=$(id -un) direnv exec . bash -c 'sbt --batch "inspect tree apps-app/Compile/scalafix"' | grep -c copyResources
USER=$(id -un) direnv exec . bash -c 'sbt --batch "inspect tree apps-app/Compile/products"' | grep -c copyResources   # expect >0 everywhere
```
Race reproduction (probabilistic; loop it): warm the build with `Test/compile`, then repeat `scalafixAll`
and watch for the error:
```
USER=$(id -un) direnv exec . bash -c 'sbt --batch Test/compile'
for i in $(seq 1 10); do USER=$(id -un) direnv exec . bash -c 'sbt --batch scalafixAll' 2>&1 | grep -m1 'Unable to load symbol table' && break; done
```
Rerunning the failed job is the immediate workaround; the fix is the #7176 backport (section 6).

## Duplicates / related

- Duplicate of the main failure fixed by #7176 (4031327bc4, 2026-09-10; itself the outcome of the
  2026-09-08 triage round). Same signature: scalafix "Unable to load symbol table" on a `.dar.<uuid>.tmp`
  under `apps/app/target/scala-2.13/classes/`.
- No cn-test-failures issue could be searched from this sandbox (`gh search issues` -> no permission on
  DACH-NY/cn-test-failures). Earlier occurrences on release lines will show the same signature.
- 10137: the sibling release-line-0.8.0 run 35052864473, failed job wall-clock-time(8), a
  different failure.
- Not related: the guardrail duplicate-definition warnings for `apps-scan` (ScalaServer + ScalaClient into
  the same package). Cosmetic, deterministic, present on every build.

## Suggested next step / owner

1. Backport #7176 (commit 4031327bc4, build.sbt only, 5 added lines) to release-line-0.8.1 and
   release-line-0.8.0 (and 0.7.5 if it still gets static_tests runs). Use the "Backport a commit or PR
   across branches" workflow (`.github/workflows/backport_workflow.yml`, workflow_dispatch with
   `pr_number=7176` and `base_branch=release-line-0.8.x`), or cherry-pick locally:
   `git cherry-pick -s -x 4031327bc4` on each release branch. Owner: whoever owns release-line
   backports; the change is CI-only and carries no runtime risk.
2. Until the backport lands: rerun the failed job.
3. Optional (unchanged): upstream to sbt-scalafix (classpath walk should tolerate a vanished entry) or to
   scalafix (skip files that disappear during `ClasspathOps.newSymbolTable`).
