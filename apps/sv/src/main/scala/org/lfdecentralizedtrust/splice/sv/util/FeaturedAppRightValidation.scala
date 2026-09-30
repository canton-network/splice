package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.tracing.TraceContext

import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_GrantFeaturedAppRight,
  SRARC_UpdateFeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}

object FeaturedAppRightValidation {
  private type FeaturedAppRightValidator =
    Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]] => Either[String, Unit]

  private def opsOf(
      c: Contract[FeaturedAppRight.ContractId, FeaturedAppRight]
  ): Set[String] =
    c.payload.opsParties.toScala.map(_.asScala.toSet).getOrElse(Set.empty)

  def validateGrant(
      provider: String,
      opsParties: Option[Seq[String]],
      featuredAppRights: Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]],
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
      featuredAppRights: Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]],
  ): Either[String, Unit] = {
    val proposed = newOpsParties.getOrElse(Seq.empty).toSet
    val others = featuredAppRights.filterNot(_.contractId == id)
    val existingOps = others.flatMap(opsOf).toSet
    val overlap = proposed intersect existingOps
    if (overlap.nonEmpty) Left(s"opsParties already used: ${overlap.mkString(", ")}")
    else Right(())
  }

  private def runValidator(
      store: SvDsoStore,
      validate: FeaturedAppRightValidator,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Either[String, Unit]] = {
    def loop(after: Option[Long]): Future[Either[String, Unit]] =
      store.paginateFeaturedAppRights(after).flatMap { page =>
        validate(page.resultsInPage) match {
          case Left(err) => Future.successful(Left(err))
          case Right(()) =>
            page.nextPageToken match {
              case Some(token) => loop(Some(token))
              case None => Future.successful(Right(()))
            }
        }
      }
    loop(None)
  }

  def validateFeaturedAppRightAction(
      action: ActionRequiringConfirmation,
      store: SvDsoStore,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Either[String, Unit]] = {
    action match {
      case arc: ARC_DsoRules =>
        arc.dsoAction match {
          case g: SRARC_GrantFeaturedAppRight =>
            val provider = g.dsoRules_GrantFeaturedAppRightValue.provider
            val opsParties =
              g.dsoRules_GrantFeaturedAppRightValue.opsParties.toScala.map(_.asScala.toSeq)
            runValidator(store, validateGrant(provider, opsParties, _))

          case u: SRARC_UpdateFeaturedAppRight =>
            val rightCid = u.dsoRules_UpdateFeaturedAppRightValue.rightCid
            val newOpsParties = u.dsoRules_UpdateFeaturedAppRightValue.update.newOpsParties.toScala
              .map(_.asScala.toSeq)
            runValidator(store, validateUpdate(rightCid, newOpsParties, _))

          case _ => Future.successful(Right(()))
        }
      case _ => Future.successful(Right(()))
    }
  }
}
