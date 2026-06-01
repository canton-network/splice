// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.cryptohash.Hash
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.{
  MintingAllowance,
  ProcessRewardsV2,
  ProcessRewardsV2_ProcessBatch,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.batch.{
  BatchOfBatches,
  BatchOfMintingAllowances,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  GetRewardAccountingBatchResponse,
  RewardAccountingMintingAllowance,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.automation.RewardProcessingMetrics
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.da.set.types.{Set as DamlSet}

import java.math.BigDecimal
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

import ProcessRewardsTriggerBase.*

private[delegatebased] abstract class ProcessRewardsTriggerBase(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    scanConnectionF: Future[ScanConnection],
    isDryRun: Boolean,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ProcessRewardsV2.ContractId,
      ProcessRewardsV2,
    ](
      svTaskContext.dsoStore,
      ProcessRewardsV2.COMPANION,
    )
    with SvTaskBasedTrigger[ProcessRewardsV2Contract] {

  private val store = svTaskContext.dsoStore
  private val rewardMetrics = new RewardProcessingMetrics(context.metricsFactory)

  override protected def source(implicit
      traceContext: TraceContext
  ): Source[ProcessRewardsV2Contract, NotUsed] =
    super.source.filter(_.payload.dryRun == isDryRun)

  override def completeTaskAsDsoDelegate(
      task: ProcessRewardsV2Contract,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.payload.round.number
    val batchHash = task.payload.batchHash.value
    val batchF = fetchBatch(round, batchHash)
    val dsoRulesF = store.getDsoRules()
    for {
      batch <- batchF
      dsoRules <- dsoRulesF
      damlBatch = convertBatch(batch)
      choiceArg = new ProcessRewardsV2_ProcessBatch(
        damlBatch,
        // TODO (#5715) determine 'providersWithWrongVettingState'
        new DamlSet(java.util.Collections.emptyMap()),
      )
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ProcessRewardsV2_ProcessBatch(
          task.contractId,
          choiceArg,
          controller,
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          cmd,
        )
        .noDedup
        .yieldUnit()
      delay = java.time.Duration
        .between(task.payload.roundClosedAt, context.clock.now.toInstant)
      _ = rewardMetrics.processRewardsProcessingDelay.update(delay)(
        MetricsContext.Empty.withExtraLabels("dryRun" -> isDryRun.toString)
      )
    } yield TaskSuccess(
      s"Processed round $round, processingDelay=$delay, batchType=${batchTypeOf(batch)}"
    )
  }

  private def batchTypeOf(response: GetRewardAccountingBatchResponse): String =
    response match {
      case _: GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches =>
        "BatchOfBatches"
      case _: GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances =>
        "BatchOfMintingAllowances"
    }

  private def convertBatch(
      response: GetRewardAccountingBatchResponse
  ): org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.Batch =
    response match {
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(value) =>
        val childHashes = value.childHashes.map(h => new Hash(h)).asJava
        new BatchOfBatches(childHashes)
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(
            value
          ) =>
        val allowances = value.mintingAllowances
          .map((a: RewardAccountingMintingAllowance) =>
            new MintingAllowance(a.provider, new BigDecimal(a.amount))
          )
          .asJava
        new BatchOfMintingAllowances(allowances)
    }

  private def fetchBatch(round: Long, batchHash: String)(implicit
      tc: TraceContext
  ): Future[GetRewardAccountingBatchResponse] =
    scanConnectionF.flatMap(_.getRewardAccountingBatch(round, batchHash)).map {
      case Some(response) => response
      case None =>
        // TODO (#5623) replace with BFT read
        throw new RuntimeException(
          s"Batch not found from scan for round $round with hash $batchHash"
        )
    }
}

class ProcessRewardsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    scanConnectionF: Future[ScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends ProcessRewardsTriggerBase(
      context,
      svTaskContext,
      scanConnectionF,
      isDryRun = false,
    )

class ProcessRewardsDryRunTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    scanConnectionF: Future[ScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends ProcessRewardsTriggerBase(
      context,
      svTaskContext,
      scanConnectionF,
      isDryRun = true,
    )

private[delegatebased] object ProcessRewardsTriggerBase {
  type ProcessRewardsV2Contract = AssignedContract[
    ProcessRewardsV2.ContractId,
    ProcessRewardsV2,
  ]
}
