//> using scala 2.13.16
//> using dep ch.epfl.scala::scalafix-core:0.14.9

package fix

import scala.meta._

object Suppression {

  /** Mirrors WartRemover's own `hasWartAnnotation` check: skip any tree nested inside a
    * `@SuppressWarnings(Array(..., "<wartName>", ...))`-annotated definition. Scalafix has no
    * built-in awareness of this — it's a WartRemover convention, not a scalafix one.
    */
  def isSuppressed(t: Tree, wartName: String): Boolean =
    t.parent match {
      case None => false
      case Some(d: Defn.Def) if hasAnnot(d.mods, wartName) => true
      case Some(d: Defn.Val) if hasAnnot(d.mods, wartName) => true
      case Some(d: Defn.Var) if hasAnnot(d.mods, wartName) => true
      case Some(parent) => isSuppressed(parent, wartName)
    }

  private def hasAnnot(mods: List[Mod], wartName: String): Boolean =
    mods.exists {
      case Mod.Annot(Init(Type.Name("SuppressWarnings"), _, argss)) =>
        argss.flatten.exists(_.syntax.contains(wartName))
      case _ => false
    }
}
