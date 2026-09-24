package org.lfdecentralizedtrust.splice.sv.util

import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.util.AssignedContract

object FeaturedAppRightValidation {
  private def opsOf(c: AssignedContract[FeaturedAppRight.ContractId, FeaturedAppRight]): Set[String] =
    c.payload.opsParties.toScala.map(_.asScala.toSet).getOrElse(Set.empty)

  def validateGrant(
   provider: String,
   opsParties: Option[Seq[String]],
   featuredAppRights: Seq[AssignedContract[FeaturedAppRight.ContractId, FeaturedAppRight]],
 ): Either[String, Unit] = {
    val proposed = opsParties.getOrElse(Seq.empty).toSet
    val existingOps = featuredAppRights.flatMap(opsOf).toSet
    val dupProvider = featuredAppRights.exists(_.payload.provider == provider)
    val overlap = proposed intersect existingOps
    if (dupProvider) Left(s"provider $provider already has a FeaturedAppRight")
    else if (overlap.nonEmpty) Left(s"opsParties already used: ${overlap.mkString(", ")}")
    else if (proposed(provider)) Left(s"provider cannot be its own opsParty")
    else Right(())
  }

  def validateUpdate(
      id: FeaturedAppRight.ContractId,
      newOpsParties: Option[Seq[String]],
      featuredAppRights: Seq[AssignedContract[FeaturedAppRight.ContractId, FeaturedAppRight]],
    ): Either[String, Unit] = {
    val proposed = newOpsParties.getOrElse(Seq.empty).toSet
    val others = featuredAppRights.filterNot(_.contractId == id)
    val existingOps = others.flatMap(opsOf).toSet
    val overlap = proposed intersect existingOps
    if (overlap.nonEmpty) Left(s"opsParties already used: ${overlap.mkString(", ")}")
    else Right(())
  }
}
