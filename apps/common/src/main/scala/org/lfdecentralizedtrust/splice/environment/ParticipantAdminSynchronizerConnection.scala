// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.OptionT
import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands
import com.digitalasset.canton.admin.api.client.data.ListConnectedSynchronizersResult
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnectionValidation
import com.digitalasset.canton.topology.{
  ConfiguredPhysicalSynchronizerId,
  KnownPhysicalSynchronizerId,
  PhysicalSynchronizerId,
  SynchronizerId,
}
import com.github.blemale.scaffeine.Scaffeine
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.HasParticipantId

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait ParticipantAdminSynchronizerConnection {
  this: ParticipantAdminConnection & HasParticipantId =>

  private val synchronizerIdAliasCache =
    Scaffeine()
      .expireAfterWrite(1.minutes)
      .maximumSize(100)
      .buildAsync[SynchronizerAlias, SynchronizerId]()

  def listConnectedSynchronizers()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedSynchronizersResult]] = {
    runCmd(ParticipantAdminCommands.SynchronizerConnectivity.ListConnectedSynchronizers())
  }

  def getSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[SynchronizerId] = {
    synchronizerIdAliasCache.getFuture(
      synchronizerAlias,
      alias => getPhysicalSynchronizerId(alias).map(_.logical),
    )
  }

  def lookupPhysicalSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Option[PhysicalSynchronizerId]] =
    OptionT(for {
      configuredSynchronziers <- listRegisteredSynchronizers()
    } yield configuredSynchronziers.collectFirst {
      case (configuredSynchronizer, psid, _)
          if configuredSynchronizer.synchronizerAlias == synchronizerAlias =>
        psid.toOption
    }.flatten)
      .orElseF(
        runCmd(
          ParticipantAdminCommands.SynchronizerConnectivity.GetSynchronizerId(synchronizerAlias)
        ).map(Some(_)).recover {
          case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND => None
        }
      )
      .value

  def listRegisteredSynchronizers()(implicit
      tc: TraceContext
  ): Future[Seq[(SynchronizerConnectionConfig, ConfiguredPhysicalSynchronizerId, Boolean)]] = {
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ListRegisteredSynchronizers
    )
  }

  def getPhysicalSynchronizerId(synchronizerId: SynchronizerId)(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] = listRegisteredSynchronizers().map(
    _.collectFirst {
      case (_, KnownPhysicalSynchronizerId(psid), _) if psid.logical == synchronizerId => psid
    }.getOrElse(
      throw Status.NOT_FOUND
        .withDescription(
          s"No synchronizer registered and handshaked for id $synchronizerId"
        )
        .asRuntimeException()
    )
  )
  def getPhysicalSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] = lookupPhysicalSynchronizerId(synchronizerAlias).map(
    _.getOrElse(
      throw Status.NOT_FOUND
        .withDescription(
          s"No synchronizer registered and handshaked for $synchronizerAlias"
        )
        .asRuntimeException()
    )
  )

  def reconnectAllSynchronizers()(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ReconnectSynchronizers(ignoreFailures =
        false
      )
    )
  }

  def disconnectFromAllSynchronizers()(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    synchronizers <- listConnectedSynchronizers()
    _ <- Future.sequence(
      synchronizers.map(synchronizer =>
        runCmd(
          ParticipantAdminCommands.SynchronizerConnectivity.DisconnectSynchronizer(
            synchronizer.synchronizerAlias
          )
        )
      )
    )
  } yield ()

  def connectSynchronizer(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    retryProvider.retryForClientCalls(
      "connect_synchronizer",
      s"participant is connected to $alias",
      runCmd(
        ParticipantAdminCommands.SynchronizerConnectivity
          .ReconnectSynchronizer(alias, retry = false)
      ).map(isConnected =>
        if (!isConnected) {
          val msg = s"failed to connect to $alias"
          throw Status.Code.FAILED_PRECONDITION.toStatus.withDescription(msg).asRuntimeException()
        }
      ),
      logger,
    )

  private def disconnectSynchronizer(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(ParticipantAdminCommands.SynchronizerConnectivity.DisconnectSynchronizer(alias))

  def ensureSynchronizerRegisteredWithManualConnect(
      config: SynchronizerConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    require(
      config.manualConnect,
      "manualConnect must be true when trying to register only",
    )
    for {
      _ <- retryProvider
        .ensureThat(
          retryFor,
          "synchronizer_registered_no_handshake",
          s"participant registered ${config.synchronizerAlias}",
          lookupSynchronizerConnectionConfig(config.synchronizerAlias).map(_.toRight(())),
          (_: Unit) => registerSynchronizer(config),
          logger,
        )
    } yield ()
  }

  def ensureSynchronizerRegisteredAndConnected(
      config: SynchronizerConnectionConfig,
      overwriteExistingConnection: Boolean,
      reconnectOnSynchronizerConfigurationChange: Boolean,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = for {
    _ <- retryProvider
      .ensureThat(
        retryFor,
        "synchronizer_registered",
        s"participant registered ${config.synchronizerAlias} with config $config",
        lookupSynchronizerConnectionConfig(config.synchronizerAlias).map {
          case Some(_) if !overwriteExistingConnection => Right(())
          // We don't set the sequencer id when connecting but Canton returns it so we ignore it in the comparison here.
          case Some(existingConfig)
              if ParticipantAdminConnection.dropSequencerId(
                existingConfig
              ) == ParticipantAdminConnection.dropSequencerId(config) =>
            Right(())
          case Some(other) => Left(Some(other))
          case None => Left(None)
        },
        (existingConfig: Option[SynchronizerConnectionConfig]) =>
          existingConfig match {
            case None =>
              logger.info(s"Registering new synchronizer with config $config")
              registerSynchronizer(config)
            case Some(_) =>
              modifySynchronizerConnectionConfigAndReconnect(
                config.synchronizerAlias,
                reconnectOnSynchronizerConfigurationChange,
                _ => Some(config),
              )
                .map(_ => ())
          },
        logger,
      )
    _ <- connectSynchronizer(config.synchronizerAlias)
  } yield ()

  private def registerSynchronizer(config: SynchronizerConnectionConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.RegisterSynchronizer(
        config,
        performHandshake = false,
        SequencerConnectionValidation.ThresholdActive,
      )
    )

  private def reconnectSynchronizer(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    _ <- retryProvider.retryForClientCalls(
      "reconnect_synchronizer_disconnect",
      s"participant is disconnected from $alias",
      disconnectSynchronizer(alias),
      logger,
    )
    _ <- connectSynchronizer(alias)
  } yield ()

  def lookupSynchronizerConnectionConfig(
      synchronizerAlias: SynchronizerAlias
  )(implicit traceContext: TraceContext): Future[Option[SynchronizerConnectionConfig]] =
    for {
      registeredSynchronizers <- listRegisteredSynchronizers()
    } yield registeredSynchronizers
      .collectFirst {
        case (registeredSynchronizer, _, _)
            if registeredSynchronizer.synchronizerAlias == synchronizerAlias =>
          registeredSynchronizer
      }

  def getSynchronizerConnectionConfig(
      synchronizerAlias: SynchronizerAlias
  )(implicit traceContext: TraceContext): Future[SynchronizerConnectionConfig] =
    lookupSynchronizerConnectionConfig(synchronizerAlias).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"Synchronizer $synchronizerAlias is not configured on the participant")
          .asRuntimeException()
      )
    )

  def modifySynchronizerConnectionConfig(
      synchronizer: SynchronizerAlias,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    retryProvider.retryForClientCalls(
      "modify_synchronizer_connection",
      "Set the new synchronizer connection if required",
      for {
        oldConfig <- getSynchronizerConnectionConfig(synchronizer)
        newConfig = f(oldConfig)
        configModified <- newConfig match {
          case None =>
            logger.trace("No update to synchronizer connection config required")
            Future.successful(false)
          case Some(config) =>
            if (
              oldConfig.synchronizerId
                .exists(oldPsid => config.synchronizerId.exists(psid => psid != oldPsid))
            ) {
              Future.failed(
                Status.INVALID_ARGUMENT
                  .withDescription(
                    s"New config physical synchronizer id ${config.synchronizerId} cannot be different from the old one ${oldConfig.synchronizerId} for synchronizer $synchronizer"
                  )
                  .asRuntimeException()
              )
            } else {

              logger.info(
                s"Updating to new synchronizer connection config for synchronizer $synchronizer. Old config: $oldConfig, new config: $config"
              )
              for {
                _ <- setSynchronizerConnectionConfig(config)
              } yield true
            }
        }
      } yield configModified,
      logger,
    )
  }

  private def modifyOrRegisterSynchronizerConnectionConfig(
      config: SynchronizerConnectionConfig,
      reconnectOnSynchronizerConfigurationChange: Boolean,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      configO <- lookupSynchronizerConnectionConfig(config.synchronizerAlias)
      needsReconnect <- configO match {
        case Some(config) =>
          modifySynchronizerConnectionConfig(
            config.synchronizerAlias,
            f,
          )
        case None =>
          logger.info(s"Synchronizer ${config.synchronizerAlias} is new, registering")
          ensureSynchronizerRegisteredAndConnected(
            config,
            overwriteExistingConnection = true,
            reconnectOnSynchronizerConfigurationChange = reconnectOnSynchronizerConfigurationChange,
            retryFor = retryFor,
          ).map(_ => false)
      }
    } yield needsReconnect

  def modifySynchronizerConnectionConfigAndReconnect(
      synchronizerAlias: SynchronizerAlias,
      reconnectOnSynchronizerConfigurationChange: Boolean,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      configModified <- modifySynchronizerConnectionConfig(synchronizerAlias, f)
      _ <-
        if (configModified && reconnectOnSynchronizerConfigurationChange) {
          logger.info(
            s"reconnect to the synchronizer $synchronizerAlias for new sequencer configuration to take effect"
          )
          reconnectSynchronizer(synchronizerAlias)
        } else Future.unit
    } yield ()

  def modifyOrRegisterSynchronizerConnectionConfigAndReconnect(
      config: SynchronizerConnectionConfig,
      reconnectOnSynchronizerConfigurationChange: Boolean,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      configModified <- modifyOrRegisterSynchronizerConnectionConfig(
        config,
        reconnectOnSynchronizerConfigurationChange,
        f,
        retryFor,
      )
      _ <-
        if (configModified && reconnectOnSynchronizerConfigurationChange) {
          logger.info(
            s"reconnect to the synchronizer ${config.synchronizerAlias} for new sequencer configuration to take effect"
          )
          reconnectSynchronizer(config.synchronizerAlias)
        } else Future.unit
    } yield ()

  private def setSynchronizerConnectionConfig(config: SynchronizerConnectionConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ModifySynchronizerConnection(
        None,
        config,
        SequencerConnectionValidation.ThresholdActive,
      )
    )
}
