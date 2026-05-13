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
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import java.util.Optional
import scala.jdk.CollectionConverters.*

class ExpiredAmuletTrigger(
    svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
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
      svTaskContext.dsoStore.listExpiredAmulets(context.config.ignoredExpiredAmuletPartyIds),
      splice.amulet.Amulet.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      c => Seq(c.dso, c.owner).map(PartyId.tryFromProtoPrimitive(_)),
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val informees = task.work.expiredContracts
      .map(c => PartyId.tryFromProtoPrimitive(c.payload.owner))
      .toSet + store.key.dsoParty
    for {
      dsoRules <- store.getDsoRules()
      supports24hSubmissionDelay <- svTaskContext.packageVersionSupport.supports24hSubmissionDelay(
        informees.toSeq,
        context.clock.now,
      )
      cmds <-
        if (supports24hSubmissionDelay.supported) {
          println("A")
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
          println("B")
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
      synchronizerId <- store.getDsoRules().map(_.domain)
      preferredPackages <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.AmuletExpiry)
        .getSupportedPackageVersion(
          synchronizerId,
          Seq(
            (
              "splice-amulet",
              Seq(store.key.dsoParty, store.key.svParty) ++ task.work.expiredContracts
                .map(c => PartyId.tryFromProtoPrimitive(c.payload.owner)),
            )
          ),
          CantonTimestamp.now(),
        )
      _ = println(preferredPackages)
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.AmuletExpiry)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          update = cmds,
        )
        .noDedup
        .withPreferredPackage(preferredPackages.map(_.packageId))
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
