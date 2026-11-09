// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{AutomationService, AutomationServiceCompanion}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.TriggerClass
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorTrafficControlConfig

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a sync operator app. */
class SyncOperatorAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    synchronizerId: SynchronizerId,
    synchronizerNode: SyncOperatorSynchronizerNode,
    trafficControlConfig: SyncOperatorTrafficControlConfig,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(
      automationConfig,
      clock,
      // Nothing registered here depends on domain time.
      DomainTimeSynchronization.Noop,
      retryProvider,
    ) {

  override def companion: SyncOperatorAutomationService.type = SyncOperatorAutomationService

  registerTrigger(
    new ReconcileDedicatedSynchronizerParametersTrigger(
      triggerContext,
      synchronizerId,
      synchronizerNode,
      trafficControlConfig,
    )
  )
}

object SyncOperatorAutomationService extends AutomationServiceCompanion {
  import AutomationServiceCompanion.aTrigger

  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    Seq(aTrigger[ReconcileDedicatedSynchronizerParametersTrigger])
}
