// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.StatusRuntimeException
import org.lfdecentralizedtrust.splice.automation.{BatchSplitting, TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.util.UnresponsiveParties
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import scala.concurrent.{ExecutionContext, Future}

trait UnavailablePartiesGuard extends NamedLogging {
  protected def svConfig: SvAppBackendConfig
  protected def unavailablePartiesStore: UnavailablePartiesStore
  protected def svTaskContext: SvTaskBasedTrigger.Context

  private def protectedParties: Set[PartyId] =
    svConfig.protectedPartyIds + svTaskContext.dsoStore.key.dsoParty

  /** Submits the given batch, isolating vetting failures by recursively splitting the batch
    * and marking the parties on the lowest Amulet version of the offending contract
    * as unavailable.
    * If the feature is disabled, the batch is submitted as a whole.
    */
  protected def submitBatchWithSplitting[C](
      contracts: Seq[C],
      parties: C => Set[PartyId],
  )(submit: Seq[C] => Future[Unit])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TaskOutcome] =
    if (!svConfig.parameters.enabledFeatures.enableVettingFailureBatchSplitting) {
      submit(contracts).map(_ => TaskSuccess(s"submitted batch of ${contracts.size} contracts"))
    } else {
      new BatchSplitting(
        party =>
          traceContext =>
            svTaskContext.vettingLookupService.lookupVettingState(
              party,
              PackageIdResolver.Package.SpliceAmulet,
            )(
              traceContext
            ),
        unavailablePartiesStore,
        protectedParties,
        loggerFactory,
      )
        .processBatch(contracts, parties, submit)
        .map(result => TaskSuccess(result.summary))
    }

  protected def completeUnlessAmuletVersionIgnored(
      vettedVersion: String,
      stakeholders: Set[PartyId],
      ignoreUnresponsiveParties: Boolean,
  )(task: => Future[TaskOutcome])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TaskOutcome] =
    if (
      svConfig.allIgnoredAmuletVersions.contains(vettedVersion) &&
      svConfig.parameters.enabledFeatures.ignorePartyIdWithIgnoredAmulet
    ) {
      val toIgnore = withoutDsoParty(stakeholders)
      unavailablePartiesStore
        .addParties(toIgnore.toSeq)
        .map(_ =>
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
          ignorePartiesWithoutVettedAmulet(stakeholders, contractIds).map(TaskSuccess(_))
      }

  protected def ignorePartiesWithoutVettedAmulet(
      informees: Set[PartyId],
      contractIds: Seq[String],
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[String] = {
    val toIgnore = withoutDsoParty(informees)
    unavailablePartiesStore
      .addParties(toIgnore.toSeq)
      .map(_ =>
        s"No vetted Amulet version for $contractIds; ignoring ${toIgnore.size} parties: $toIgnore"
      )
  }

  private def recoverUnresponsiveParties(
      enabled: Boolean
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): PartialFunction[Throwable, Future[TaskOutcome]] = {
    case ex: StatusRuntimeException
        if enabled && svConfig.parameters.enabledFeatures.naiveUnresponsivePartiesAutoIgnore =>
      val toIgnore = withoutDsoParty(
        UnresponsiveParties.fromThrowable(ex).getOrElse(Set.empty)
      )
      if (toIgnore.isEmpty) {
        Future.failed(ex)
      } else {
        unavailablePartiesStore
          .addParties(toIgnore.toSeq)
          .map(_ =>
            TaskSuccess(
              s"Batch failed due to unresponsive parties, added ${toIgnore.size} to ignore list: $toIgnore"
            )
          )
      }
  }

  // never ignore the DSO party itself: it is a stakeholder on every DSO contract
  private def withoutDsoParty(parties: Set[PartyId]): Set[PartyId] =
    parties - svTaskContext.dsoStore.key.dsoParty

}
