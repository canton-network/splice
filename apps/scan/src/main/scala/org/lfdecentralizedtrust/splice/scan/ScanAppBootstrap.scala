// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan

import org.apache.pekko.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.admin.http.AdminRoutes
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.config.SpliceDbConfig.withConfiguredPostgresConnectionSettings
import org.lfdecentralizedtrust.splice.environment.{NodeBootstrapBase, SpliceStorageFactory}
import org.lfdecentralizedtrust.splice.store.db.SpliceDbLockCounters
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.metrics.ScanAppMetrics
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

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.Future

/** Class used to orchester the starting/initialization of Scan apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class ScanAppBootstrap(
    override val name: InstanceName,
    val config: ScanAppBackendConfig,
    val scanAppParameters: SharedSpliceAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    override val metrics: ScanAppMetrics,
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
      ScanApp,
      ScanAppBackendConfig,
      SharedSpliceAppParameters,
    ](
      config,
      name,
      scanAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      configuredOpenTelemetry,
    ) {

  override def initialize(adminRoutes: AdminRoutes): EitherT[Future, String, Unit] =
    startInstanceUnlessClosing {
      new ScanApp(
        name,
        config,
        scanAppParameters,
        storage,
        clock,
        loggerFactory,
        tracerProvider,
        futureSupervisor,
        metrics,
        adminRoutes,
      )
    }

  override def isActive: Boolean = storage.isActive
}

object ScanAppBootstrap {
  val LoggerFactoryKeyName: String = "scan"

  def apply(
      name: String,
      scanConfig: ScanAppBackendConfig,
      amuletAppParameters: SharedSpliceAppParameters,
      clock: Clock,
      scanMetrics: ScanAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, ScanAppBootstrap] =
    SpliceStorageFactory.createWithDeferredClose(
      storage = withConfiguredPostgresConnectionSettings(scanConfig.storage, scanConfig.postgres),
      instanceLockEnabled = scanConfig.instanceLockEnabled,
      mainLockCounter = SpliceDbLockCounters.SCAN_WRITE,
      poolLockCounter = SpliceDbLockCounters.SCAN_WRITERS,
      exitOnFatalFailures = amuletAppParameters.exitOnFatalFailures,
      futureSupervisor = futureSupervisor,
      loggerFactory = loggerFactory,
    ) { storageFactory =>
      InstanceName
        .create(name)
        .map { instanceName =>
          new ScanAppBootstrap(
            instanceName,
            scanConfig,
            amuletAppParameters,
            testingConfigInternal,
            clock,
            scanMetrics,
            storageFactory,
            loggerFactory,
            futureSupervisor,
            configuredOpenTelemetry,
          )
        }
        .leftMap(_.toString)
    }
}
