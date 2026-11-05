// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  FeaturedAppRight,
  FeaturedAppRight_UpdateConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_CreateFeaturedAppConfigUpdateInstruction,
  SRARC_GrantFeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

// Validates FeaturedAppRight grant/amend actions before they become vote-requests: it is the
// authoritative gate for the opsParties rules (no duplicates, provider is not its own opsParty,
// valid party ids) and additionally checks cross-right overlap against the ACS.
// The UI pre-checks the first three for fast feedback, but only the backend can do the ACS overlap check.
// The UI also surfaces an advisory warning when opsParties overlap an in-flight vote-request; this validator
// deliberately does not re-check in-flight requests (that query would run on every proposal), so the
// authoritative check here is ACS-only and the cross-request race is left as the TOCTOU limitation noted below.
object FeaturedAppRightValidator {

  def validateFeaturedAppRightAction(
      action: ActionRequiringConfirmation,
      store: SvDsoStore,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Either[String, Unit]] = {
    action match {
      case arc: ARC_DsoRules =>
        arc.dsoAction match {
          case g: SRARC_GrantFeaturedAppRight =>
            val value = g.dsoRules_GrantFeaturedAppRightValue
            val providerPartyId = PartyId.tryFromProtoPrimitive(value.provider)
            val proposed =
              SvUtil.optionalJavaListToSeq(value.opsParties).map(PartyId.tryFromProtoPrimitive)
            checkOpsParties(store, providerPartyId, proposed, None)

          case u: SRARC_CreateFeaturedAppConfigUpdateInstruction =>
            val value = u.dsoRules_CreateFeaturedAppConfigUpdateInstructionValue
            // `updates` is a list — one entry per provider this instruction amends, each with its own opsParties.
            // We check every entry and stop at the first conflict, so a bad opsParty can't slip through by being
            // placed in a later entry instead of the first.
            // Still open: two entries in the same instruction both adding the same party both pass here (neither is
            // on-ledger yet), the same kind of race as the TOCTOU note on checkOpsParties below.
            val allUpdates: Seq[(String, FeaturedAppRight_UpdateConfig)] =
              value.updates.asScala.toSeq.map(t => (t._1, t._2))
            allUpdates.foldLeft(Future.successful(Right(()): Either[String, Unit])) {
              (accF, entry) =>
                accF.flatMap {
                  case Left(err) => Future.successful(Left(err))
                  case Right(()) => validateAmend(store, entry._1, entry._2)
                }
            }
          case _ => Future.successful(Right(()))
        }
      case _ => Future.successful(Right(()))
    }
  }

  private def validateAmend(
      store: SvDsoStore,
      providerRaw: String,
      update: FeaturedAppRight_UpdateConfig,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Either[String, Unit]] = {
    val providerPartyId = PartyId.tryFromProtoPrimitive(providerRaw)
    store.lookupFeaturedAppRight(providerPartyId).flatMap {
      case None =>
        Future.successful(Left(s"no FeaturedAppRight found for provider: $providerRaw"))
      case Some(current) =>
        val baseParties = SvUtil
          .optionalJavaListToSeq(update.baseConfig.opsParties)
          .map(PartyId.tryFromProtoPrimitive)
        val proposedParties = SvUtil
          .optionalJavaListToSeq(update.newConfig.opsParties)
          .map(PartyId.tryFromProtoPrimitive)
        val currentParties = SvUtil
          .optionalJavaListToSeq(current.payload.opsParties)
          .map(PartyId.tryFromProtoPrimitive)
        val effective = resolveOps(baseParties, currentParties, proposedParties)
        checkOpsParties(store, providerPartyId, effective, Some(current.contractId))
    }
  }

  // If multiple vote-requests are created by different SVs, due to ACS snapshot race conditions
  // we could in principle still end up in a situation where a single party is specified in multiple rights.
  // Time of Check, Time of Use (TOCTOU) problem
  def checkOpsParties(
      store: SvDsoStore,
      provider: PartyId,
      proposedOpsParties: Seq[PartyId],
      // the currentContractCid is excluded client-side below
      currentContractCid: Option[FeaturedAppRight.ContractId],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Either[String, Unit]] =
    if (proposedOpsParties.distinct.sizeIs != proposedOpsParties.size)
      Future.successful(
        Left(s"duplicate exist in opsParties, in the config update request for provider: $provider")
      )
    else if (proposedOpsParties.contains(provider))
      Future.successful(
        Left(
          s"provider specified in opsParties, in the config update request for provider: $provider"
        )
      )
    else {
      val appParties = (provider +: proposedOpsParties).distinct
      val querySet = appParties.toSet
      store.listFeaturedAppRightsForParties(appParties).map { rights =>
        // The DB returns every right overlapping the query set; drop the right being amended, then
        // map each remaining overlap back to the proposed party and the right it collides with.
        val conflicts = rights
          .filterNot(r => currentContractCid.contains(r.contractId))
          .flatMap { r =>
            val rightParties =
              PartyId.tryFromProtoPrimitive(r.payload.provider) +:
                SvUtil
                  .optionalJavaListToSeq(r.payload.opsParties)
                  .map(PartyId.tryFromProtoPrimitive)
            rightParties.filter(querySet.contains).map(p => (p, r.contractId))
          }
        conflicts match {
          case Nil => Right(())
          case cs =>
            val rendered = cs
              .map { case (party, cid) => s"$party (on right ${cid.contractId})" }
              .mkString(", ")
            Left(s"parties already in use: $rendered")
        }
      }
    }

  // Decides the opsParties an amend ends up with: if the proposed list matches the base the amend was built from,
  // the amend didn't touch opsParties, so keep the ones currently on the right; otherwise take the proposed list.
  // (This mirrors DAML's patch behavior.)
  //
  // Known limitation: we treat "no opsParties" (None) and "empty list" ([]) as the same, but DAML treats them as different.
  // This only matters for an amend that turns opsParties from None to empty list, which is not expected in a real world config update request.
  // We never miss a real conflict, the error only ever goes the safe way, so we accept this rare case rather than add code to tell None and [] apart.
  def resolveOps(
      baseParties: Seq[PartyId],
      currentParties: Seq[PartyId],
      proposedParties: Seq[PartyId],
  ): Seq[PartyId] =
    if (baseParties == proposedParties) {
      currentParties
    } else {
      proposedParties
    }
}
