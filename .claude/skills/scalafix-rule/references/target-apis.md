# Target APIs: TryFailed and FutureAndThen

Researched directly from source in this repo on 2026-09-30. Re-grep the file paths below before
relying on exact signatures — this is a snapshot, not a guarantee they haven't changed.

## TryFailed

**Wart source (the one that actually runs — Canton compiles with Scala 2.13.16):**
`canton/community/lib/wartremover/src/main/scala-2/com/digitalasset/canton/TryFailed.scala`

Match logic: a `Select(receiver, "failed")` where `receiver.tpe.typeSymbol` is `scala.util.Try` or
`scala.util.Success`. In SemanticDB terms, both receivers resolve the `.failed` call to the same
method symbol (`Success` doesn't override `failed`, it inherits `Try`'s), so a Scalafix rule only
needs to match the single symbol `scala.util.Try#failed()` — no separate case for `Success` is
needed, unlike the wart's two `case` arms (the wart uses Scala 3 quote patterns that don't get this
inheritance shortcut for free; the scala-2 wart already merges the two checks via `||` on
`typeSymbol`).

Wart message: `"(Try|Success).failed should not be used. Use e.g. .forFailed from TryUtil instead."`

**Target API:** `canton/community/base/src/main/scala/com/digitalasset/canton/util/TryUtil.scala`

```scala
object TryUtil {
  implicit final class ForFailedOps[A](private val a: Try[A]) extends AnyVal {
    @inline
    def forFailed(f: Throwable => Unit): Unit = a.fold(f, _ => ())
    ...
  }
}
```

`forFailed` is an implicit value-class extension method on `Try[A]`, not a static function. The
wart's own advice ("If you need failed.foreach, use TryUtil.forFailed instead") is specifically
about the `t.failed.foreach(f)` idiom, so the rewrite scope is that compound call, not every bare
`.failed`:

```
t.failed.foreach(f)   →   t.forFailed(f)
```

plus `import com.digitalasset.canton.util.TryUtil._` (or `.*` if the file already targets Scala 3
syntax — check the target file's existing import style; this repo's app code is Scala 2.13, so
`_` is correct there).

No real `t.failed.foreach(...)` call sites currently exist in `canton/community` (grepped
2026-09-30) — the wart has successfully prevented any from landing. Build the test fixture
synthetically from the shape above.

## FutureAndThen

**Wart source:**
`canton/community/lib/wartremover/src/main/scala-2/com/digitalasset/canton/FutureAndThen.scala`

Match logic: `Select(receiver, "andThen")` where `receiver.tpe.typeSymbol` is
`scala.concurrent.Future`. This type-symbol check is exactly why a Scalafix rule must bind to the
resolved method symbol rather than the bare name `andThen` — this repo has many *other* uses of
`.andThen` that must NOT be rewritten, all found by grepping `\.andThen\s*[{(]` under
`canton/community`:

- `PartialFunction#andThen` (e.g. `handleError.andThen(_.left.map(_._2))` in
  `ledger-json-api/.../Endpoints.scala`)
- Monocle `Lens#andThen` (e.g. `GenLens[P2PNetworkConfig](_.peerEndpoints)).andThen(...)` in
  `UseBftSequencer.scala`, `UseToxiproxy.scala`)
- `GetResult#andThen` (e.g. `Tags.scala`, `Offset.scala`, `ConfiguredPhysicalSynchronizerId.scala`)

None of these are `scala.concurrent.Future#andThen` — they resolve to different symbols entirely
(different owner classes), so a symbol-exact `SymbolMatcher` naturally excludes all of them. A
name-only matcher would incorrectly flag every one. Use at least one of these real look-alikes as
the "negative" fixture case described in SKILL.md step 4.

Wart message: `"Future.andThen swallows exceptions. Use e.g. the typeclass Thereafter with .thereafter or .thereafterP for partial functions."`

**Target API:**
`canton/community/base/src/main/scala/com/digitalasset/canton/util/Thereafter.scala`

```scala
import com.digitalasset.canton.util.Thereafter.syntax._

future.thereafter(result => ...)   // body: Try[A] => Unit, must be total
```

**Correction (2026-09-30, verified against the actual test corpus's pinned dependency — see
below):** an earlier version of this note claimed `thereafterP` (a `PartialFunction`-accepting
variant that would make this rewrite a plain method rename) is present in this in-tree
`Thereafter.scala` and should be preferred. That's true of the in-tree source
(`Thereafter.scala:126-127`) — but it is **not what the rule is actually graded/tested against**.
The test corpus pins
`com.daml:canton-community-common_2.13:3.0.0-snapshot.20240130.12664.0.va195d0f6` from Maven
Central, and `javap` against the resolved `canton-community-base` jar from that snapshot confirms
`Thereafter`/`Thereafter$Ops` expose only `thereafter` and `thereafterF` — no `thereafterP`:

```
public interface com.digitalasset.canton.util.Thereafter<F> {
  ... thereafter(F, Function1<Object, BoxedUnit>, ExecutionContext);
  ... thereafterF(F, Function1<Object, Future<BoxedUnit>>, ExecutionContext);
}
```

So **target `thereafter` with a manually appended `case _ => ()` fallback**, not `thereafterP` —
the original pasted design brief was right about this. `thereafter`'s body must be a *total*
`Try[A] => Unit`, but the `andThen` partial function need not be exhaustive:

```
f.andThen { case Failure(e) => ... }
  →
f.thereafter { case Failure(e) => ...; case _ => () }
```

plus `import com.digitalasset.canton.util.Thereafter.syntax._`. Without the added `case _ => ()`,
the rewritten code fails to compile under `-Xfatal-warnings` with "match may not be exhaustive"
(`Try` has exactly two cases, `Success`/`Failure`, so a single wildcard fallback always suffices).

If this rule is ever pointed at the in-tree monorepo directly (via `sbt scalafixAll` on
`canton/community` itself, compiling against the in-tree `Thereafter.scala` rather than the
published snapshot), `thereafterP` *would* be available there and is the cleaner rewrite in that
context — but don't design the rule around an API that doesn't exist on the classpath it's
actually tested against. If both targets matter, that's a real design fork worth surfacing to
whoever owns the corpus rather than silently picking one.

Real call sites already in the codebase (the in-tree monorepo, not the corpus) use the
single-argument-lambda `thereafter` form, e.g. `.thereafter(addResult(operatorId, _))`
(`BftSender.scala:161`), `.thereafter(_ => setPreviousPolicy())`
(`ProgrammableSequencer.scala:148`) — useful only as a sanity check for idiomatic
`Thereafter.syntax` usage, not as a direct model for the `andThen` rewrite (those aren't rewrites
of `andThen`).
