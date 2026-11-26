//> using scala 2.13.16
//> using dep ch.epfl.scala::scalafix-core:0.14.9

package fix

import scalafix.v1._
import scala.meta._

class FutureAndThen extends SemanticRule("FutureAndThen") {
  private val andThenMatcher = SymbolMatcher.exact("scala/concurrent/Future#andThen().")
  private val wartName = "com.digitalasset.canton.FutureAndThen"

  override def fix(implicit doc: SemanticDocument): Patch = {
    doc.tree.collect {
      case app @ Term.Apply(Term.Select(_, name @ andThenMatcher(_)), List(arg))
          if !Suppression.isSuppressed(app, wartName) =>
        handle(app, name, arg)
    }.asPatch
  }

  // Only the `andThen` name token and the argument are replaced — never the receiver, and never
  // the whole Term.Apply reconstructed from `.syntax`. Chained calls (F3: `f.andThen{...}.andThen{...}`)
  // nest one Term.Apply inside another's receiver; replacing a whole subtree that embeds another
  // node's original .syntax while that node is *also* being independently replaced corrupts the
  // output (confirmed empirically: an earlier whole-Apply-replacement version produced garbled,
  // duplicated text on F3). Keeping every patch's range disjoint from every other match's range
  // avoids this regardless of nesting depth.
  private def handle(app: Term.Apply, name: Term.Name, arg: Term): Patch =
    if (hasExtraArgList(app)) {
      lint(app, "Future#andThen with an explicit trailing argument list (e.g. an explicit " +
        "ExecutionContext) is not auto-rewritten; verify Thereafter.syntax.thereafter accepts " +
        "the same call shape, then rewrite by hand.")
    } else
      arg match {
        case pf: Term.PartialFunction =>
          val rewrittenCases = pf.cases.map(wrapCaseBody).mkString("\n")
          // A case list already ending in an unguarded wildcard/variable pattern is already
          // total — appending another `case _ => ()` after it is not just redundant, it's a
          // hard compile error ("patterns after a variable pattern cannot match", SLS 8.1.1),
          // confirmed empirically against F5 (`case _ => counter.incrementAndGet()`).
          val alreadyTotal = pf.cases.lastOption.exists { c =>
            c.cond.isEmpty && (c.pat.is[Pat.Wildcard] || c.pat.is[Pat.Var])
          }
          val newBody =
            if (alreadyTotal) s"{\n$rewrittenCases\n}"
            else s"{\n$rewrittenCases\ncase _ => ()\n}"
          Patch.replaceTree(name, "thereafter") +
            Patch.replaceTree(pf, newBody) +
            Patch.addGlobalImport(importer"com.digitalasset.canton.util.Thereafter.syntax._")
        case _ =>
          lint(
            app,
            "Future#andThen called with a non-literal PartialFunction value. Rewriting to " +
              "thereafter requires the callback to be total; since PartialFunction <: Function1, " +
              "a blind rename would compile but risks a runtime MatchError on inputs the partial " +
              "function doesn't handle. Verify the value is exhaustive (or make it so) before " +
              "switching to thereafter by hand.",
          )
      }

  // Detects `f.andThen(pf)(ec)` — the matched Apply is itself the `fun` of another Apply.
  private def hasExtraArgList(app: Term.Apply): Boolean =
    app.parent.exists {
      case outer: Term.Apply => outer.fun == app
      case _ => false
    }

  // thereafter's body must be total (Try[A] => Unit); andThen's need not be. Wrapping every
  // case body as `{ <body>; () }` sidesteps -Wvalue-discard regardless of the body's static
  // type (only a block's *final* expression is discard-checked, and that's now the literal
  // `()`), without needing to type-check the original body.
  private def wrapCaseBody(c: Case): String = {
    val guard = c.cond.map(cnd => s" if ${cnd.syntax}").getOrElse("")
    s"case ${c.pat.syntax}$guard => { ${c.body.syntax}; () }"
  }

  private def lint(app: Term.Apply, message: String): Patch =
    Patch.lint(Diagnostic("FutureAndThen", message, app.pos))
}
