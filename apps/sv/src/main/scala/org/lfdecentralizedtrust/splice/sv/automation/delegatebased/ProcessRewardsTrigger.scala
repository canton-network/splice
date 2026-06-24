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
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{BftScanConnection, ScanConnection}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricsContext.Implicits.empty
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.da.set.types.Set as DamlSet
import com.daml.ledger.javaapi.data.Unit as DamlUnit
import com.digitalasset.canton.topology.PartyId

import java.math.BigDecimal
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import ProcessRewardsTriggerBase.*
import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Errors, Latency}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

import scala.util.{Failure, Success}

private[delegatebased] abstract class ProcessRewardsTriggerBase(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    getOwnScanConnection: () => Future[ScanConnection],
    getPeerBftScanConnection: () => Future[BftScanConnection],
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
  private val rewardMetrics = new ProcessRewardsMetrics(context.metricsFactory, isDryRun)

  override def extraMetricLabels: Seq[(String, String)] = Seq("dryRun" -> isDryRun.toString)

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
      providersWithWrongVettingState <- determineProvidersWithWrongVettingState(batch)
      choiceArg = new ProcessRewardsV2_ProcessBatch(
        damlBatch,
        providersWithWrongVettingState,
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
      _ = rewardMetrics.processRewardsProcessingDelay.update(delay)
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

  private def determineProvidersWithWrongVettingState(
      batch: GetRewardAccountingBatchResponse
  )(implicit tc: TraceContext): Future[DamlSet[String]] = {
    val providers = batch match {
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(
            value
          ) =>
        value.mintingAllowances.map(_.provider)
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(_) =>
        Vector.empty[String]
    }
    val now = context.clock.now
    Future
      .traverse(providers) { provider =>
        val partyId = PartyId.tryFromProtoPrimitive(provider)
        svTaskContext.packageVersionSupport
          .supportsTrafficBasedAppRewards(Seq(partyId), now)
          .map(support => provider -> support.supported)
      }
      .map { supportByProvider =>
        val withWrongVettingState = supportByProvider.collect { case (provider, false) => provider }
        damlSetOf(withWrongVettingState)
      }
  }

  private def damlSetOf(values: Seq[String]): DamlSet[String] =
    new DamlSet(values.map(_ -> DamlUnit.getInstance).toMap.asJava)

  private def fetchBatch(round: Long, batchHash: String)(implicit
      tc: TraceContext
  ): Future[GetRewardAccountingBatchResponse] = {
    def bftReadBatch: Future[GetRewardAccountingBatchResponse] = {
      rewardMetrics.processRewardsBatchBftReads.mark()
      for {
        bftScan <- getPeerBftScanConnection()
        response <- bftScan.getRewardAccountingBatch(round, batchHash)
      } yield response match {
        case Some(batch) =>
          logger.info(s"Obtained batch for round $round with hash $batchHash via BFT read.")
          batch
        case None =>
          throw Status.FAILED_PRECONDITION
            .withDescription(
              s"Failed to obtain batch for round $round with hash $batchHash via BFT read."
            )
            .asRuntimeException()
      }
    }

    for {
      ownScan <- getOwnScanConnection()
      batch <- ownScan.getRewardAccountingBatch(round, batchHash).transformWith {
        case Success(Some(batch)) =>
          Future.successful(batch)
        case Success(None) =>
          bftReadBatch
        case Failure(_) =>
          bftReadBatch
      }
    } yield batch
  }
}

class ProcessRewardsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    getOwnScanConnection: () => Future[ScanConnection],
    getPeerBftScanConnection: () => Future[BftScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends ProcessRewardsTriggerBase(
      context,
      svTaskContext,
      getOwnScanConnection,
      getPeerBftScanConnection,
      isDryRun = false,
    )

class ProcessRewardsDryRunTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    getOwnScanConnection: () => Future[ScanConnection],
    getPeerBftScanConnection: () => Future[BftScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends ProcessRewardsTriggerBase(
      context,
      svTaskContext,
      getOwnScanConnection,
      getPeerBftScanConnection,
      isDryRun = true,
    )

object ProcessRewardsTriggerBase {
  type ProcessRewardsV2Contract = AssignedContract[
    ProcessRewardsV2.ContractId,
    ProcessRewardsV2,
  ]

  class ProcessRewardsMetrics(metricsFactory: LabeledMetricsFactory, dryRun: Boolean) {

    private val metricsContext = MetricsContext.Empty.withExtraLabels("dryRun" -> dryRun.toString)

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix

    val processRewardsProcessingDelay: Timer =
      metricsFactory.timer(
        MetricInfo(
          name = prefix :+ "process_rewards_v2" :+ "processing_delay",
          summary = "Delay between round close and ProcessRewardsV2 processing",
          description =
            "This metric captures the time it took between the closing of a round, and this SV's processing of a ProcessRewardsV2 contract for that round. Labeled with dryRun.",
          qualification = Latency,
        )
      )(metricsContext)

    val processRewardsBatchBftReads: Meter =
      metricsFactory.meter(
        MetricInfo(
          name = prefix :+ "process_rewards_v2" :+ "batch_bft_reads",
          summary = "Count of BFT reads of the reward-accounting batch",
          description =
            "This metric counts the BFT reads of the reward-accounting batch performed by the ProcessRewardsV2 trigger, i.e., the cases where this SV's own Scan could not provide the batch and it had to be obtained via a BFT read against peer Scans. Labeled with dryRun.",
          qualification = Errors,
        )
      )(metricsContext)
  }
}
