// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.IgnoredPartiesStore
import org.lfdecentralizedtrust.splice.util.{ChoiceContextWithDisclosures, TokenStandardMetadata}
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import cats.implicits.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.anyvalue.AV_Bool

class ExpiredAmuletAllocationV2Trigger(
    override protected val svConfig: SvAppBackendConfig,
    clock: Clock,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val ignoredPartiesStore: IgnoredPartiesStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends BatchedMultiDomainExpiredContractTrigger.Template[
      splice.amuletallocationv2.AmuletAllocationV2.ContractId,
      splice.amuletallocationv2.AmuletAllocationV2,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredAmuletAllocationBatchSize,
      svTaskContext.dsoStore.listExpiredAmuletAllocationsV2(ignoredPartiesStore.getAll),
      splice.amuletallocationv2.AmuletAllocationV2.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      ExpiredAmuletAllocationV2Trigger.getStakeholders,
    )
    with SvTaskBasedTrigger[ExpiredAmuletAllocationV2Trigger.Task]
    with IgnoredAmuletVersionGuard {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      task: ExpiredAmuletAllocationV2Trigger.Task,
      controller: String,
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    completeWithIgnoredAmuletVersionCheck(
      task.work.vettedVersion.toString,
      task.work.stakeholders,
      store.key.dsoParty,
      enableUnresponsivePartiesAutoIgnore = true,
    )(completeExpiryTaskAsDsoDelegate(task, controller))
  }

  private def completeExpiryTaskAsDsoDelegate(
      task: ExpiredAmuletAllocationV2Trigger.Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val stakeholders = task.work.stakeholders
    val informees = stakeholders - store.key.dsoParty
    for {
      packageSupport <- svTaskContext.packageVersionSupport.supportsAmuletAllocationV2(
        stakeholders.toSeq,
        clock.now,
      )
      res <-
        if (!packageSupport.supported) {
          logger.info(
            s"Skipping expiry of ${task.work.expiredContracts.size} allocations because not all parties have vetted the required Amulet package version. Parties: ${stakeholders
                .mkString(", ")}"
          )
          Future.successful(
            TaskSuccess(
              s"Batch of ${task.work.expiredContracts.size} skipped due to old package version."
            )
          )
        } else {
          for {
            dsoRules <- store.getDsoRules()
            extAmuletRules <- store.getExternalPartyAmuletRules()
            lockedAmulets <- store.multiDomainAcsStore.lookupContractsById(
              splice.amulet.LockedAmulet.COMPANION
            )(task.work.expiredContracts.flatMap(_.payload.lockedAmulet.toScala))
            lockedAmuletsMap = lockedAmulets.toList.groupByNel(_.contractId.contractId)
            cancellations = task.work.expiredContracts.map { allocation =>
              val lockedAmuletContract = for {
                lockedAmuletCid <- allocation.payload.lockedAmulet.toScala
                lockedAmuletContract <- lockedAmuletsMap.get(lockedAmuletCid.contractId)
              } yield lockedAmuletContract.head // guaranteed to only have one element
              val cancel = new splice.api.token.allocationv2.Allocation_Cancel(
                java.util.List.of(dsoRules.payload.dso),
                new splice.api.token.metadatav1.ExtraArgs(
                  new splice.api.token.metadatav1.ChoiceContext(
                    java.util.Map.of(
                      TokenStandardMetadata.expireLockKey,
                      new AV_Bool(lockedAmuletContract.isDefined),
                    )
                  ),
                  ChoiceContextWithDisclosures.emptyMetadata,
                ),
              )
              new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
                allocation.contractId.toInterface(
                  splice.api.token.allocationv2.Allocation.INTERFACE
                ),
                cancel,
              )
            }
            expireAllocations =
              new splice.externalpartyamuletrules.ExternalPartyAmuletRules_ExpireAmuletAllocationsV2(
                cancellations.asJava,
                informees.map(_.toProtoPrimitive).toList.asJava,
              )
            res <- svTaskContext
              .connection(SpliceLedgerConnectionPriority.AmuletExpiry)
              .submit(
                Seq(store.key.svParty),
                Seq(store.key.dsoParty),
                update = dsoRules
                  .exercise(
                    _.exerciseDsoRules_ExpireAmuletAllocationsV2(
                      extAmuletRules.contract.contractId,
                      expireAllocations,
                      controller,
                    )
                  )
                  .update
                  .commands()
                  .asScala
                  .toSeq,
              )
              .noDedup
              .withSynchronizerId(dsoRules.domain)
              .yieldUnit()
              .map(_ =>
                TaskSuccess(
                  s"archived batch of ${task.work.expiredContracts.size} expired Amulet Allocations"
                )
              )
          } yield res
        }
    } yield res
  }

}

object ExpiredAmuletAllocationV2Trigger
    extends ContractStakeholders[splice.amuletallocationv2.AmuletAllocationV2] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[
        splice.amuletallocationv2.AmuletAllocationV2.ContractId,
        splice.amuletallocationv2.AmuletAllocationV2,
      ]
    ]

  override def informees(payload: splice.amuletallocationv2.AmuletAllocationV2): Seq[String] =
    payload.allocation.authorizer.owner.toScala.toList ++ payload.settlement.executors.asScala

  override def dso(payload: splice.amuletallocationv2.AmuletAllocationV2): String =
    payload.allocation.admin
}
