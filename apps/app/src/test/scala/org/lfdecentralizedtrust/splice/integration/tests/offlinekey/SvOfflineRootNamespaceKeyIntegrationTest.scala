package org.lfdecentralizedtrust.splice.integration.tests.offlinekey

import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{ScanAppBackendReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}

class SvOfflineRootNamespaceKeyIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with ProcessTestUtil
    with StandaloneCanton
    with WalletTestUtil
    with OfflineRootNamespaceKeyUtil {

  override def usesDbs: Seq[String] =
    super[StandaloneCanton].usesDbs ++ super[OfflineRootNamespaceKeyUtil].usesDbs

  override def dbsSuffix: String = "offline_root_key"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  private val cantonNameSuffix: String = getClass.getSimpleName

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(cantonNameSuffix)
      // we start the participants during the test so we cannot pre-allocate
      .withPreSetup(_ => ())
      .addConfigTransforms((_, config) =>
        // use a fresh participant to ensure a fresh deployment so we can validate the topology state
        ConfigTransforms.bumpCantonPortsBy(22_000)(config)
      )
      // Add a suffix to the canton identifiers to avoid metric conflicts with the shared canton nodes
      .withCantonNodeNameSuffix(cantonNameSuffix)
      .withTrafficTopupsDisabled
      .withManualStart

  "start svs with offline root namespace keys" in { implicit env =>
    withCantonSvNodes(
      (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), None),
      logSuffix = "offline-root-keys",
      sv4 = false,
      // the identities are built from a root key held offline, so the nodes
      // must stay uninitialized until the test sets them up
      autoInitParticipants = Set.empty,
    )() {
      initSvNodesWithOfflineRootNamespaceKey(sv1Backend, sv1ScanBackend)
      initSvNodesWithOfflineRootNamespaceKey(sv2Backend, sv2ScanBackend)
    }
  }

  private def initSvNodesWithOfflineRootNamespaceKey(
      backend: SvAppBackendReference,
      scan: ScanAppBackendReference,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val participantClient = backend.participantClientWithAdminToken
    val sequencerClient = backend.sequencerClient
    val mediatorClient = backend.mediatorClient
    val protocolVersion = synchronizerProtocolVersion
    initializeInstanceWithOfflineRootNamespaceKey(
      s"${backend.name}$cantonNameSuffix",
      participantClient,
      protocolVersion,
    )
    initializeInstanceWithOfflineRootNamespaceKey(
      s"${backend.name}$cantonNameSuffix",
      sequencerClient,
      protocolVersion,
    )
    initializeInstanceWithOfflineRootNamespaceKey(
      s"${backend.name}$cantonNameSuffix",
      mediatorClient,
      protocolVersion,
    )
    scan.start()
    backend.startSync()
    scan.waitForInitialization()
    instanceHasNoRootNamespaceKey(participantClient)
    instanceHasNoRootNamespaceKey(sequencerClient)
    instanceHasNoRootNamespaceKey(mediatorClient)
  }

  private def synchronizerProtocolVersion(implicit
      env: SpliceTestConsoleEnvironment
  ): ProtocolVersion =
    sv1Backend.config.onboarding match {
      case Some(foundDso: SvOnboardingConfig.FoundDso) => foundDso.initialProtocolVersion
      case Some(_: SvOnboardingConfig.JoinWithKey) | Some(_: SvOnboardingConfig.RollForwardLsu) |
          None =>
        fail("Failed to retrieve initialProtocolVersion from sv1.")
    }
}
