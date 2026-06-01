// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  AutomationServiceCompanion,
  SpliceAppAutomationService,
}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.TriggerClass
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore

import scala.concurrent.ExecutionContext

class ExternalPartyWalletAutomationService(
    store: ExternalPartyWalletStore,
    ledgerClient: SpliceLedgerClient,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    retryProvider: RetryProvider,
    params: SpliceParametersConfig,
    scanConnection: BftScanConnection,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      automationConfig,
      clock,
      domainTimeSync,
      store,
      ledgerClient,
      retryProvider,
      params,
    ) {

  override protected def metricsContext: MetricsContext =
    MetricsContext(
      "automation_service" -> getClass.getSimpleName,
      "party" -> store.key.externalParty.toString,
    )

  override def companion
      : org.lfdecentralizedtrust.splice.wallet.automation.ExternalPartyWalletAutomationService.type =
    ExternalPartyWalletAutomationService

  registerTrigger(
    new MintingDelegationCollectRewardsTrigger(
      triggerContext,
      store,
      scanConnection,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )
}

object ExternalPartyWalletAutomationService extends AutomationServiceCompanion {

  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] = Seq.empty
}
