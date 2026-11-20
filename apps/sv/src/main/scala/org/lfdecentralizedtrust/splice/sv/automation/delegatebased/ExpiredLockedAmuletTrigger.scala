// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.sv.automation.unavailableparties.UnavailablePartiesGuard
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpiredLockedAmuletTrigger.{Task, getStakeholders}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders

import java.util.Optional
import scala.jdk.CollectionConverters.*

class ExpiredLockedAmuletTrigger(
    override protected val svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val unavailablePartiesStore: UnavailablePartiesStore,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    // TODO(#2885): switch to a low-contention trigger; this one will heavily content among SVs
) extends BatchedMultiDomainExpiredContractTrigger.Template[
      splice.amulet.LockedAmulet.ContractId,
      splice.amulet.LockedAmulet,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredAmuletBatchSize,
      svTaskContext.dsoStore.listLockedExpiredAmulets(Some(unavailablePartiesStore)),
      splice.amulet.LockedAmulet.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      getStakeholders,
    )
    with SvTaskBasedTrigger[Task]
    with UnavailablePartiesGuard {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    completeExpiryBatchWithSplitting(task, getStakeholders)(
      completeExpiryTaskAsDsoDelegate(_, controller)
    )
  }

  private def completeExpiryTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val stakeholders = task.work.stakeholders
    for {
      dsoRules <- store.getDsoRules()
      // Pin the dso-governance and amulet versions vetted by the DSO and all stakeholders, as TAPS alone fails to
      // find them when a stakeholder vets older versions than the DSO (https://github.com/DACH-NY/canton/issues/31450).
      // Revisit once that is fixed. See AmuletExpiryWithMinimalVettedPackagesIntegrationTest.
      preferredPackageIds <- svTaskContext.packageVersionSupport.getPreferredPackageIds(
        Seq(
          PackageIdResolver.Package.SpliceDsoGovernance -> Seq(store.key.dsoParty),
          PackageIdResolver.Package.SpliceAmulet -> stakeholders.toSeq,
        ),
        context.clock.now,
      )
      externalPartyConfigStates <- store.getExternalPartyConfigStatesPair()
      cmds = task.work.expiredContracts.flatMap(co =>
        dsoRules
          .exercise(
            _.exerciseDsoRules_LockedAmulet_ExpireAmuletV2(
              co.contractId,
              new splice.amulet.LockedAmulet_ExpireAmuletV2(
                externalPartyConfigStates.oldest.contractId,
                externalPartyConfigStates.newest.contractId,
              ),
              Optional.of(controller),
            )
          )
          .update
          .commands()
          .asScala
          .toSeq
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.AmuletExpiry)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          update = cmds,
        )
        .noDedup
        .withPreferredPackage(preferredPackageIds)
        .withSynchronizerId(dsoRules.domain)
        .yieldUnit()
    } yield TaskSuccess(s"archived expired locked amulet")
  }
}

object ExpiredLockedAmuletTrigger extends ContractStakeholders[splice.amulet.LockedAmulet] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[
        splice.amulet.LockedAmulet.ContractId,
        splice.amulet.LockedAmulet,
      ]
    ]

  override def informees(payload: splice.amulet.LockedAmulet): Seq[String] =
    Seq(payload.amulet.owner) ++ payload.lock.holders.asScala

  override def dso(payload: splice.amulet.LockedAmulet): String = payload.amulet.dso
}
