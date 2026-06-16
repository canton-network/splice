// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import org.apache.pekko.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.admin.http.AdminRoutes
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.config.SpliceDbConfig.withConfiguredPostgresConnectionSettings
import org.lfdecentralizedtrust.splice.environment.{NodeBootstrapBase, SpliceStorageFactory}
import org.lfdecentralizedtrust.splice.store.db.SpliceDbLockCounters
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.metrics.SvAppMetrics
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

/** Class used to orchester the starting/initialization of an SV app.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SvAppBootstrap(
    override val name: InstanceName,
    val config: SvAppBackendConfig,
    val svAppParameters: SharedSpliceAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    override val metrics: SvAppMetrics,
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
      SvApp,
      SvAppBackendConfig,
      SharedSpliceAppParameters,
    ](
      config,
      name,
      svAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      configuredOpenTelemetry,
    ) {

  override def initialize(adminRoutes: AdminRoutes): EitherT[Future, String, Unit] =
    startInstanceUnlessClosing {
      new SvApp(
        name,
        config,
        svAppParameters,
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

object SvAppBootstrap {
  val LoggerFactoryKeyName: String = "SV"

  def apply(
      name: String,
      svConfig: SvAppBackendConfig,
      svAppParameters: SharedSpliceAppParameters,
      clock: Clock,
      svMetrics: SvAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SvAppBootstrap] =
    SpliceStorageFactory.createWithDeferredClose(
      storage = withConfiguredPostgresConnectionSettings(svConfig.storage, svConfig.postgres),
      instanceLockEnabled = svConfig.instanceLockEnabled,
      mainLockCounter = SpliceDbLockCounters.SV_WRITE,
      poolLockCounter = SpliceDbLockCounters.SV_WRITERS,
      exitOnFatalFailures = svAppParameters.exitOnFatalFailures,
      futureSupervisor = futureSupervisor,
      loggerFactory = loggerFactory,
    ) { storageFactory =>
      InstanceName
        .create(name)
        .map { instanceName =>
          new SvAppBootstrap(
            instanceName,
            svConfig,
            svAppParameters,
            testingConfigInternal,
            clock,
            svMetrics,
            storageFactory,
            loggerFactory,
            futureSupervisor,
            configuredOpenTelemetry,
          )
        }
        .leftMap(_.toString)
    }
}
