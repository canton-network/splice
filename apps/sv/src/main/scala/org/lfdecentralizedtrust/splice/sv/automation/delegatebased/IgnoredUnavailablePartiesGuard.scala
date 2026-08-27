// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import org.lfdecentralizedtrust.splice.automation.{TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.store.IgnoredPartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.util.UnresponsiveParties
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import scala.concurrent.{ExecutionContext, Future}

trait IgnoredUnavailablePartiesGuard extends NamedLogging {
  protected def svConfig: SvAppBackendConfig
  protected def ignoredPartiesStore: IgnoredPartiesStore
  protected def svTaskContext: SvTaskBasedTrigger.Context

  protected def completeUnlessAmuletVersionIgnored(
      vettedVersion: String,
      stakeholders: Set[PartyId],
      ignoreUnresponsiveParties: Boolean,
  )(task: => Future[TaskOutcome])(implicit ec: ExecutionContext): Future[TaskOutcome] =
    if (
      svConfig.allIgnoredAmuletVersions.contains(vettedVersion) &&
      svConfig.parameters.enabledFeatures.ignorePartyIdWithIgnoredAmulet
    ) {
      val toIgnore = withoutDsoParty(stakeholders)
      ignoredPartiesStore.addAll(toIgnore)
      Future.successful(
        TaskSuccess(
          s"Skipped batch with ignored version $vettedVersion: added ${toIgnore.size} parties to ignore list: $toIgnore"
        )
      )
    } else {
      task.recoverWith(recoverUnresponsiveParties(ignoreUnresponsiveParties))
    }

  protected def completeWithVettedAmuletVersion(
      stakeholders: Set[PartyId],
      contractIds: Seq[String],
      ignoreUnresponsiveParties: Boolean = true,
  )(task: => Future[TaskOutcome])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TaskOutcome] =
    svTaskContext.vettingLookupService
      .lookupVettingState(stakeholders.toSeq, PackageIdResolver.Package.SpliceAmulet)
      .flatMap {
        case Some(vettedVersion) =>
          completeUnlessAmuletVersionIgnored(
            vettedVersion.toString,
            stakeholders,
            ignoreUnresponsiveParties,
          )(task)
        case None =>
          Future.successful(
            TaskSuccess(ignorePartiesWithoutVettedAmulet(stakeholders, contractIds))
          )
      }

  protected def ignorePartiesWithoutVettedAmulet(
      informees: Set[PartyId],
      contractIds: Seq[String],
      logAsWarning: Boolean = false,
  )(implicit tc: TraceContext): String = {
    val toIgnore = withoutDsoParty(informees)
    ignoredPartiesStore.addAll(toIgnore)
    val msg =
      s"No vetted Amulet version for $contractIds; ignoring ${toIgnore.size} parties: $toIgnore"
    if (logAsWarning) logger.warn(msg)
    msg
  }

  private def recoverUnresponsiveParties(
      enabled: Boolean
  ): PartialFunction[Throwable, Future[TaskOutcome]] = {
    case ex: StatusRuntimeException
        if enabled && svConfig.parameters.enabledFeatures.naiveUnresponsivePartiesAutoIgnore =>
      val toIgnore = withoutDsoParty(extractUnresponsiveParties(ex))
      if (toIgnore.isEmpty) {
        Future.failed(ex)
      } else {
        ignoredPartiesStore.addAll(toIgnore)
        Future.successful(
          TaskSuccess(
            s"Batch failed due to unresponsive parties, added ${toIgnore.size} to ignore list: $toIgnore"
          )
        )
      }
  }

  // never ignore the DSO party itself: it is a stakeholder on every DSO contract
  private def withoutDsoParty(parties: Set[PartyId]): Set[PartyId] =
    parties - svTaskContext.dsoStore.key.dsoParty

  private def extractUnresponsiveParties(ex: StatusRuntimeException): Set[PartyId] = {
    val statusProto = StatusProto.fromThrowable(ex)
    val errorDetails = ErrorDetails.from(statusProto)
    errorDetails
      .collectFirst { case UnresponsiveParties(parties) => parties }
      .getOrElse(Set.empty)
  }

}
