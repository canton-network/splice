// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{AutomationService, AutomationServiceCompanion}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.TriggerClass
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SynchronizerNodeService}
import org.lfdecentralizedtrust.splice.lsu.LsuTransferTrafficTrigger
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode
import org.lfdecentralizedtrust.splice.syncoperator.config.{
  SyncOperatorLsuConfig,
  SyncOperatorTrafficControlConfig,
}

import java.nio.file.Path

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a sync operator app. */
class SyncOperatorAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    synchronizerId: SynchronizerId,
    synchronizerNodeService: SynchronizerNodeService[SyncOperatorSynchronizerNode],
    lsuConfig: Option[SyncOperatorLsuConfig],
    lsuDumpPath: Option[Path],
    trafficControlConfig: SyncOperatorTrafficControlConfig,
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
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
      synchronizerNodeService,
      trafficControlConfig,
    )
  )

  registerTrigger(
    new UnlimitedTrafficTrigger(
      triggerContext,
      synchronizerId,
      synchronizerNodeService,
      trafficBalanceReconciliationDelay,
    )
  )

  registerLsuTriggers()

  /** The upgrade path runs only once a successor node is configured. */
  private def registerLsuTriggers(): Unit =
    synchronizerNodeService.nodes.successor.foreach { successorSynchronizerNode =>
      lsuConfig.foreach { lsu =>
        registerTrigger(
          new DedicatedLsuAnnouncementTrigger(
            triggerContext,
            synchronizerNodeService.nodes.current.sequencerAdminConnection,
            lsu,
          )
        )
      }
      registerTrigger(
        new DedicatedLsuTrigger(
          triggerContext,
          synchronizerNodeService.nodes,
          successorSynchronizerNode,
          lsuDumpPath.getOrElse(
            throw new IllegalArgumentException(
              "lsu-dump-path must be set when a successor synchronizer node is configured"
            )
          ),
        )
      )
      registerTrigger(
        new LsuTransferTrafficTrigger(
          triggerContext,
          synchronizerNodeService.nodes.current,
          successorSynchronizerNode,
        )
      )
    }
}

object SyncOperatorAutomationService extends AutomationServiceCompanion {
  import AutomationServiceCompanion.aTrigger

  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    Seq(
      aTrigger[ReconcileDedicatedSynchronizerParametersTrigger],
      aTrigger[UnlimitedTrafficTrigger],
      aTrigger[DedicatedLsuAnnouncementTrigger],
      aTrigger[DedicatedLsuTrigger],
      aTrigger[LsuTransferTrafficTrigger],
    )
}
