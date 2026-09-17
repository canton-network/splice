package org.lfdecentralizedtrust.splice.integration.plugins

import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.console.{ParticipantClientReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.plugins.ResetTopologyStatePlugin.{
  TopologyStateNotReset,
  TopologyStateResetFailed,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.SynchronizerId
import io.grpc
import io.grpc.StatusRuntimeException

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

abstract class ResetTopologyStatePlugin extends SpliceEnvironmentSetupPlugin with BaseTest {

  private val MAX_RETRIES = 15

  protected def resetTopologyState(
      env: SpliceTests.SpliceTestConsoleEnvironment,
      synchronizerId: SynchronizerId,
      sv1: SvAppBackendReference,
  ): Unit

  protected def topologyType: String

  private val resetFailure = new AtomicReference[Option[Throwable]](None)

  override def beforeEnvironmentCreated(config: SpliceConfig): SpliceConfig = {
    ResetTopologyStatePlugin.notResetTopologyType.get().foreach { notReset =>
      throw TopologyStateNotReset(notReset)
    }
    config
  }

  override def beforeEnvironmentDestroyed(
      env: SpliceTests.SpliceTestConsoleEnvironment
  ): Unit = {

    // Stop all nodes to stop them from submitting topology TXs.
    env.stopAll()

    try {
      attemptToResetTopologyState(env)
    } catch {
      case NonFatal(e) =>
        logger.error(s"Resetting $topologyType failed, giving up", e)
        resetFailure.set(Some(e))
        ResetTopologyStatePlugin.notResetTopologyType.compareAndSet(None, Some(topologyType))
    }
  }

  override def afterEnvironmentDestroyed(config: SpliceConfig): Unit =
    resetFailure.getAndSet(None).foreach { e =>
      throw TopologyStateResetFailed(topologyType, e)
    }

  private def attemptToResetTopologyState(env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    val sv1 = env.svs.local.find(_.name == "sv1").value
    val allSvs = env.svs.local
      .filterNot(_.name.endsWith("Local"))
      .filterNot(_.name.endsWith("Onboarded"))
    val globalDomainAlias = sv1.config.domains.global.alias

    def connectedGlobalSync(client: ParticipantClientReference) = {
      client.synchronizers
        .list_connected()
        .find(_.synchronizerAlias == globalDomainAlias)
    }

    def isSynchronizerRegistered(client: ParticipantClientReference) = {
      client.synchronizers
        .list_registered()
        .exists(_._1.synchronizerAlias == globalDomainAlias)
    }

    allSvs.foreach(backend => {
      if (isSynchronizerRegistered(backend.participantClientWithAdminToken)) {
        if (connectedGlobalSync(backend.participantClientWithAdminToken).isEmpty) {
          // reconnect just in case the participant was left disconnected after the test
          backend.participantClientWithAdminToken.synchronizers.reconnect(
            globalDomainAlias
          )
        }
        eventually() {
          connectedGlobalSync(backend.participantClientWithAdminToken)
            .getOrElse(
              fail(s"${backend.name} not connected to the global sync")
            )
        }
      }

    })

    def resetTopologyStateRetries(retries: Int): Unit = {
      if (retries > MAX_RETRIES) {
        throw new IllegalStateException(
          s"Exceeded max retries for resetting $topologyType: $MAX_RETRIES"
        )
      }
      try {
        resetTopologyState(
          env,
          sv1.participantClientWithAdminToken.synchronizers.id_of(globalDomainAlias),
          sv1,
        )
      } catch {
        case _: CommandFailure =>
          logger.info(
            s"Restarting $topologyType reset as command failed likely because base serial has changed"
          )
          resetTopologyStateRetries(retries + 1)
        case s: StatusRuntimeException
            if s.getStatus.getCode == grpc.Status.Code.INVALID_ARGUMENT =>
          logger.info(
            s"Restarting $topologyType reset as base serial has changed"
          )
          resetTopologyStateRetries(retries + 1)
      }
    }
    resetTopologyStateRetries(0)
    logger.info(s"$topologyType has been reset")
  }
}

object ResetTopologyStatePlugin {

  private val notResetTopologyType = new AtomicReference[Option[String]](None)

  final case class TopologyStateResetFailed(topologyType: String, cause: Throwable)
      extends RuntimeException(
        s"The $topologyType could not be reset after the test suite; the shared Canton topology state is left modified",
        cause,
      )

  final case class TopologyStateNotReset(topologyType: String)
      extends RuntimeException(
        s"Not creating a new environment: the $topologyType could not be reset after an earlier test suite in this JVM, see the preceding 'Resetting $topologyType failed' error"
      )
}
