// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell

import org.apache.pekko.actor.ActorSystem
import cats.data.EitherT
import cats.syntax.either.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.admin.http.AdminRoutes
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.config.SpliceDbConfig.withConfiguredPostgresConnectionSettings
import org.lfdecentralizedtrust.splice.environment.{NodeBootstrapBase, SpliceStorageFactory}
import org.lfdecentralizedtrust.splice.store.db.SpliceDbLockCounters
import org.lfdecentralizedtrust.splice.splitwell.config.SplitwellAppBackendConfig
import org.lfdecentralizedtrust.splice.splitwell.metrics.SplitwellAppMetrics
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

/** Class used to orchester the starting/initialization of Splitwell apps.
  *
  * Modelled after Canton's ParticipantNodeBootstrap class.
  */
class SplitwellAppBootstrap(
    override val name: InstanceName,
    val config: SplitwellAppBackendConfig,
    val splitwellAppParameters: SharedSpliceAppParameters,
    val testingConfig: TestingConfigInternal,
    clock: Clock,
    override val metrics: SplitwellAppMetrics,
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
      SplitwellApp,
      SplitwellAppBackendConfig,
      SharedSpliceAppParameters,
    ](
      config,
      name,
      splitwellAppParameters,
      clock,
      metrics,
      storageFactory,
      loggerFactory,
      configuredOpenTelemetry,
    ) {
  override def initialize(adminRoutes: AdminRoutes): EitherT[Future, String, Unit] =
    startInstanceUnlessClosing {
      new SplitwellApp(
        name,
        config,
        splitwellAppParameters,
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

object SplitwellAppBootstrap {
  val LoggerFactoryKeyName: String = "splitwell"

  def apply(
      name: String,
      splitwellConfig: SplitwellAppBackendConfig,
      amuletAppParameters: SharedSpliceAppParameters,
      clock: Clock,
      splitwellMetrics: SplitwellAppMetrics,
      testingConfigInternal: TestingConfigInternal,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      configuredOpenTelemetry: ConfiguredOpenTelemetry,
  )(implicit
      executionContext: ExecutionContextIdlenessExecutorService,
      scheduler: ScheduledExecutorService,
      actorSystem: ActorSystem,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Either[String, SplitwellAppBootstrap] =
    SpliceStorageFactory.createWithDeferredClose(
      storage = withConfiguredPostgresConnectionSettings(
        splitwellConfig.storage,
        splitwellConfig.postgres,
      ),
      instanceLockEnabled = splitwellConfig.instanceLockEnabled,
      mainLockCounter = SpliceDbLockCounters.SPLITWELL_WRITE,
      poolLockCounter = SpliceDbLockCounters.SPLITWELL_WRITERS,
      exitOnFatalFailures = amuletAppParameters.exitOnFatalFailures,
      futureSupervisor = futureSupervisor,
      loggerFactory = loggerFactory,
    ) { storageFactory =>
      InstanceName
        .create(name)
        .map { instanceName =>
          new SplitwellAppBootstrap(
            instanceName,
            splitwellConfig,
            amuletAppParameters,
            testingConfigInternal,
            clock,
            splitwellMetrics,
            storageFactory,
            loggerFactory,
            futureSupervisor,
            configuredOpenTelemetry,
          )
        }
        .leftMap(_.toString)
    }
}
