// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator

import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  FutureSupervisor,
}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.*
import com.digitalasset.canton.telemetry.ConfiguredOpenTelemetry
import com.digitalasset.canton.time.*
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.admin.http.AdminRoutes
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.config.SpliceDbConfig.withConfiguredPostgresConnectionSettings
import org.lfdecentralizedtrust.splice.environment.{NodeBootstrapBase, SpliceStorageFactory}
import org.lfdecentralizedtrust.splice.store.db.SpliceDbLockCounters
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorAppBackendConfig
import org.lfdecentralizedtrust.splice.syncoperator.metrics.SyncOperatorAppMetrics

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Sync Operator Node apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SyncOperatorAppBootstrap(
    override val name: InstanceName,
    val config: SyncOperatorAppBackendConfig,
    val syncOperatorAppParameters: SharedSpliceAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    override val metrics: SyncOperatorAppMetrics,
    storageFactory: StorageFactory,
    loggerFactory: NamedLoggerFactory,
    futureSupervisor: FutureSupervisor,
    configuredOpenTelemetry: ConfiguredOpenTelemetry,
)(implicit
    executionContext: ExecutionContextIdlenessExecutorService,
    scheduler: ScheduledExecutorService,
    actorSystem: ActorSystem,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends NodeBootstrapBase[
      SyncOperatorApp,
      SyncOperatorAppBackendConfig,
      SharedSpliceAppParameters,
    ](
      config,
      name,
      syncOperatorAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      configuredOpenTelemetry,
    ) {

  override def initialize(adminRoutes: AdminRoutes): EitherT[Future, String, Unit] = {
    // No HTTP surface yet; operator-facing endpoints attach here.
    val _ = adminRoutes
    startInstanceUnlessClosing {
      new SyncOperatorApp(
        name,
        config,
        syncOperatorAppParameters,
        storage,
        clock,
        loggerFactory,
        tracerProvider,
        futureSupervisor,
        metrics,
      )
    }
  }

  override def isActive: Boolean = storage.isActive
}

object SyncOperatorAppBootstrap {
  val LoggerFactoryKeyName: String = "syncoperator"

  def apply(
      name: String,
      syncOperatorConfig: SyncOperatorAppBackendConfig,
      syncOperatorAppParameters: SharedSpliceAppParameters,
      clock: Clock,
      syncOperatorMetrics: SyncOperatorAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SyncOperatorAppBootstrap] =
    SpliceStorageFactory.createWithDeferredClose(
      storage = withConfiguredPostgresConnectionSettings(
        syncOperatorConfig.storage,
        syncOperatorConfig.postgres,
      ),
      instanceLockEnabled = syncOperatorConfig.instanceLockEnabled,
      mainLockCounter = SpliceDbLockCounters.SYNC_OPERATOR_WRITE,
      poolLockCounter = SpliceDbLockCounters.SYNC_OPERATOR_WRITERS,
      exitOnFatalFailures = syncOperatorAppParameters.exitOnFatalFailures,
      futureSupervisor = futureSupervisor,
      loggerFactory = loggerFactory,
    ) { storageFactory =>
      InstanceName
        .create(name)
        .map { instanceName =>
          new SyncOperatorAppBootstrap(
            instanceName,
            syncOperatorConfig,
            syncOperatorAppParameters,
            testingConfigInternal,
            clock,
            syncOperatorMetrics,
            storageFactory,
            loggerFactory,
            futureSupervisor,
            configuredOpenTelemetry,
          )
        }
        .leftMap(_.toString)
    }
}
