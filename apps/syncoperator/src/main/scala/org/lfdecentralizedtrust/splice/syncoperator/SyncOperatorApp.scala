// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator

import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PhysicalSynchronizerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  RetryFor,
  SequencerAdminConnection,
  SpliceNodeBase,
  SynchronizerNode,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.syncoperator.automation.SyncOperatorAutomationService
import org.lfdecentralizedtrust.splice.syncoperator.config.{
  SyncOperatorAppBackendConfig,
  SyncOperatorSynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.syncoperator.metrics.SyncOperatorAppMetrics
import org.lfdecentralizedtrust.splice.util.HasHealth

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a sync operator app instance.
  *
  * Runs the automation for the synchronizer it operates.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SyncOperatorApp(
    override val name: InstanceName,
    val config: SyncOperatorAppBackendConfig,
    val appParameters: SharedSpliceAppParameters,
    storage: DbStorage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    futureSupervisor: FutureSupervisor,
    metrics: SyncOperatorAppMetrics,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends SpliceNodeBase[SyncOperatorApp.State](
      appParameters,
      loggerFactory,
      futureSupervisor,
      metrics,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override protected def initializeApp()(implicit
      traceContext: TraceContext
  ): Future[SyncOperatorApp.State] = {
    val synchronizerNodes = SynchronizerNode.LocalSynchronizerNodes(
      current = newSynchronizerNode(config.synchronizerNodes.current),
      successor = config.synchronizerNodes.successor.map(newSynchronizerNode),
      legacy = None,
      additionalLegacy = Seq.empty,
    )
    val synchronizerNodeService = new SynchronizerNodeService(
      synchronizerNodes,
      config.parameters.spliceCachingConfigs.physicalSynchronizerExpiration,
      retryProvider,
      loggerFactory,
    )
    val sequencerAdminConnection = synchronizerNodes.current.sequencerAdminConnection
    for {
      physicalSynchronizerId <- appInitStep("Get the synchronizer id from the sequencer") {
        servedSynchronizerId(sequencerAdminConnection)
      }
      synchronizerId = physicalSynchronizerId.logical
      _ <- appInitStep("Check the configured synchronizer has not already been upgraded") {
        requireCurrentNodeIsLive(synchronizerNodes)
      }
      _ <- appInitStep("Check the synchronizer runs protocol version 37 or later") {
        requireProtocolVersion(physicalSynchronizerId)
      }
      _ <- appInitStep("Check the synchronizer is permissioned and runs traffic control") {
        requireDedicatedSynchronizerParameters(sequencerAdminConnection, synchronizerId)
      }
      automation = new SyncOperatorAutomationService(
        config.automation,
        clock,
        retryProvider,
        synchronizerId,
        synchronizerNodeService,
        config.lsu,
        config.lsuDumpPath,
        config.trafficControl,
        config.trafficBalanceReconciliationDelay,
        loggerFactory,
      )
    } yield {
      SyncOperatorApp.State(
        automation,
        storage,
        synchronizerId,
        synchronizerNodes,
        loggerFactory.getTracedLogger(SyncOperatorApp.State.getClass),
        timeouts,
      )
    }
  }

  private def newSynchronizerNode(
      nodeConfig: SyncOperatorSynchronizerNodeConfig
  ): SyncOperatorSynchronizerNode =
    new SyncOperatorSynchronizerNode(
      new SequencerAdminConnection(
        nodeConfig.sequencer.adminApi,
        appParameters.loggingConfig.api,
        loggerFactory,
        metrics.grpcClientMetrics,
        retryProvider,
      ),
      nodeConfig.mediator.map(mediator =>
        new MediatorAdminConnection(
          mediator.adminApi,
          appParameters.loggingConfig.api,
          loggerFactory,
          metrics.grpcClientMetrics,
          retryProvider,
        )
      ),
      nodeConfig,
      loggerFactory.getTracedLogger(classOf[SyncOperatorSynchronizerNode]),
    )

  /** An upgraded node still reports the same logical synchronizer id, so without a successor to
    * switch to this app would grant traffic on a synchronizer nobody is connected to. The
    * announcement alone is enough to refuse: it is only ever published with a successor configured.
    */
  private def requireCurrentNodeIsLive(
      nodes: SynchronizerNode.LocalSynchronizerNodes[SyncOperatorSynchronizerNode]
  )(implicit traceContext: TraceContext): Future[Unit] =
    if (nodes.successor.isDefined) Future.unit
    else
      for {
        psid <- nodes.current.sequencerAdminConnection.getPhysicalSynchronizerId()
        announcements <- nodes.current.sequencerAdminConnection
          .listLsuAnnouncements(psid.logical)
        superseded = announcements.filter(
          _.mapping.successorSynchronizerId.serial > psid.serial
        )
        _ <- superseded.headOption.fold(Future.unit) { announcement =>
          Future.failed(
            Status.FAILED_PRECONDITION
              .withDescription(
                s"The configured synchronizer node is at $psid but is upgraded to " +
                  s"${announcement.mapping.successorSynchronizerId} at " +
                  s"${announcement.mapping.upgradeTime}. Set synchronizer-nodes.successor, or " +
                  "point synchronizer-nodes.current at the successor once the upgrade has landed."
              )
              .asRuntimeException()
          )
        }
      } yield ()

  /** The app sets the traffic limit of participants before they join, which the sequencer only
    * accepts from protocol version 37.
    */
  private def requireProtocolVersion(synchronizerId: PhysicalSynchronizerId): Future[Unit] =
    if (synchronizerId.protocolVersion >= ProtocolVersion.v37) Future.unit
    else
      Future.failed(
        Status.FAILED_PRECONDITION
          .withDescription(
            s"Synchronizer ${synchronizerId.logical} runs protocol version " +
              s"${synchronizerId.protocolVersion}. The sync operator needs protocol version 37 " +
              "or later."
          )
          .asRuntimeException()
      )

  /** The app grants unlimited traffic to every admitted participant, so the synchronizer must only
    * admit participants the operator permits. It adjusts traffic control but never turns it on,
    * which would strand members that have no traffic yet. Waits, since the synchronizer's
    * bootstrap may set both after the sequencer is up.
    */
  private def requireDedicatedSynchronizerParameters(
      sequencerAdminConnection: SequencerAdminConnection,
      synchronizerId: SynchronizerId,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "sync_operator_synchronizer_parameters",
      s"synchronizer $synchronizerId is permissioned and runs traffic control",
      sequencerAdminConnection
        .getSynchronizerParametersState(synchronizerId)
        .map(_.mapping.parameters)
        .flatMap { parameters =>
          val problems = Seq(
            Option.when(!parameters.onboardingRestriction.isRestricted)(
              s"Synchronizer $synchronizerId is not permissioned. Set its onboarding restriction " +
                "to RestrictedOpen or RestrictedLocked before starting the sync operator."
            ),
            Option.when(parameters.trafficControl.isEmpty)(
              s"Synchronizer $synchronizerId does not run traffic control. Enable it on the " +
                "synchronizer before starting the sync operator."
            ),
          ).flatten
          if (problems.isEmpty) Future.unit
          else
            Future.failed(
              Status.FAILED_PRECONDITION
                .withDescription(problems.mkString(" "))
                .asRuntimeException()
            )
        },
      logger,
    )

  /** The synchronizer the configured sequencer serves. Waits while it is still initializing. */
  private def servedSynchronizerId(
      sequencerAdminConnection: SequencerAdminConnection
  )(implicit traceContext: TraceContext): Future[PhysicalSynchronizerId] =
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "sync_operator_served_synchronizer_id",
      "the sequencer reports the synchronizer it serves",
      sequencerAdminConnection.getStatus.map(
        _.successOption
          .map(_.synchronizerId)
          .getOrElse(
            throw Status.UNAVAILABLE
              .withDescription("Sequencer is not yet initialized")
              .asRuntimeException()
          )
      ),
      logger,
    )

  protected[this] override def automationServices(st: SyncOperatorApp.State) =
    Seq(st.automation)
}

object SyncOperatorApp {
  case class State(
      automation: SyncOperatorAutomationService,
      storage: DbStorage,
      synchronizerId: SynchronizerId,
      synchronizerNodes: SynchronizerNode.LocalSynchronizerNodes[SyncOperatorSynchronizerNode],
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  ) extends AutoCloseable
      with HasHealth {

    /** The configured current node's sequencer. Automation follows the successor across an
      * upgrade, see `SynchronizerNodeService`.
      */
    def sequencerAdminConnection: SequencerAdminConnection =
      synchronizerNodes.current.sequencerAdminConnection

    override def isHealthy: Boolean = storage.isActive

    override def close(): Unit = {
      val instances =
        Seq[AutoCloseable](automation, storage, synchronizerNodes.current) ++
          synchronizerNodes.successor.toList
      LifeCycle.close(instances*)(logger)
    }
  }
}
