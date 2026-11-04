// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection.CommandId
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.wallet.config.WalletSweepConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService.AmuletOperationDedupConfig
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class WalletTokenStandardSweepTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    config: WalletSweepConfig,
    scanConnection: ScanConnection,
    treasury: TreasuryService,
    dedupDuration: DedupDuration,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends WalletSweepTrigger(context, store, config, scanConnection) {

  // Pending transfer instructions lock the swept amount into a LockedAmulet, and
  // getAmuletBalanceWithHoldingFees only counts unlocked Amulet, so nothing is outstanding.
  override protected def getOutstandingBalanceUsd(
      amuletPrice: java.math.BigDecimal,
      currentAmuletConfig: AmuletConfig[USD],
  )(implicit tc: TraceContext): Future[BigDecimal] = Future.successful(BigDecimal(0))

  override protected def outstandingDescription: Nothing = throw Status.INTERNAL
    .withDescription(
      s"outstandingDescription should never be called for ${this.getClass.getSimpleName}"
    )
    .asRuntimeException

  override protected def extraRetrieveTasksValidation()(implicit tc: TraceContext): Future[Unit] =
    packageVersionSupport
      .supportsTokenStandard(
        Seq(store.key.endUserParty, store.key.dsoParty, config.receiver),
        context.clock.now,
      )
      .map { support =>
        if (!support.supported)
          throw Status.FAILED_PRECONDITION
            .withDescription(
              s"Token standard is not supported for sweeping to ${config.receiver}; disable `use-token-standard-transfer`"
            )
            .asRuntimeException
      }

  override protected def sweep(
      task: WalletSweepTrigger.Task,
      balanceUsd: BigDecimal,
      outstandingBalanceUsd: BigDecimal,
      amountToSendBeforeFeesUsd: BigDecimal,
      amountToSendAfterFeesUsd: BigDecimal,
      amountToSendAfterFeesCC: java.math.BigDecimal,
  )(implicit tc: TraceContext): Future[Unit] = {
    logger.info(
      s"Sweeping $amountToSendAfterFeesUsd USD ($amountToSendAfterFeesCC CC) to ${config.receiver} via token standard, trackingId ${task.trackingId}"
    )
    treasury
      .enqueueTokenStandardTransferOperationV1(
        receiverPartyId = config.receiver,
        amount = BigDecimal(amountToSendAfterFeesCC),
        description = "sweep",
        expiresAt = context.clock.now.plus(java.time.Duration.ofHours(24)),
        dedup = Some(
          AmuletOperationDedupConfig(
            CommandId(
              "org.lfdecentralizedtrust.splice.wallet.tokenStandardSweep",
              Seq(store.key.endUserParty),
              task.trackingId,
            ),
            dedupDuration,
            recoverAcceptedDuplicates = true,
          )
        ),
      )
      .map(result => logger.debug(s"Token standard sweep result: $result"))
  }
}
