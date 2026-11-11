// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock.substitution as substitutionCodegen
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders
import org.lfdecentralizedtrust.splice.util.GovernanceLockUtil

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import ExpireGovernanceLockSubstitutionTrigger.{
  Substitution,
  SubstitutionCid,
  Task,
  getStakeholders,
}

/** Garbage-collects expired `GovernanceLockSubstitution`s. */
class ExpireGovernanceLockSubstitutionTrigger(
    override protected val svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val unavailablePartiesStore: UnavailablePartiesStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends BatchedMultiDomainExpiredContractTrigger.Template[SubstitutionCid, Substitution](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredGovernanceLockSubstitutionBatchSize,
      svTaskContext.dsoStore.listExpiredGovernanceLockSubstitutions(Some(unavailablePartiesStore)),
      substitutionCodegen.GovernanceLockSubstitution.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      getStakeholders,
    )
    with SvTaskBasedTrigger[Task]
    with UnavailablePartiesGuard {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    completeUnlessAmuletVersionIgnored(
      task.work.vettedVersion.toString,
      task.work.stakeholders,
      ignoreUnresponsiveParties = true,
    )(completeExpiryTaskAsDsoDelegate(task, controller))

  private def completeExpiryTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val expiredSubstitutions = task.work.expiredContracts
    for {
      dsoRules <- store.getDsoRules()
      cmds = expiredSubstitutions.flatMap(co =>
        dsoRules
          .exercise(
            _.exerciseDsoRules_GovernanceLockSubstitution_DsoExpire(co.contractId, controller)
          )
          .update
          .commands()
          .asScala
          .toSeq
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          update = cmds,
        )
        .noDedup
        .withSynchronizerId(dsoRules.domain)
        .yieldUnit()
    } yield TaskSuccess(s"archived ${expiredSubstitutions.size} expired substitution proposals")
  }
}

object ExpireGovernanceLockSubstitutionTrigger
    extends ContractStakeholders[substitutionCodegen.GovernanceLockSubstitution] {
  private type SubstitutionCid = substitutionCodegen.GovernanceLockSubstitution.ContractId
  private type Substitution = substitutionCodegen.GovernanceLockSubstitution

  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[SubstitutionCid, Substitution]
    ]

  override def informees(
      payload: substitutionCodegen.GovernanceLockSubstitution
  ): Seq[String] =
    GovernanceLockUtil.substitutionInformees(payload)

  override def dso(payload: substitutionCodegen.GovernanceLockSubstitution): String =
    payload.target.dso
}
