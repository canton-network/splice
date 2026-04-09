// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
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
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  GetRewardAccountingBatchResponse,
  RewardAccountingMintingAllowance,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvScanConfig
import org.lfdecentralizedtrust.splice.util.{AssignedContract, TemplateJsonDecoder}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.da.set.types.{Set as DamlSet}

import java.math.BigDecimal
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

import ProcessRewardsTriggerBase.*

private[delegatebased] abstract class ProcessRewardsTriggerBase(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    scanConfig: SvScanConfig,
    upgradesConfig: UpgradesConfig,
    isDryRun: Boolean,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends OnAssignedContractTrigger.Template[
      ProcessRewardsV2.ContractId,
      ProcessRewardsV2,
    ](
      svTaskContext.dsoStore,
      ProcessRewardsV2.COMPANION,
    )
    with SvTaskBasedTrigger[ProcessRewardsV2Contract] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      task: ProcessRewardsV2Contract,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    if (task.payload.dryRun != isDryRun) {
      Future.successful(
        TaskSuccess(
          s"Skipping ProcessRewardsV2 for round ${task.payload.round.number} with dryRun=${task.payload.dryRun}"
        )
      )
    } else {
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
      } yield TaskSuccess(
        s"Processed batch for ProcessRewardsV2 round $round, batchHash=$batchHash, dryRun=$isDryRun"
      )
    }
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
    withScanConnection { conn =>
      conn.getRewardAccountingBatch(round, batchHash).map {
        case Some(response) => response
        case None =>
          throw new RuntimeException(
            s"Batch not found from scan for round $round with hash $batchHash"
          )
      }
    }

  private def withScanConnection[T](f: ScanConnection => Future[T])(implicit
      tc: TraceContext
  ): Future[T] =
    ScanConnection
      .singleUncached(
        ScanAppClientConfig(
          NetworkAppClientConfig(scanConfig.internalUrl)
        ),
        upgradesConfig,
        context.clock,
        context.retryProvider,
        loggerFactory,
        retryConnectionOnInitialFailure = false,
      )
      .transformWith {
        case Failure(ex) =>
          Future.failed(
            new RuntimeException("Failed to connect to scan for batch lookup", ex)
          )
        case Success(conn) =>
          f(conn)
      }
}

class ProcessRewardsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    scanConfig: SvScanConfig,
    upgradesConfig: UpgradesConfig,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends ProcessRewardsTriggerBase(
      context,
      svTaskContext,
      scanConfig,
      upgradesConfig,
      isDryRun = false,
    )

class ProcessRewardsDryRunTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    scanConfig: SvScanConfig,
    upgradesConfig: UpgradesConfig,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends ProcessRewardsTriggerBase(
      context,
      svTaskContext,
      scanConfig,
      upgradesConfig,
      isDryRun = true,
    )

private[delegatebased] object ProcessRewardsTriggerBase {
  type ProcessRewardsV2Contract = AssignedContract[
    ProcessRewardsV2.ContractId,
    ProcessRewardsV2,
  ]
}
