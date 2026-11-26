# Test Corpus Analysis (TryFailed + FutureAndThen)

The corpus is a scala-cli file (`Corpus.scala`) pinning
`com.daml:canton-community-common_2.13:3.0.0-snapshot.20240130.12664.0.va195d0f6` from Maven
Central, compiled with `-Xfatal-warnings -Wvalue-discard -Xlint:_,-unused`. It compiles as-is
(verified: `scala-cli compile Corpus.scala` succeeds), and any REWRITE output must compile under
the same flags. Each case is tagged REWRITE / LINT / UNCHANGED / DECIDE. This file records the
required behavior per case and the cross-cutting rule requirements the corpus exposes that
aren't obvious from the wart source alone.

## Cross-cutting requirements discovered from the corpus

These apply across both rules, not just one case each — get these into the rule design before
writing fixtures, not after:

1. **Infix calls are a different AST shape (T3).** `t.failed foreach { e => log(e) }` (no dot)
   parses as `Term.ApplyInfix`, not `Term.Apply(Term.Select(...))`. A matcher that only handles
   the dotted form silently misses this. Match both shapes, or normalize by checking for the
   `.failed` selection first and inspecting its parent rather than starting from the outer call
   shape.
2. **`@SuppressWarnings` must be honored (F10).** WartRemover's own traversal skips any tree where
   `hasWartAnnotation` is true. Scalafix has no built-in awareness of this — it's a WartRemover
   convention, not a scalafix one. The rule must independently walk up from a match to the
   enclosing `Defn` and check for `@SuppressWarnings(Array(...))` containing the wart's name
   (matching what's actually written in the corpus: `"com.digitalasset.canton.FutureAndThen"`,
   fully qualified) before patching. Skipping this means the rule "fixes" code the author
   deliberately opted out of — which may be relying on the exact swallowing behavior being
   removed.
3. **Extra curried argument lists change safety (F7).** `f.andThen { ... }(ec)` (explicit
   `ExecutionContext`) is a `Term.Apply` wrapping another `Term.Apply` — the inner node still
   structurally matches an `andThen` call. Naively rewriting just the inner node and leaving the
   outer `(ec)` application in place is untested territory (does the replacement API even take a
   second explicit list the same way?). Check `andThenApply.parent` for another enclosing
   `Term.Apply` whose `fun` is the matched node; if present, don't rewrite — lint instead.
4. **Value-discard safety is a real, not hypothetical, hazard (F5).** `PartialFunction[Try[T], U]`
   passed to `andThen` has a freely-inferred `U` — no discard warning, because nothing forces `U`
   to be `Unit`. The moment the same case block is used as `Try[A] => Unit` (for `thereafter`), the
   compiler adapts the body's value to `Unit`, and if that body's static type isn't already `Unit`,
   `-Wvalue-discard` fires and `-Xfatal-warnings` turns it into a hard error. There is no
   syntax-only way to know a case body's static type. **Decision:** don't try to detect it —
   uniformly rewrite every case's body as `{ <original body>; () }` (a trailing `Lit.Unit()`
   inside a block), for every case in every rewritten partial function, including ones that don't
   need it (e.g. `println(v)` in F1). A mid-block statement's type is irrelevant to
   `-Wvalue-discard` (only the block's *final* expression is adapted-and-possibly-discarded, and
   that final expression is now the literal `()`), so this is universally safe regardless of the
   original body's type, at the cost of always emitting one extra `; ()` even when unnecessary.
   Simpler and more robust than trying to type-check the body.

## TryFailed cases

| Case | Tag | Behavior |
|---|---|---|
| T1 `t.failed.foreach(e => log(e))` | REWRITE | → `t.forFailed(e => log(e))` |
| T2 `t.failed.foreach(log)` | REWRITE | → `t.forFailed(log)` (method value, no eta-expansion rewrite needed — `.syntax` handles it) |
| T3 `t.failed foreach { e => log(e) }` | REWRITE | Infix form — needs the `Term.ApplyInfix` shape too (see cross-cutting #1) → `t.forFailed { e => log(e) }` |
| T4 `Try(compute() / 0).failed.foreach(log)` | REWRITE | Receiver is an arbitrary expression, not an identifier — confirms using `recv.syntax`, not assuming a simple ref |
| T5 `for (e <- t.failed) log(e)` | DECIDE | See below |
| T6 `t.failed.map(_.getMessage).toOption` | LINT | `.failed` not immediately followed by `.foreach` — no drop-in `TryUtil` replacement exists for this shape. Emit `Patch.lint`, don't rewrite. |
| T7 `t.failed.toOption` | LINT | Same reasoning as T6 |
| T8 `Future.failed(new RuntimeException(...))` | UNCHANGED | `Future.failed` (companion object factory) — different symbol (`scala/concurrent/Future.failed().` on the object, not `scala/util/Try#failed().` on an instance). Exact-symbol matching excludes this for free. |
| T9 `f.failed.foreach(log)` where `f: Future[Int]` | UNCHANGED | `Future#failed` (instance projection) is a distinct symbol from `Try#failed`. Same free exclusion. |
| T10 `j.failed.foreach(log)` where `j: Job` (user class) | UNCHANGED | Unrelated method literally named `failed` — distinct symbol, excluded for free. This is the strongest argument for symbol-based matching over name-based: a name-only matcher would incorrectly rewrite T8/T9/T10. |

### T5 DECIDE: for-comprehension over `.failed`

`for (e <- t.failed) log(e)` desugars to `t.failed.foreach(e => log(e))` at compile time, but
**scalameta parses the pre-desugar syntax** — this is a `Term.For` node
(`Enumerator.Generator(pat, rhs)` + body), not a `Term.Apply(Term.Select(_, "foreach"))`. It will
never be caught by the foreach-shaped matcher above; it needs its own case.

The general shape is riskier than it looks: if the generator pattern is a *refining* pattern (e.g.
`for (e: SomeSubtype <- t.failed) ...`), the desugared `foreach` silently skips non-matching
values — converting that to an unconditional `forFailed(f)` call (which always invokes `f`) would
change behavior (invoke `f` on throwables that the original pattern would have filtered out).

**Decision:** REWRITE only the narrow, safe shape the corpus actually shows — a single generator
(no guards, no additional enumerators), with an **irrefutable** pattern (`Pat.Var`, a bare
identifier bind with no type refinement). For anything wider (guards, multiple enumerators, a
refining/typed pattern), fall back to LINT rather than guessing. Justification: the safe case is
mechanically identical to the already-handled `foreach` rewrite (same target API, same argument
shape after desugaring), so it costs nothing extra to support; the unsafe case has a real
behavior-changing failure mode with no cheap way to rule it out syntactically, so LINT (flag, let a
human confirm the pattern is irrefutable) is the right default there. This mirrors the LINT
decision for T6/T7 — "rewrite the safe fragment, lint everything else" rather than trying to
special-case every shape.

Implementation shape: match
`Term.For(List(Enumerator.Generator(v: Pat.Var, Term.Select(recv, failedMatcher(_)))), body)` (or
the infix equivalent if the generator RHS uses infix `.failed`), rewrite to
`Patch.replaceTree(forNode, s"${recv.syntax}.forFailed { ${v.syntax} => ${body.syntax} }")`, else
if the `.failed` receiver matches but the shape doesn't (guard present, non-`Pat.Var` pattern,
`Term.ForYield` with a `yield`), lint instead.

## FutureAndThen cases

| Case | Tag | Behavior |
|---|---|---|
| F1 `f.andThen { case Success(v) => ...; case Failure(e) => ... }` | REWRITE | → `f.thereafter { case Success(v) => { println(v); () }; case Failure(e) => { log(e); () }; case _ => () }` — see cross-cutting #4 for why every case body gets wrapped, even ones that don't strictly need it. Already-exhaustive PF still gets a trailing `case _ => ()`; verify this doesn't itself trigger an unreachable-pattern warning under `-Xlint:_` (check empirically against the corpus — not verified yet as of this note). |
| F2 `f.andThen { case Failure(e) => log(e) }` | REWRITE | → `f.thereafter { case Failure(e) => { log(e); () }; case _ => () }` |
| F3 chained: `f.andThen{...}.andThen{...}` | REWRITE | Both `andThen` calls must be independently matched and rewritten — `doc.tree.collect` naturally visits both `Term.Apply` nodes since one is nested inside the receiver of the other. No special handling needed beyond making sure the SymbolMatcher check is evaluated per-occurrence (it is, since it's driven by the resolved symbol at each call site, not global state). |
| F4 `Future(x * 2).andThen { case Success(v) => println(v) }` | REWRITE | Receiver is an expression, not an identifier — same `recv.syntax` requirement as T4 |
| F5 `f.andThen { case _ => counter.incrementAndGet() }` | DECIDE | Resolved by cross-cutting #4 (uniform `{ body; () }` wrapping) — this is the case that makes the hazard concrete: `incrementAndGet()` returns `Int`, and without the wrapping, `-Wvalue-discard` + `-Xfatal-warnings` turns the naive rewrite into a compile error. |
| F6 `f.andThen(onFailure)` where `onFailure: PartialFunction[Try[Int], Unit]` is a named val | DECIDE | See below |
| F7 `f.andThen { case Failure(e) => log(e) }(ec)` (explicit `ExecutionContext`) | LINT | Resolved by cross-cutting #3 — extra curried arg list, don't rewrite, flag instead |
| F8 `inc.andThen(_.toString)` (`Function1#andThen`) | UNCHANGED | Distinct symbol (`scala/Function1#andThen()`), excluded for free by exact-symbol matching |
| F9 `pf.andThen(_.toString)` (`PartialFunction#andThen`) | UNCHANGED | `PartialFunction` overrides `andThen` with a different symbol; also excluded for free |
| F10 `@SuppressWarnings(Array("com.digitalasset.canton.FutureAndThen")) def f10(...) = f.andThen {...}` | UNCHANGED | Resolved by cross-cutting #2 — must check for the suppression annotation on the enclosing `Defn` and skip |

### F6 DECIDE: named `PartialFunction` value, not a literal

The structural matcher (requiring the argument to literally be a `Term.PartialFunction` node) does
not match a bare `Term.Name` reference like `onFailure` — so this falls through to "no match" by
construction, which is a good default, but silently doing nothing gives no signal that this call
site was seen and skipped. The more important question: *would it even be correct to rewrite it if
we could?*

`PartialFunction[A, B] extends (A => B)` in the standard library — so `onFailure` already has type
`Try[Int] => Unit` by subtyping, meaning a blind `f.andThen(onFailure)` → `f.thereafter(onFailure)`
rename **would compile successfully**. That's exactly the trap: `thereafter`'s contract expects a
*total* function, but `onFailure` is only defined for `Failure` — calling it on a `Success` result
throws `scala.MatchError` at runtime. `andThen` was safe here specifically because it checks
`isDefinedAt` before invoking; `thereafter` has no such check. The corpus's framing ("its result
type decides whether it compiles") is pointing at exactly this: the rewrite type-checks fine, so a
naive rule that only checks "does it compile" would ship a runtime regression.

**Decision:** treat this as LINT, not UNCHANGED-silently and not REWRITE. Detect the shape (the
`andThen` receiver matches, but the argument isn't a literal `Term.PartialFunction`) and emit a
diagnostic explaining that automatic rewriting isn't attempted because the argument's exhaustiveness
can't be verified syntactically, so a human must confirm `onFailure` is total (or wrap it with
`.orElse { case _ => () }` before their own rewrite) before switching to `thereafter`. This is the
same "flag, don't guess" principle as T6/T7 and F7, applied to a case where blind mechanical
rewriting would silently compile but be wrong.

## Status: validated end-to-end (2026-09-30)

Both rules were implemented, iterated on, and run against the actual corpus via `scala-cli fix`
(see `references/scala-cli-workflow.md` for the tool loop). **All 20 cases pass exactly as
specified**, and the fully-rewritten file compiles clean under
`-Xfatal-warnings -Wvalue-discard -Xlint:_,-unused`. Four real bugs surfaced only by actually
running the rule against the corpus — not visible from reading the wart source or reasoning about
scalameta APIs in the abstract:

1. **Reference equality (`eq`) between tree nodes is unreliable — use `==`.** A first draft of the
   `TryFailed` for-comprehension case guarded on `rhs eq sel` (comparing a `Term.Select` reached via
   `Enumerator.Generator`'s field against the same node reached via `doc.tree.collect`). This
   silently failed to match even though both were "the same" node, because scalameta doesn't
   guarantee `eq`-identity between two accesses to what is structurally the same subtree. Case
   classes give free structural equality — use `==`, never `eq`, when comparing scalameta trees.
2. **`Enumerator.Generator`'s parent is not `Term.For` directly.** In scalameta 4.17.4 (what
   scala-cli's bundled scalafix resolves), the enumerator list is wrapped in an intermediate
   `Term.EnumeratorsBlock` node: `Term.For(Term.EnumeratorsBlock(List(Enumerator.Generator(...))), body)`.
   A pattern expecting `Enumerator.Generator(...).parent` to be `Term.For` directly will never
   match. Found by printing `t.parent.map(_.getClass.getName)` — when a structural match silently
   fails, dump the actual runtime class of the parent rather than guessing from the grammar.
3. **Nested/chained matches corrupt output if you replace whole subtrees via reconstructed
   `.syntax` strings.** The first `FutureAndThen` draft replaced the entire `Term.Apply` with
   `s"${recv.syntax}.thereafter {...}"`. For F3 (`f.andThen{...}.andThen{...}`), the outer call's
   `recv` *is* the inner call's `Term.Apply` — replacing both nodes independently, where the outer
   replacement text embeds the inner node's *original* `.syntax`, produces garbled/duplicated
   output (confirmed: the actual corrupted output was
   `f.andThen { case Success(v) => println(v) }.thereafter {...}f.thereafter {...}`). **Fix:**
   replace only the minimal, disjoint spans — the `andThen` name token (→ `"thereafter"`) and the
   partial-function argument (→ rewritten cases) — never the receiver, never the whole `Apply`.
   Disjoint per-match patches compose correctly regardless of nesting depth; whole-subtree
   reconstruction does not. Prefer this pattern generally, not just for this rule.
4. **"Always append a fallback case" breaks when the last case is already a catch-all.** F5's
   PF already ends in `case _ => counter.incrementAndGet()` — appending another `case _ => ()`
   after an unguarded wildcard is a **hard compile error** ("patterns after a variable pattern
   cannot match", SLS 8.1.1), not a warning. Fix: check whether the last case is already an
   unguarded `Pat.Wildcard`/`Pat.Var` before appending the synthetic fallback; skip it if so.

The working implementations are in this skill's `examples/` directory (`TryFailed.scala`,
`FutureAndThen.scala`, `Suppression.scala`) and supersede the illustrative snippets in
`scaffold.md`, which were written before any of this was run and got several of the above wrong.
Prefer the validated versions.

## Net effect on rule structure

Both rules end up with the same three-way branch, not just a match/no-match binary:

1. **REWRITE** — the narrow, verified-safe shape (literal argument, direct call, no suppression,
   no extra curried args).
2. **LINT** — the symbol matches (this *is* a `Try#failed` or `Future#andThen` call) but the shape
   isn't the safe one — emit `Patch.lint(Diagnostic(...))` so the wart's signal isn't silently
   lost.
3. **No patch at all** — the symbol doesn't match (different receiver type entirely) or the call is
   inside a `@SuppressWarnings`-annotated scope for this wart.

Confirm exact behavior of each case by running the actual rule against `Corpus.scala` — don't stop
at "the fixture I hand-wrote passes"; the corpus's UNCHANGED/LINT cases are also assertions
(nothing should be rewritten, and a lint rule run should still report a diagnostic where expected).
