// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.unavailableparties

import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.SvTaskBasedTrigger
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.StatusRuntimeException
import org.lfdecentralizedtrust.splice.automation.{
  BatchedMultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
}
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.util.{AssignedContract, UnresponsiveParties}
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import scala.concurrent.{ExecutionContext, Future}

trait UnavailablePartiesGuard extends NamedLogging {
  protected def svConfig: SvAppBackendConfig
  protected def unavailablePartiesStore: UnavailablePartiesStore
  protected def svTaskContext: SvTaskBasedTrigger.Context

  // the in-memory store has no backoff and holds the manually ignored parties, which must not be restored
  private def batchSplittingEnabled: Boolean = {
    val features = svConfig.parameters.enabledFeatures
    features.enableVettingFailureBatchSplitting && features.enablePersistedUnavailableParties
  }

  /** Completes the task like [[completeUnlessAmuletVersionIgnored]]. With
    * `enableVettingFailureBatchSplitting` and `enablePersistedUnavailableParties` set, a failing
    * task is instead narrowed to sub-batches of its contracts, isolating vetting failures and
    * marking the parties on the lowest Amulet version of the offending contract as unavailable.
    * In that case the parties are restored by the [[UnavailablePartiesDetector]] for the
    * sub-batches that succeeded only.
    */
  protected def completeWithBatchSplitting[Task, C](
      vettedVersion: String,
      task: Task,
      contracts: Seq[C],
      partiesOf: C => Set[PartyId],
      narrow: (Task, Seq[C]) => Task,
  )(complete: Task => Future[TaskOutcome])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TaskOutcome] = {
    val stakeholders = contracts.flatMap(partiesOf).toSet
    if (!batchSplittingEnabled)
      completeUnlessAmuletVersionIgnored(
        vettedVersion,
        stakeholders,
        ignoreUnresponsiveParties = true,
      )(complete(task))
    else if (amuletVersionIgnored(vettedVersion))
      ignoreAmuletVersion(vettedVersion, stakeholders)
    else
      new UnavailablePartiesDetector(
        party =>
          traceContext =>
            svTaskContext.vettingLookupService.lookupVettingState(
              party,
              PackageIdResolver.Package.SpliceAmulet,
            )(traceContext),
        unavailablePartiesStore,
        loggerFactory,
      )
        .processBatch[C](
          contracts,
          partiesOf,
          (subBatch: Seq[C]) => complete(narrow(task, subBatch)).map(_ => ()),
        )
        .map(result => TaskSuccess(result.summary))
  }

  protected def completeExpiryBatchWithSplitting[TCid, T](
      task: ScheduledTaskTrigger.ReadyTask[BatchedMultiDomainExpiredContractTrigger.Batch[TCid, T]],
      stakeholdersOf: T => Seq[PartyId],
  )(
      complete: ScheduledTaskTrigger.ReadyTask[
        BatchedMultiDomainExpiredContractTrigger.Batch[TCid, T]
      ] => Future[TaskOutcome]
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[TaskOutcome] =
    completeWithBatchSplitting(
      task.work.vettedVersion.toString,
      task,
      task.work.expiredContracts,
      (c: AssignedContract[TCid, T]) => stakeholdersOf(c.payload).toSet,
      (
          t: ScheduledTaskTrigger.ReadyTask[
            BatchedMultiDomainExpiredContractTrigger.Batch[TCid, T]
          ],
          subBatch: Seq[AssignedContract[TCid, T]],
      ) =>
        t.copy(work =
          t.work.copy(
            expiredContracts = subBatch,
            stakeholders = subBatch.flatMap(c => stakeholdersOf(c.payload)).toSet,
          )
        ),
    )(complete)

  private def amuletVersionIgnored(vettedVersion: String): Boolean =
    svConfig.allIgnoredAmuletVersions.contains(vettedVersion) &&
      svConfig.parameters.enabledFeatures.ignorePartyIdWithIgnoredAmulet

  private def ignoreAmuletVersion(vettedVersion: String, stakeholders: Set[PartyId])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TaskOutcome] =
    unavailablePartiesStore
      .addParties(stakeholders.toSeq)
      .map(ignoredParties =>
        TaskSuccess(
          s"Skipped batch with ignored version $vettedVersion: added ${ignoredParties.size} parties to ignore list: $ignoredParties"
        )
      )

  protected def completeUnlessAmuletVersionIgnored(
      vettedVersion: String,
      stakeholders: Set[PartyId],
      ignoreUnresponsiveParties: Boolean,
  )(task: => Future[TaskOutcome])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TaskOutcome] =
    if (amuletVersionIgnored(vettedVersion)) ignoreAmuletVersion(vettedVersion, stakeholders)
    else
      task
        .flatMap(recoverUnavailableParties(stakeholders))
        .recoverWith(recoverUnresponsiveParties(ignoreUnresponsiveParties))

  /** Provisional retry reset: once a submission involving previously unavailable parties
    * succeeds, they are removed from the store so that their backoff starts from the base
    * duration again the next time they become unavailable.
    */
  private def recoverUnavailableParties(
      stakeholders: Set[PartyId]
  )(outcome: TaskOutcome)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TaskOutcome] =
    outcome match {
      case _: TaskSuccess
          if svConfig.parameters.enabledFeatures.enablePersistedUnavailableParties =>
        unavailablePartiesStore
          .removeParties(stakeholders.toSeq)
          .map { restored =>
            if (restored.nonEmpty)
              logger.info(
                s"Submission succeeded, recovered ${restored.size} unavailable parties: $restored"
              )
            outcome
          }
      case _ => Future.successful(outcome)
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
    unavailablePartiesStore
      .addParties(informees.toSeq)
      .map(ignoredParties =>
        s"No vetted Amulet version for $contractIds; ignoring ${ignoredParties.size} parties: $ignoredParties"
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
      val toIgnore = UnresponsiveParties.fromThrowable(ex).getOrElse(Set.empty)
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

}
