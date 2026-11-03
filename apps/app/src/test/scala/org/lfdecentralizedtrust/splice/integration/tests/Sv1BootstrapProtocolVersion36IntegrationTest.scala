package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.FullClientConfig
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.TopologyMapping
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.auth.{AuthTokenSourceStatic, AuthUtil}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
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
import scala.jdk.CollectionConverters.*

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
      .simpleTopology4Svs(this.getClass.getSimpleName)
      // The standard pre-setup depends on the shared Canton nodes.
      .withPreSetup(_ => ())
      .addConfigTransformsToFront((_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf))
      .withSequencerConnectionsFromScanDisabled(22_000)
      // Add a suffix to the canton identifiers to avoid metric conflicts with the shared canton nodes
      .withCantonNodeNameSuffix("StandaloneSv1BootstrapPv36")
      .withProtocolVersion(protocolVersion)
      .withTrafficTopupsDisabled
      .withManualStart

  "sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins" in { implicit env =>
    import env.executionContext
    def participantConnection(sv: SvAppBackendReference) =
      new ParticipantAdminConnection(
        FullClientConfig(port = sv.config.participantClient.adminApi.clientConfig.port),
        env.environment.config.monitoring.logging.api,
        loggerFactory.append("participant", sv.name),
        grpcClientMetrics,
        retryProvider,
        tokenProvider = AuthTokenSourceStatic(
          AuthUtil.CantonAdminApi.participantAdminApiTestToken
        ),
      )
    def sequencerConnection(sv: SvAppBackendReference) =
      new SequencerAdminConnection(
        FullClientConfig(port = sv.config.localSynchronizerNodes.current.sequencer.adminApi.port),
        env.environment.config.monitoring.logging.api,
        loggerFactory.append("sequencer", sv.name),
        grpcClientMetrics,
        retryProvider,
      )
    def mediatorConnection(sv: SvAppBackendReference) =
      new MediatorAdminConnection(
        FullClientConfig(port = sv.config.localSynchronizerNodes.current.mediator.adminApi.port),
        env.environment.config.monitoring.logging.api,
        loggerFactory.append("mediator", sv.name),
        grpcClientMetrics,
        retryProvider,
      )
    val svs = Seq(sv1Backend, sv2Backend)
    val synchronizerNodeConnections: Seq[TopologyAdminConnection] =
      svs.flatMap(sv => Seq(sequencerConnection(sv), mediatorConnection(sv)))
    val topologyConnections: Seq[TopologyAdminConnection] =
      svs.map(participantConnection) ++ synchronizerNodeConnections

    withCantonSvNodes(
      (
        Some(sv1Backend),
        Some(sv2Backend),
        // sv3 is not used but its participant config requires an admin user.
        Some(sv3Backend),
        None,
      ),
      logSuffix = "sv1-bootstrap-pv36",
      sv4 = false,
    )() {
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
        startAllSync(sv1Nodes*)
      }

      clue(s"The synchronizer runs protocol version $protocolVersion") {
        val connected = sv1Backend.participantClientWithAdminToken.synchronizers
          .list_connected()
          .loneElement
        connected.physicalSynchronizerId.protocolVersion shouldBe protocolVersion
        sv1ScanBackend.getDsoPartyId() shouldBe sv1Backend.getDsoInfo().dsoParty
      }

      clue("sv2 joins after sv1") {
        startAllSync(sv2Nodes*)
        sv2Backend.participantClientWithAdminToken.synchronizers
          .list_connected()
          .loneElement
          .physicalSynchronizerId
          .protocolVersion shouldBe protocolVersion
        sv1Backend.getDsoInfo().dsoRules.payload.svs.keySet.asScala shouldBe Set(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          sv2Backend.getDsoInfo().svParty.toProtoPrimitive,
        )
      }

      clue("Sequencers and mediators have no OwnerToKeyMapping in their authorized store") {
        synchronizerNodeConnections.foreach { connection =>
          connection
            .listAllTransactions(
              TopologyStoreId.Authorized,
              includeMappings = Set(TopologyMapping.Code.OwnerToKeyMapping),
            )
            .futureValue shouldBe empty
        }
      }
    }
  }
}
