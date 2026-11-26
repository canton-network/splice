# Isolated Scalafix Rule Project Scaffold

Confirmed from this repo on 2026-09-30: `sbt.version = 1.12.14` (`project/build.properties`),
`sbt-scalafix` plugin already pinned at `0.14.2` (`project/plugins.sbt`), app code compiles with
Scala `2.13.16` (`project/CantonDependencies.scala`). The scaffold below matches those versions.
**None of this has been run against a real `sbt` invocation in this session** — treat it as a
correct-by-construction starting point (standard `scalacenter/scalafix.g8` shape), not a verified
artifact. The first thing to do with it is `sbt tests/test` and fix whatever version mismatch
surfaces.

Place this at `scalafix-rules/` at the repo root, as a fully separate sbt build (own
`project/build.properties`, own `project/plugins.sbt`) so it never touches or depends on the root
`build.sbt`.

## Directory layout

```
scalafix-rules/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── rules/
│   └── src/main/scala/fix/
│       ├── TryFailed.scala
│       └── FutureAndThen.scala
├── input/
│   └── src/main/scala/fix/
│       ├── TryFailed.scala
│       └── FutureAndThen.scala
├── output/
│   └── src/main/scala/fix/
│       ├── TryFailed.scala
│       └── FutureAndThen.scala
└── tests/
    └── src/test/scala/fix/
        └── RuleSuite.scala
```

## `project/build.properties`

```
sbt.version=1.12.14
```

## `project/plugins.sbt`

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.2")
```

## `build.sbt`

```scala
lazy val V = _root_.scalafix.sbt.BuildInfo

inThisBuild(
  List(
    organization := "com.digitalasset.canton",
    scalaVersion := "2.13.16",
  )
)

publish / skip := true

lazy val rules = project.settings(
  moduleName := "scalafix-rules",
  libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % V.scalafixVersion,
)

lazy val input = project.settings(
  // Add real Canton library deps here once TryUtil / Thereafter.syntax fixtures
  // need to actually resolve (e.g. libraryDependencies against a locally
  // published canton-base, or copy just the two files' source into a local
  // stub package under this module for a lighter-weight fixture).
)

lazy val output = project.settings(
)

lazy val tests = project
  .settings(
    libraryDependencies += "ch.epfl.scala" % "scalafix-testkit" % V.scalafixVersion % Test cross CrossVersion.full,
    Compile / compile := (Compile / compile).dependsOn(input / Compile / compile).value,
    scalafixTestkitOutputSourceDirectories :=
      (output / Compile / sourceDirectories).value,
    scalafixTestkitInputSourceDirectories :=
      (input / Compile / sourceDirectories).value,
    scalafixTestkitInputClasspath :=
      (input / Compile / fullClasspath).value,
    scalafixTestkitInputScalacOptions :=
      (input / Compile / scalacOptions).value,
    scalafixTestkitInputScalaVersion :=
      (input / Compile / scalaVersion).value,
  )
  .dependsOn(rules)
  .enablePlugins(ScalafixTestkitPlugin)
```

`V.scalafixVersion` comes from the sbt-scalafix plugin's `BuildInfo` and will resolve to whatever
version is compatible with the pinned `0.14.2` plugin — do not hardcode a version number for
`scalafix-core`/`scalafix-testkit`; let `BuildInfo` supply it so the rule, testkit, and plugin all
agree.

**Important gap to resolve during authoring, not guessed here:** the `input` module needs
`TryUtil`/`Thereafter` types to resolve for realistic fixtures (e.g. so `Future[Int]` and
`Try[Int]` receivers type-check and produce real symbols to match on). The two lightest options,
in order of preference:
1. Add a `libraryDependencies` entry on `input` pointing at a locally-published
   `community-base` artifact (if Canton publishes one locally via `sbt publishLocal` in the main
   build) — gives real symbols for `TryUtil.forFailed`/`Thereafter.syntax`.
2. If that's too heavyweight, the fixture doesn't strictly need the *real* `TryUtil`/`Thereafter` —
   only `scala.util.Try`/`scala.concurrent.Future` need to resolve (those are stdlib, free). The
   rewritten `output` fixture's `.forFailed(...)`/`.thereafter(...)` calls don't need to compile in
   the `output` module at all — `output` is only used as a text-diff target, not compiled against
   the real API. Confirm this against how `ScalafixTestkitPlugin` actually invokes `output` before
   relying on it (check whether `output` is added to `Compile` classpath anywhere that would force
   it to compile).

## Worked examples are superseded — see `corpus-notes.md`

**The snippets below were written before either rule was actually run, and both turned out to have
real bugs** (missing the infix/for-comprehension/suppression handling for `TryFailed`, and
whole-subtree-replacement corruption on chained calls for `FutureAndThen` — see
`corpus-notes.md`'s "Status: validated" section for the full list of four bugs found by actually
executing the rule against the test corpus via `references/scala-cli-workflow.md`). The symbol
strings (`scala/util/Try#failed().`, `scala/concurrent/Future#andThen().`) *did* turn out correct,
confirming the debug-printing technique works — but the surrounding tree-matching logic did not.
Treat the snippets below only as illustrations of the general `SymbolMatcher` +
`Patch.replaceTree` + `Patch.addGlobalImport` shape from SKILL.md step 5, not as something to
copy verbatim. **For working code, use `examples/TryFailed.scala`, `examples/FutureAndThen.scala`,
and `examples/Suppression.scala`** — these are the actual validated implementations, not
illustrations.

## Worked example: `rules/src/main/scala/fix/TryFailed.scala` (illustrative only, see above)

```scala
package fix

import scalafix.v1._
import scala.meta._

class TryFailed extends SemanticRule("TryFailed") {

  override def fix(implicit doc: SemanticDocument): Patch = {
    val failedMatcher = SymbolMatcher.exact("scala/util/Try#failed().")

    doc.tree.collect {
      case app @ Term.Apply(
            Term.Select(Term.Select(recv, failedMatcher(_)), Term.Name("foreach")),
            List(f),
          ) =>
        Patch.replaceTree(app, s"${recv.syntax}.forFailed(${f.syntax})") +
          Patch.addGlobalImport(importer"com.digitalasset.canton.util.TryUtil._")
    }.asPatch
  }
}
```

## Worked example: `rules/src/main/scala/fix/FutureAndThen.scala` (illustrative only — see the "Worked examples are superseded" note above; this version has the nested-patch corruption bug from `corpus-notes.md` bug #3)

Targets `thereafter` with a synthesized `case _ => ()` fallback (see `references/target-apis.md`
for why: the test corpus's pinned Canton snapshot has no `thereafterP`, confirmed by `javap`
against the resolved jar — don't design around an API that isn't on the classpath being tested):

```scala
package fix

import scalafix.v1._
import scala.meta._

class FutureAndThen extends SemanticRule("FutureAndThen") {

  override def fix(implicit doc: SemanticDocument): Patch = {
    val andThenMatcher = SymbolMatcher.exact("scala/concurrent/Future#andThen().")

    doc.tree.collect {
      case app @ Term.Apply(Term.Select(recv, andThenMatcher(_)), List(pf: Term.PartialFunction)) =>
        val casesSrc = pf.cases.map(_.syntax).mkString("\n")
        val newPf = s"{\n$casesSrc\ncase _ => ()\n}"
        Patch.replaceTree(app, s"${recv.syntax}.thereafter $newPf") +
          Patch.addGlobalImport(importer"com.digitalasset.canton.util.Thereafter.syntax._")
    }.asPatch
  }
}
```

This only handles a literal partial function (`Term.PartialFunction`) at the call site — see the
corpus case F6 (`f.andThen(onFailure)`, a named `PartialFunction` value) for why that's a real gap,
not a hypothetical one, and `references/corpus-notes.md` for how to handle it.

### Alternative: `thereafterP` (only if targeting the in-tree monorepo, not the corpus)

If a rule variant is ever built to run via `sbt scalafixAll` directly on the in-tree monorepo
(compiling against `canton/community/base/.../Thereafter.scala` rather than the published
snapshot), `thereafterP` is available there and gives a plain method-name-rename rewrite with no
fallback-case synthesis and no `Term.PartialFunction`-literal restriction:

```scala
case Term.Select(recv, name @ andThenMatcher(_)) =>
  Patch.replaceTree(name, "thereafterP") +
    Patch.addGlobalImport(importer"com.digitalasset.canton.util.Thereafter.syntax._")
```

Do not use this as the primary implementation unless the target classpath is confirmed (via
`javap`, not assumption) to expose `thereafterP`.

## `.scalafix.conf` entries once rules are ready

These custom rules run separately from the existing `.scalafix.conf` (`RemoveUnused`,
`OrganizeImports`) since they live in a different sbt build. To apply them for real, the rule
project must be published (locally is fine: `sbt rules/publishLocal`) and referenced from the
target build via `scalafixDependencies` and `--rules` — this wiring step is separate from rule
authoring and is not covered here; don't attempt it until the fixture tests pass.
