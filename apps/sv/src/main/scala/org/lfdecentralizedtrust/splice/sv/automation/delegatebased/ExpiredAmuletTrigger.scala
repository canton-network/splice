// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpiredAmuletTrigger.*
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.IgnoredPartiesStore

import java.util.Optional
import scala.jdk.CollectionConverters.*

class ExpiredAmuletTrigger(
    override protected val svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val ignoredPartiesStore: IgnoredPartiesStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    // TODO(#2885): switch to a low-contention trigger; this one will heavily content among SVs
) extends BatchedMultiDomainExpiredContractTrigger.Template[
      splice.amulet.Amulet.ContractId,
      splice.amulet.Amulet,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredAmuletBatchSize,
      svTaskContext.dsoStore.listExpiredAmulets(ignoredPartiesStore.getAll),
      splice.amulet.Amulet.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      c => Seq(c.dso, c.owner).map(PartyId.tryFromProtoPrimitive(_)),
    )
    with SvTaskBasedTrigger[Task]
    with IgnoredAmuletVersionGuard {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val informees =
      task.work.expiredContracts.map(c => PartyId.tryFromProtoPrimitive(c.payload.owner)).toSet
    completeWithIgnoredAmuletVersionCheck(
      task.work.vettedVersion.toString,
      informees,
      enableUnresponsivePartiesAutoIgnore = true,
    )(completeExpiryTaskAsDsoDelegate(task, controller, informees))
  }

  private def completeExpiryTaskAsDsoDelegate(
      task: Task,
      controller: String,
      informees: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val allParties = informees + store.key.dsoParty
    for {
      dsoRules <- store.getDsoRules()
      supports24hSubmissionDelay <- svTaskContext.packageVersionSupport.supports24hSubmissionDelay(
        allParties.toSeq,
        Seq(store.key.dsoParty),
        context.clock.now,
      )
      cmds <-
        if (supports24hSubmissionDelay.supported) {
          store.getExternalPartyConfigStatesPair().map { externalPartyConfigStates =>
            task.work.expiredContracts.flatMap(co =>
              dsoRules
                .exercise(
                  _.exerciseDsoRules_Amulet_ExpireV2(
                    co.contractId,
                    new splice.amulet.Amulet_ExpireV2(
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
          }
        } else {
          store.getLatestActiveOpenMiningRound().map { round =>
            task.work.expiredContracts.flatMap(co =>
              dsoRules
                .exercise(
                  _.exerciseDsoRules_Amulet_Expire(
                    co.contractId,
                    new splice.amulet.Amulet_Expire(
                      round.contractId
                    ),
                    Optional.of(controller),
                  )
                )
                .update
                .commands()
                .asScala
                .toSeq
            )
          }
        }
      // remove once TAPS use partial information from pass 1 in pass 2 (https://github.com/DACH-NY/canton/issues/31450)
      preferredPackageIds = supports24hSubmissionDelay.packageIds
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
    } yield TaskSuccess("archived expired amulet")
  }
}

object ExpiredAmuletTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[
        splice.amulet.Amulet.ContractId,
        splice.amulet.Amulet,
      ]
    ]
}
