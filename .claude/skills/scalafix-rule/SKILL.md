---
name: scalafix-rule
description: This skill should be used when the user asks to "write a scalafix rule", "add a scalafix rule for <wart>", "auto-fix this wart", "rewrite Try.failed to forFailed", "rewrite Future.andThen to thereafter/thereafterP", "scalafix rule for TryFailed", "scalafix rule for FutureAndThen", or wants a WartRemover-detected pattern in this repo turned into an automatic rewrite.
---

# Writing Scalafix Rules for This Repo

This repo (Canton / Splice) already detects certain harmful patterns via custom WartRemover
rules (`canton/community/lib/wartremover/src/main/scala-2/com/digitalasset/canton/*.scala` — the
`scala-2` variant is the one that actually runs, since the compiled application code targets
Scala 2.13, not Scala 3). WartRemover only flags violations; it does not fix them. A Scalafix
`SemanticRule` closes that gap: `sbt scalafixAll` rewrites every violation automatically.

Writing a correct semantic Scalafix rule requires binding to the *resolved symbol* of the method
call, not just its surface syntax — otherwise the rule either misses type-driven distinctions
(e.g. `Future#andThen` vs. `PartialFunction#andThen`, which look identical in source) or rewrites
things it shouldn't. The workflow below exists to make that binding step fast and reliable instead
of guesswork.

## 1. Read the wart before writing the rule

For any wart being auto-fixed, read its `scala-2` source under
`canton/community/lib/wartremover/src/main/scala-2/com/digitalasset/canton/`. It already contains
the exact type-symbol match (e.g. `receiver.tpe.typeSymbol == futureTypeSymbol`) that the Scalafix
rule must replicate using SemanticDB symbols instead of `scala.reflect` types. The wart's own
Scaladoc names the replacement API — treat that as the rewrite target, but re-verify the target
API's current signature directly from source (see `references/target-apis.md` for the two APIs
already researched: `TryUtil.forFailed` and `Thereafter.syntax.thereafterP`; re-grep if the wart is
a different one).

## 2. Scaffold a rule project

If the target is a standalone scala-cli file (e.g. a test corpus with its own `//> using`
directives) rather than the in-tree monorepo, use `references/scala-cli-workflow.md` — a flat
`scala-cli`-based rule project with no sbt involved. **This is the validated path**: it's what was
actually used to build and pass the full TryFailed/FutureAndThen corpus, is much faster to iterate
in than sbt, and `scala-cli fix` natively runs Scalafix (`--scalafix-rules`, `--scalafix-arg
--tool-classpath=...`) against a real compiled-with-real-flags target.

If the target is instead the in-tree monorepo directly (no scala-cli project exists there), do not
touch the root `build.sbt` — it is enormous and fragile. Build the rule in a small, self-contained
sbt project instead (the standard `rules` / `input` / `output` / `tests` layout used by the
`scalacenter/scalafix.g8` template, untested-against-real-sbt as of this writing — see
`references/scaffold.md`). Put it at `scalafix-rules/` at the repo root (sibling to `canton/`,
`apps/`, etc.), never inside the root build.

## 3. Find the exact symbol before matching on it

This is the step that most often goes wrong. Scalameta symbol strings encode the owner, method
name, and a descriptor for overload resolution (e.g. `scala/concurrent/Future#andThen().`), and
getting the descriptor wrong makes the matcher silently match nothing. Do not guess it — discover
it:

1. In the `rules` module, write a throwaway rule whose `fix` method does
   `doc.tree.collect { case t: Term.Select => t.symbol }.filter(_.value.nonEmpty)` and prints
   `symbol.structure` (or just `symbol.value`) for every `Term.Select`/`Term.Apply` node touching
   the pattern of interest.
2. Run it against the `input` fixture (see step 4) via the `tests` module.
3. Read the printed symbol strings from the test output and hardcode the exact one(s) into
   `SymbolMatcher.exact(...)`.
4. Delete the debug printing once the real matcher is in place.

This defeats the temptation to pattern-match on syntax (`Term.Name("andThen")`) alone, which is
exactly the bug WartRemover itself avoids by checking `receiver.tpe.typeSymbol`.

## 4. Test-driven fixture loop

Never write the rewrite `Patch` before there is a failing test to drive it:

1. Add a minimal snippet to `input/src/main/scala/fix/<RuleName>.scala` reproducing precisely the
   shape the wart flags (copy the shape from the wart's own match arm, e.g.
   `t.failed.foreach(f)` or `future.andThen { case Failure(e) => ... }`). Keep it minimal — one or
   two violating call sites, plus one *non*-violating look-alike (e.g. a `PartialFunction#andThen`
   call) to prove the symbol matcher doesn't over-fire.
2. Hand-write the expected result in the matching `output/` file, including the added import.
3. Run `sbt tests/test`. It fails until the rule + fixture agree exactly, including import
   ordering and whitespace.
4. Iterate on the `Patch` (not the fixture) until it passes.

Only after the fixture-driven test passes should the rule be considered a candidate for real use.

## 5. Building the `Patch`

- Prefer replacing the smallest, most disjoint spans possible (e.g. just a method-name `Term.Name`,
  or just an argument subtree) over reconstructing and replacing a whole enclosing node via a
  `.syntax`-interpolated string. **This matters most when a match can nest inside another match**
  (e.g. chained calls like `f.andThen{...}.andThen{...}`, where the outer call's receiver *is* the
  inner call's node): replacing the whole outer node with a string that embeds the inner node's
  *original* `.syntax`, while the inner node is *also* being independently replaced, corrupts the
  output into garbled/duplicated text. Confirmed empirically — see `references/corpus-notes.md`
  bug #3. Disjoint per-match patches compose correctly regardless of nesting depth.
- When reconstructing a node from parts is unavoidable (no nesting risk), build the new source
  from the original subtrees' `.syntax` (e.g. `s"${recv.syntax}.forFailed(${f.syntax})"`) so
  formatting of the untouched parts (arg bodies, comments) survives.
- Use `Patch.addGlobalImport(importer"com.digitalasset.canton.util.TryUtil._")` (or the
  `Thereafter.syntax` equivalent) — Scalafix dedupes imports and inserts them in the conventional
  location, so don't hand-manage import text.
- For the `andThen` → `Thereafter` rewrite specifically: target `thereafter` plus a manually
  appended `case _ => ()` fallback arm, not `thereafterP` — `thereafterP` doesn't exist on the
  classpath most rules are actually tested against (verify with `javap` against the resolved jar,
  don't assume from reading in-tree source; see `references/target-apis.md` for how this went
  wrong once already). `thereafter`'s body must be total; `andThen`'s partial function need not be,
  so the fallback case is required or the rewritten code fails to compile under
  `-Xfatal-warnings` ("match may not be exhaustive"). See `references/target-apis.md` and
  `references/scaffold.md` for the full rewrite and the `thereafterP` alternative for the narrower
  case of targeting the in-tree monorepo directly.

## 6. Before calling a rule done

- `sbt tests/test` passes for every fixture, including the negative (non-violating look-alike)
  case.
- Manually copy one real call site from the codebase (see `references/target-apis.md` for known
  real usages) into the `input` fixture and confirm the rewrite matches what a human would write.
- Note in the PR/handoff that the rule has not been run against the full monorepo yet (that
  requires wiring `scalafixDependencies`/`.scalafix.conf` in the real build, a separate step from
  authoring the rule) — don't claim broader validation than the fixture tests actually cover.

## 7. Expect a three-way outcome, not just match/no-match

A test corpus for these two rules tags every case REWRITE / LINT / UNCHANGED / DECIDE. Don't design
the rule as a binary "matches → rewrite, else leave alone" — several corpus cases (a `.failed` not
immediately followed by `.foreach`, an `andThen` fed a named `PartialFunction` value instead of a
literal, an `andThen` with an explicit trailing `ExecutionContext` argument) share the *same
resolved method symbol* as the safe cases but must not be silently rewritten, and must not be
silently ignored either — they need a `Patch.lint(Diagnostic(...))` so the signal isn't lost. See
`references/corpus-notes.md` for the full case-by-case breakdown, including two easy-to-miss
requirements found only by working through the corpus: matching `Term.ApplyInfix` in addition to
`Term.Apply(Term.Select(...))` (dot-free calls parse differently), and independently re-implementing
WartRemover's `@SuppressWarnings` respect (Scalafix has no built-in awareness of it).

## Example Files

- **`examples/TryFailed.scala`**, **`examples/FutureAndThen.scala`**,
  **`examples/Suppression.scala`** — the actual validated `SemanticRule` implementations, not
  illustrations. Run against the full 20-case test corpus via `references/scala-cli-workflow.md`;
  every case passes and the rewritten output compiles clean under the corpus's own
  `-Xfatal-warnings -Wvalue-discard -Xlint:_,-unused`. Copy these directly rather than
  reconstructing from the (superseded, bug-containing) snippets in `references/scaffold.md`.

## Reference Files

- **`references/target-apis.md`** — the exact `TryUtil.forFailed` / `Thereafter.syntax.thereafterP`
  signatures, the wart match logic they replace, and real call-site examples already found in this
  repo. Start here for the TryFailed/FutureAndThen rules specifically; re-derive the equivalent
  for any other wart from its `scala-2` source.
- **`references/scaffold.md`** — full `build.sbt`/`plugins.sbt`/module skeleton for the isolated
  rule project, plus worked (untested-against-real-sbt) example rules for both `TryFailed` and
  `FutureAndThen` showing the `SymbolMatcher` + `Patch` shape described above.
- **`references/corpus-notes.md`** — case-by-case analysis of the 20-case test corpus for these two
  rules, including the three DECIDE resolutions (for-comprehension desugaring, value-discard safety,
  named-`PartialFunction`-value rewriting) with justification, **plus four real implementation bugs
  found and fixed by actually running the rule against the corpus** (tree `eq` vs `==`, a
  scalameta wrapper node between `Enumerator.Generator` and `Term.For`, nested-patch corruption,
  and an already-total-case compile error). Both rules now pass all 20 cases and the rewritten
  file compiles clean under the corpus's own strict flags — read this before writing the fixture
  tests for `TryFailed`/`FutureAndThen` specifically.
- **`references/scala-cli-workflow.md`** — the validated, scala-cli-native dev loop used to build
  and test the above (no sbt), including the exact commands, the `META-INF/services` rule
  registration trick, and the debug-symbol-printing technique from step 3 in concrete code. Prefer
  this over `scaffold.md`'s sbt setup whenever the target is a standalone scala-cli corpus file
  rather than the in-tree monorepo.
