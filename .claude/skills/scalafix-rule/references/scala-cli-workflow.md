# Validated Workflow: scala-cli Instead of the sbt Testkit Scaffold

When the target is a scala-cli-based test corpus (a single `.scala` file with `//> using` directives
pinning a dependency and compiler flags — exactly the shape of the TryFailed/FutureAndThen test
corpus), this workflow is lighter and faster to iterate in than the sbt `rules`/`input`/`output`/
`tests` scaffold in `scaffold.md`, and is what was actually used to validate the `TryFailed` and
`FutureAndThen` rules end-to-end. Prefer it when the target is a standalone corpus file; prefer the
sbt scaffold when the target is the in-tree monorepo itself (no scala-cli project exists there to
piggyback on).

## Setup (one time)

`scala-cli` ships as a single self-contained binary — get the release matching the host
architecture from `https://github.com/VirtusLab/scala-cli/releases/latest` (check `uname -m`
first; conflating `x86_64` and `aarch64` assets fails with `Exec format error`, not a helpful
message). No JDK version matching is needed beyond having *a* JDK on `PATH`.

`scala-cli fix` (a `--power`/experimental subcommand) runs Scalafix natively and resolves its own
internal `scalafix-cli`/`scalafix-core` toolchain independently of the target file's own Scala
version — run `scala-cli fix <file> --power -v -v 2>&1 | grep scalafix` once to see exactly which
`scalafix-core_2.13:<version>` it fetches, and pin the rule project's own
`ch.epfl.scala::scalafix-core` dependency to that same version (confirmed working here:
`0.14.9`, resolved by scala-cli 1.17.1).

## Rule project layout

No sbt needed. A flat directory of scala-cli files plus a resources folder:

```
rules/
├── src/
│   ├── Suppression.scala   # shared @SuppressWarnings-detection helper (see SKILL.md, corpus req. #2)
│   ├── TryFailed.scala
│   └── FutureAndThen.scala
└── resources/
    └── META-INF/services/scalafix.v1.Rule   # one fully-qualified rule class name per line
```

Each rule `.scala` file needs its own `//> using` header (only the *first* file's directives are
authoritative when packaging multiple files together — scala-cli warns about this but still works):

```scala
//> using scala 2.13.16
//> using dep ch.epfl.scala::scalafix-core:0.14.9
```

`META-INF/services/scalafix.v1.Rule` is the standard Java `ServiceLoader` registration scalafix's
rule loader uses — plain text, one fully-qualified class name per line (e.g. `fix.TryFailed`). This
replaces the `@AutoService`-annotation-processor approach the sbt g8 template usually sets up;
writing the file by hand is simpler for a scala-cli project.

## The debug-symbol step, concretely

SKILL.md step 3 says to discover exact `SymbolMatcher` strings by printing them — here's the exact
mechanism that worked:

```scala
// src/Debug.scala
//> using scala 2.13.16
//> using dep ch.epfl.scala::scalafix-core:0.14.9
package fix
import scalafix.v1._
import scala.meta._

class Debug extends SemanticRule("Debug") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    doc.tree.traverse {
      case t: Term.Select => System.err.println(s"SELECT [${t.syntax}] symbol=[${t.symbol.value}]")
    }
    Patch.empty
  }
}
```

`Tree#traverse(pf: PartialFunction[Tree, Unit]): Unit` runs side effects during the walk — simpler
than trying to route debug info through `Patch.lint` while iterating. This is also how the
`Term.EnumeratorsBlock` wrapper (corpus-notes.md bug #2) was found: when a structural match
silently fails, print `t.parent.map(_.getClass.getName)` at the fallback case rather than
re-guessing from the grammar.

## Build and run loop

```bash
# 1. Compile-check a single rule fast (catches API misuse before packaging)
scala-cli compile rules/src/TryFailed.scala

# 2. Package everything (rules + shared helpers) into one jar with resources
scala-cli --power package rules/src/*.scala --resource-dir rules/resources --library -o rules/rules.jar -f

# 3. Run the rule(s) against the corpus — --scalafix-rules can repeat, NOT comma-join
scala-cli fix Corpus.scala --power \
  --scalafix-rules TryFailed --scalafix-rules FutureAndThen \
  --scalafix-arg="--tool-classpath=rules/rules.jar"
# `--jar rules/rules.jar` (the generic classpath flag) does NOT put the jar on scalafix's own
# rule-loading classpath — scala-cli's `fix` shells out to an isolated scalafix-cli process.
# `--scalafix-arg="--tool-classpath=..."` forwards the flag scalafix-cli itself understands.

# 4. `scala-cli fix` (no --check) rewrites the file in place. Diff against a saved original,
#    then re-verify the *rewritten* file still compiles under the corpus's own strict flags —
#    this is the actual acceptance bar, not just "the rule ran without crashing":
scala-cli compile Corpus.scala
```

Without `--check`, `scala-cli fix` mutates the target file directly — keep a pristine copy (`cp
Corpus.scala /tmp/Corpus.scala.orig`) to diff against and restore between iterations, since a
half-fixed corpus makes it hard to tell which case's rewrite (if any) is wrong on the next run.

Lint-only diagnostics (`Patch.lint(Diagnostic(...))`, for corpus cases like T6/T7/F6/F7) print to
stderr as `error: [RuleName.RuleName] <message>` pointing at the flagged span, without modifying
the file — confirm the LINT cases produce exactly this and nothing else (no rewrite, no crash).
