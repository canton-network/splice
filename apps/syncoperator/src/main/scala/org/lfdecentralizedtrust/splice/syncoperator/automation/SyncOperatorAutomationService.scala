// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import org.lfdecentralizedtrust.splice.automation.{AutomationService, AutomationServiceCompanion}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.TriggerClass
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization

/** Manages background automation that runs on a sync operator app. */
class SyncOperatorAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
) extends AutomationService(
      automationConfig,
      clock,
      // Nothing registered here depends on domain time.
      DomainTimeSynchronization.Noop,
      retryProvider,
    ) {

  override def companion: SyncOperatorAutomationService.type = SyncOperatorAutomationService
}

object SyncOperatorAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] = Seq.empty
}
