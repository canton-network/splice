package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.topology.SequencingParameters
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.config.BftSequencingParameters
import org.lfdecentralizedtrust.splice.util.StandaloneCanton

class SvReconcileBftSequencingParametersIntegrationTest
    extends IntegrationTest
    with StandaloneCanton {

  override def dbsSuffix = "bftsequencingparameters"

  override lazy val resetRequiredTopologyState = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .addConfigTransforms(
        (_, c) => ConfigTransforms.bumpCantonPortsBy(22_000)(c),
        (_, c) =>
          c.copy(
            svApps = c.svApps +
              (InstanceName.tryCreate("sv1Local") ->
                c.svApps(InstanceName.tryCreate("sv1"))
                  .copy(
                    bftSequencingParameters = Some(
                      BftSequencingParameters(
                        pbftViewChangeTimeout = PositiveFiniteDuration.ofSeconds(5),
                        segmentLength = SequencingParameters.DefaultSegmentLength.length,
                        blacklistLeaderSelectionPolicyConfig =
                          SequencingParameters.DefaultLeaderSelectionPolicyConfig,
                      )
                    )
                  ))
          ),
      )
      .withManualStart

  "SV automation can set and unset bft sequencing parameters" in { implicit env =>
    withCantonSvNodes(
      (
        Some(sv1Backend),
        Some(sv2Backend),
        Some(sv2Backend),
        None,
      ),
      logSuffix = "bft-sequencing-parameters",
      sv4 = false,
      enableBftSequencer = true,
    )() {
      sv1Backend.startSync()
      sv1Backend.stop()
      sv1Backend.participantClient.topology.sequencing_parameters
        .list(decentralizedSynchronizerId) shouldBe empty
      actAndCheck("Restart with sequencing parameters set", sv1LocalBackend.startSync())(
        "sequencing parameters are set",
        _ => {
          val parameters = sv1Backend.participantClient.topology.sequencing_parameters
            .list(decentralizedSynchronizerId)
            .loneElement
          val bytes = parameters.item.payload.value
          val bftParameters = SequencingParameters
            .fromByteString(sv1Backend.config.localSynchronizerNodes.current.protocolVersion, bytes)
            .value
          bftParameters.pbftViewChangeTimeout shouldBe com.digitalasset.canton.time.PositiveFiniteDuration
            .tryOfSeconds(5)
        },
      )
      sv1LocalBackend.stop()
      actAndCheck("Restart with sequencing parameters unset", sv1Backend.startSync())(
        "sequencing parameters are unset",
        _ => {
          sv1Backend.participantClient.topology.sequencing_parameters
            .list(decentralizedSynchronizerId) shouldBe empty
        },
      )
      sv1Backend.stop()
    }
  }
}
