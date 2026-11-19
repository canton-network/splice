// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.{PackageVersionSupport, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.AmuletRulesContractStateMigrationTrigger.{
  Task,
  latestContractStateSchemaVersion,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Migrates the contract state schema of AmuletRules to the latest version known to this SV app
  * by exercising DsoRules_AmuletRules_MigrateContractStateSchema.
  */
class AmuletRulesContractStateMigrationTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] =
    // The migrate choice only exists in newer amulet versions
    packageVersionSupport
      .supportsContractStateSchemaMigration(Seq(store.key.dsoParty), context.clock.now)
      .flatMap { support =>
        if (support.supported)
          store.lookupAmuletRules().map { amuletRulesO =>
            amuletRulesO.toList
              .map { amuletRules =>
                Task(
                  amuletRules.contractId,
                  amuletRules.payload.contractStateSchemaVersion.toScala.fold(0L)(_.longValue),
                )
              }
              .filter(_.currentVersion < latestContractStateSchemaVersion)
          }
        else Future.successful(Seq.empty)
      }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_AmuletRules_MigrateContractStateSchema(
          task.amuletRulesCid,
          store.key.svParty.toProtoPrimitive,
        )
      )
      result <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
      changes = result.exerciseResult.result.changesDescription.asScala
        .map(change => s"${change._1}: ${change._2}")
        .mkString("\n")
    } yield TaskSuccess(
      s"Migrated AmuletRules contract state schema from version ${task.currentVersion} to $latestContractStateSchemaVersion, changes:\n$changes"
    )
  }

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] =
    store.lookupAmuletRules().map { amuletRulesO =>
      !amuletRulesO.exists(_.contractId == task.amuletRulesCid)
    }
}

object AmuletRulesContractStateMigrationTrigger {

  // This should be kept in sync with the value defined in Splice.AmuletRules
  val latestContractStateSchemaVersion: Long = 1L

  final case class Task(
      amuletRulesCid: splice.amuletrules.AmuletRules.ContractId,
      currentVersion: Long,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("amuletRulesCid", _.amuletRulesCid.contractId.unquoted),
        param("currentVersion", _.currentVersion),
      )
  }
}
