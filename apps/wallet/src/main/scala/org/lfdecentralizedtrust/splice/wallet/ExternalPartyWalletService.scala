// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.ParticipantId
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.wallet.automation.ExternalPartyWalletAutomationService
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an external party's wallet. */
class ExternalPartyWalletService(
    ledgerClient: SpliceLedgerClient,
    key: ExternalPartyWalletStore.Key,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    storage: DbStorage,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    migrationId: Long,
    participantId: ParticipantId,
    params: SpliceParametersConfig,
    scanConnection: BftScanConnection,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    close: CloseContext,
) extends RetryProvider.Has
    with FlagCloseable
    with NamedLogging {

  val store: ExternalPartyWalletStore =
    ExternalPartyWalletStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      migrationId,
      participantId,
      automationConfig.ingestion,
      params.defaultLimit,
    )

  val automation = new ExternalPartyWalletAutomationService(
    store,
    ledgerClient,
    automationConfig,
    clock,
    domainTimeSync,
    retryProvider,
    params,
    scanConnection,
    loggerFactory,
    packageVersionSupport,
  )

  override def onClosed(): Unit = {
    automation.close()
    store.close()
    super.onClosed()
  }
}
