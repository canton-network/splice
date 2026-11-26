//> using scala 2.13.16
//> using dep ch.epfl.scala::scalafix-core:0.14.9

package fix

import scalafix.v1._
import scala.meta._

class TryFailed extends SemanticRule("TryFailed") {
  private val failedMatcher = SymbolMatcher.exact("scala/util/Try#failed().")
  private val wartName = "com.digitalasset.canton.TryFailed"

  override def fix(implicit doc: SemanticDocument): Patch = {
    doc.tree.collect {
      case sel @ Term.Select(recv, failedMatcher(_)) if !Suppression.isSuppressed(sel, wartName) =>
        handle(sel, recv)
    }.asPatch
  }

  private def handle(sel: Term.Select, recv: Term): Patch = {
    sel.parent match {
      // dot form: recv.failed.foreach(f)  ->  Term.Select(sel, "foreach") wrapped in Term.Apply
      case Some(foreachSel @ Term.Select(_, Term.Name("foreach"))) =>
        foreachSel.parent match {
          case Some(app @ Term.Apply(_, List(f))) =>
            rewrite(app, recv, f)
          case _ => lint(sel)
        }

      // infix form: recv.failed foreach { ... }
      case Some(app @ Term.ApplyInfix(lhs, Term.Name("foreach"), _, List(f))) if lhs == sel =>
        rewrite(app, recv, f)

      // for-comprehension: for (v <- recv.failed) body  (single generator, irrefutable var, no guard)
      // Scalameta wraps the enumerator list in a Term.EnumeratorsBlock between the
      // Enumerator.Generator and the enclosing Term.For.
      case Some(gen @ Enumerator.Generator(v: Pat.Var, rhs)) if rhs == sel =>
        gen.parent match {
          case Some(block @ Term.EnumeratorsBlock(List(_))) =>
            block.parent match {
              case Some(forNode @ Term.For(_, body)) =>
                Patch.replaceTree(
                  forNode,
                  s"${recv.syntax}.forFailed { ${v.syntax} => ${body.syntax} }",
                ) + addImport
              case _ => lint(sel)
            }
          case _ => lint(sel)
        }

      case _ => lint(sel)
    }
  }

  private def rewrite(node: Tree, recv: Term, f: Term): Patch =
    Patch.replaceTree(node, s"${recv.syntax}.forFailed(${f.syntax})") + addImport

  private def addImport: Patch =
    Patch.addGlobalImport(importer"com.digitalasset.canton.util.TryUtil._")

  private def lint(sel: Term.Select): Patch =
    Patch.lint(
      Diagnostic(
        "TryFailed",
        "Try#failed used outside the .failed.foreach(...) shape this rule auto-fixes. " +
          "Use TryUtil.forFailed manually, or restructure to match on the Try directly.",
        sel.pos,
      )
    )
}
