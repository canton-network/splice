package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.FullClientConfig
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.IsTheCantonSequencerBFTEnabled
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  SequencerAdminConnection,
  TopologyAdminConnection,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton}

import scala.concurrent.duration.DurationInt

class Sv1BootstrapProtocolVersion36IntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with ProcessTestUtil
    with StandaloneCanton {

  override def dbsSuffix: String = "sv1_bootstrap_pv36"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  private val protocolVersion = ProtocolVersion.v36

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      // Do not use `simpleTopology1Sv` as its setup depends on the shared Canton nodes.
      .fromResources(Seq("simple-topology-1sv.conf"), this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf))
      // Add a suffix to the canton identifiers to avoid metric conflicts with the shared canton nodes
      .withCantonNodeNameSuffix("StandaloneSv1BootstrapPv36")
      .withProtocolVersion(protocolVersion)
      .withTrafficTopupsDisabled
      .withManualStart

  "sv1 bootstraps a fresh synchronizer with protocol version 36" in { implicit env =>
    import env.executionContext
    val svConfig = sv1Backend.config
    val synchronizerNodes = svConfig.localSynchronizerNodes.current
    val topologyConnections: Seq[TopologyAdminConnection] = Seq(
      new ParticipantAdminConnection(
        FullClientConfig(port = svConfig.participantClient.adminApi.port),
        env.environment.config.monitoring.logging.api,
        loggerFactory.append("participant", "sv1"),
        grpcClientMetrics,
        retryProvider,
      ),
      new SequencerAdminConnection(
        FullClientConfig(port = synchronizerNodes.sequencer.adminApi.port),
        env.environment.config.monitoring.logging.api,
        loggerFactory.append("sequencer", "sv1"),
        grpcClientMetrics,
        retryProvider,
      ),
      new MediatorAdminConnection(
        FullClientConfig(port = synchronizerNodes.mediator.adminApi.port),
        env.environment.config.monitoring.logging.api,
        loggerFactory.append("mediator", "sv1"),
        grpcClientMetrics,
        retryProvider,
      ),
    )

    withCanton(
      Seq(testResourcesPath / "standalone-sv1-nodes.conf") ++
        Option
          .when(IsTheCantonSequencerBFTEnabled)(
            testResourcesPath / "standalone-sequencer-sv1-extra-enable-bft.conf"
          )
          .toList,
      Seq(ProcessTestUtil.enableRepairCommandsConfig),
      "sv1-bootstrap-pv36",
      "SV1_ADMIN_USER" -> svConfig.ledgerApiUser,
      "SV1_PARTICIPANT_DB" -> s"participant_sv1_$dbsSuffix",
      "SV1_SEQUENCER_DB" -> s"sequencer_sv1_$dbsSuffix",
      "SV1_SEQUENCER_DB_BFT" -> s"sequencer_sv1_${dbsSuffix}_bft",
      "SV1_MEDIATOR_DB" -> s"mediator_sv1_$dbsSuffix",
      "SEQUENCER_DRIVER_DB" -> s"sequencer_driver_$dbsSuffix",
    ) {
      clue("Canton nodes are running but have no identity") {
        topologyConnections.foreach { connection =>
          // Long timeout because Canton is slow to start up
          eventually(timeUntilSuccess = 60.seconds) {
            val idResult = connection.getIdOption().futureValue
            idResult.initialized shouldBe false
            idResult.uniqueIdentifier shouldBe None
          }
        }
      }

      clue("Start sv1 and its scan and validator") {
        startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)
      }

      clue(s"The synchronizer runs protocol version $protocolVersion") {
        val connected = sv1Backend.participantClientWithAdminToken.synchronizers
          .list_connected()
          .loneElement
        connected.physicalSynchronizerId.protocolVersion shouldBe protocolVersion
        sv1ScanBackend.getDsoPartyId() shouldBe sv1Backend.getDsoInfo().dsoParty
      }
    }
  }
}
