// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.cryptohash.Hash
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.automation.RewardProcessingMetrics
import org.lfdecentralizedtrust.splice.sv.config.SvScanConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{AssignedContract, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

abstract class CalculateRewardsTriggerBase(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConfig: SvScanConfig,
    upgradesConfig: UpgradesConfig,
    isDryRun: Boolean,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends PollingParallelTaskExecutionTrigger[CalculateRewardsTriggerBase.Task] {

  import CalculateRewardsTriggerBase.*

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty
  private val rewardMetrics = new RewardProcessingMetrics(context.metricsFactory)

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = for {
    calculateRewards <- store.listCalculateRewardsV2()
  } yield calculateRewards.filter(_.payload.dryRun == isDryRun).map(Task(_))

  override def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.calculateRewards.payload.round.number
    for {
      rootHash <- getRootHash(round)
      action = startProcessingRewardsAction(
        task.calculateRewards.contractId,
        rootHash,
      )
      queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"skipping as confirmation from $svParty is already created for CalculateRewardsV2 round $round"
            )
          )
        case QueryResult(offset, None) =>
          for {
            dsoRules <- store.getDsoRules()
            cmd = dsoRules.exercise(
              _.exerciseDsoRules_ConfirmAction(
                svParty.toProtoPrimitive,
                action,
              )
            )
            _ <- connection
              .submit(
                actAs = Seq(svParty),
                readAs = Seq(dsoParty),
                update = cmd,
              )
              .withDedup(
                commandId = SpliceLedgerConnection.CommandId(
                  "org.lfdecentralizedtrust.splice.sv.createStartProcessingRewardsV2Confirmation",
                  Seq(svParty, dsoParty),
                  task.calculateRewards.contractId.contractId,
                ),
                deduplicationOffset = offset,
              )
              .yieldUnit()
            delay = java.time.Duration.between(
              task.calculateRewards.payload.roundClosedAt,
              context.clock.now.toInstant,
            )
            _ = rewardMetrics.calculateRewardsProcessingDelay.update(delay)(
              MetricsContext.Empty.withExtraLabels("dryRun" -> isDryRun.toString)
            )
          } yield TaskSuccess(
            s"created confirmation for CalculateRewardsV2 round $round, processingDelay=$delay"
          )
      }
    } yield taskOutcome
  }

  override def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractById(CalculateRewardsV2.COMPANION)(task.calculateRewards.contractId)
      .map(_.isEmpty)

  private def withScanConnection[T](f: ScanConnection => Future[T])(implicit
      tc: TraceContext
  ): Future[T] =
    ScanConnection
      .singleUncached(
        ScanAppClientConfig(NetworkAppClientConfig(scanConfig.internalUrl)),
        upgradesConfig,
        context.clock,
        context.retryProvider,
        loggerFactory,
        retryConnectionOnInitialFailure = false,
      )
      .transformWith {
        case Failure(ex) =>
          Future.failed(
            new RuntimeException("Failed to connect to scan for root hash lookup", ex)
          )
        case Success(conn) =>
          f(conn)
      }

  private def getRootHash(round: Long)(implicit tc: TraceContext): Future[Hash] =
    withScanConnection { conn =>
      conn.getRewardAccountingRootHash(round).map {
        case Some(response) => new Hash(response.rootHash)
        case None =>
          throw new RuntimeException(
            s"Root hash not available from scan for round $round"
          )
      }
    }

  private def startProcessingRewardsAction(
      calculateRewardsCid: CalculateRewardsV2.ContractId,
      rootHash: Hash,
  ): ActionRequiringConfirmation =
    new ARC_AmuletRules(
      new CRARC_StartProcessingRewardsV2(
        new AmuletRules_StartProcessingRewardsV2(
          calculateRewardsCid,
          rootHash,
        )
      )
    )

}

class CalculateRewardsTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConfig: SvScanConfig,
    upgradesConfig: UpgradesConfig,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends CalculateRewardsTriggerBase(
      context,
      store,
      connection,
      scanConfig,
      upgradesConfig,
      isDryRun = false,
    )

class CalculateRewardsDryRunTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConfig: SvScanConfig,
    upgradesConfig: UpgradesConfig,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends CalculateRewardsTriggerBase(
      context,
      store,
      connection,
      scanConfig,
      upgradesConfig,
      isDryRun = true,
    )

object CalculateRewardsTriggerBase {

  final case class Task(
      calculateRewards: AssignedContract[
        CalculateRewardsV2.ContractId,
        CalculateRewardsV2,
      ]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("round", _.calculateRewards.payload.round.number),
        param("dryRun", _.calculateRewards.payload.dryRun.toString.unquoted),
      )
  }
}
