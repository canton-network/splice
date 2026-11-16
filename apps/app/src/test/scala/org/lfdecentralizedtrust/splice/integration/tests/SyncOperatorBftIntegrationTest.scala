// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.{SequencerAlias, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data.{
  SubmissionRequestAmplification,
  SynchronizerConnectionConfig,
}
import com.digitalasset.canton.config.{
  FullClientConfig,
  NonNegativeDuration,
  SequencerApiClientConfig,
}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong, Port}
import com.digitalasset.canton.synchronizer.mediator.RemoteMediatorConfig
import com.digitalasset.canton.synchronizer.sequencer.config.RemoteSequencerConfig
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.console.{MediatorClientReference, SequencerClientReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.StandaloneCanton
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig

import scala.concurrent.duration.*

/** Runs a dedicated synchronizer on four BFT nodes with one sync operator per node, and checks
  * that every node grants a member unlimited traffic and that the nodes agree on what it
  * consumes.
  */
class SyncOperatorBftIntegrationTest extends IntegrationTest with StandaloneCanton {

  override def dbsSuffix = "sync_operator_bft"

  // The nodes' databases, see standalone-sync-operator-bft.conf, and the databases of operators 2
  // to 4, see sync-operator-bft-topology.conf.
  override def usesDbs: Seq[String] =
    (1 to 4).flatMap(n =>
      Seq(
        s"sequencer_${dbsSuffix}_$n",
        s"sequencer_driver_${dbsSuffix}_$n",
        s"mediator_${dbsSuffix}_$n",
      )
    ) ++ (2 to 4).map(n => s"splice_apps_${dbsSuffix}_$n") ++ super.usesDbs

  private val synchronizerAlias = SynchronizerAlias.tryCreate("syncOperatorBft")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq(
          "simple-topology-1sv.conf",
          "sync-operator-topology.conf",
          "sync-operator-bft-topology.conf",
        ),
        this.getClass.getSimpleName,
      )
      .withOnlyAliceValidatorConnectingToSplitwell
      .withStandardSetup
      // The operators can only start once their synchronizer is bootstrapped.
      .withManualStart

  "sync operators" should {

    "run a dedicated synchronizer on four BFT nodes" in { implicit env =>
      initDsoWithSv1Only()
      startAllSync(aliceValidatorBackend, splitwellValidatorBackend)
      withBootstrappedCanton(
        Seq(testResourcesPath / "standalone-sync-operator-bft.conf"),
        testResourcesPath / "standalone-sync-operator-bft-bootstrap.sc",
        "sync-operator-bft",
      ) {
        val nodes = (1 to 4).map(node)

        val synchronizerId = clue("the four nodes bootstrap the synchronizer") {
          // Each node reports the synchronizer it serves once the bootstrap script has run.
          eventually(3.minutes) {
            forAll(nodes)(_.mediator.health.status.successOption should not be empty)
            nodes
              .map(_.sequencer.health.status.successOption.map(_.synchronizerId))
              .distinct
              .loneElement
              .value
              .logical
          }
        }

        clue("the synchronizer runs on all four sequencers and mediators") {
          eventually(1.minute) {
            val sequencerState = nodes.head.sequencer.topology.sequencers
              .list(store = Some(TopologyStoreId.Synchronizer(synchronizerId)))
              .loneElement
              .item
            sequencerState.active.forgetNE should contain theSameElementsAs nodes.map(
              _.sequencer.id
            )
            // The bootstrap script raises it from the one that genesis sets.
            sequencerState.threshold shouldBe Thresholds.sequencerConnectionsSizeThreshold(
              nodes.size
            )
          }
          val mediatorState = nodes.head.sequencer.topology.mediators
            .list(Some(synchronizerId), group = Some(NonNegativeInt.zero))
            .loneElement
            .item
          mediatorState.active.forgetNE should contain theSameElementsAs nodes.map(_.mediator.id)
          mediatorState.threshold shouldBe Thresholds.mediatorDomainStateThreshold(nodes.size)
        }

        clue("the four sequencers order together") {
          eventually() {
            forAll(nodes)(
              _.sequencer.bft
                .get_ordering_topology()
                .sequencerIds should contain theSameElementsAs nodes.map(_.sequencer.id)
            )
          }
        }

        clue("every node runs traffic control with a zero base rate and restricted onboarding") {
          // Each operator checks this on its own node when it starts.
          eventually(1.minute) {
            forAll(nodes) { n =>
              val parameters = n.sequencer.topology.synchronizer_parameters
                .get_dynamic_synchronizer_parameters(synchronizerId)
              parameters.trafficControl.map(_.maxBaseTrafficAmount) shouldBe
                Some(NonNegativeLong.zero)
              parameters.onboardingRestriction.isRestricted shouldBe true
            }
          }
        }

        val operators = syncOperatorBackend +: (2 to 4).map(n => syncop(s"syncOperator$n"))
        operators.foreach(_.start())
        operators.foreach(_.waitForInitialization())

        // A member's traffic limit and consumed traffic as each node reports it.
        def trafficOnEachNode(member: Member) =
          nodes.map(
            _.sequencer.traffic_control
              .traffic_state_of_members(Seq(member))
              .trafficStates
              .get(member)
              .map(state => (state.extraTrafficPurchased.value, state.extraTrafficConsumed.value))
          )

        clue("the operators grant the mediators unlimited traffic") {
          eventually(1.minute) {
            forAll(nodes)(n =>
              trafficOnEachNode(n.mediator.id).map(_.map(_._1)) shouldBe Seq.fill(nodes.size)(
                Some(NonNegativeLong.maxValue.value)
              )
            )
          }
        }

        val participant = aliceValidatorBackend.participantClientWithAdminToken
        val member = participant.id

        // Changes to the synchronizer's topology take three of the four owners, see
        // standalone-sync-operator-bft-bootstrap.sc.
        clue("the participant is admitted") {
          nodes
            .take(3)
            .foreach(
              _.sequencer.topology.participant_synchronizer_permissions.propose(
                synchronizerId,
                member,
                ParticipantPermission.Submission,
                synchronize = None,
              )
            )
          eventually() {
            forAll(nodes)(
              _.sequencer.topology.participant_synchronizer_permissions
                .list(
                  store = TopologyStoreId.Synchronizer(synchronizerId),
                  filterUid = member.filterString,
                ) should not be empty
            )
          }
        }

        // The nodes only report traffic for members the synchronizer knows. Joining by handshake
        // alone makes the participant one, and needs no traffic.
        clue("the participant joins the synchronizer") {
          participant.synchronizers.register_by_config(
            SynchronizerConnectionConfig.tryGrpc(
              synchronizerAlias,
              nodes.map(n =>
                n.sequencer.sequencerConnection.withAlias(
                  SequencerAlias.tryCreate(n.sequencer.name)
                )
              ),
              // As a validator connects to the global synchronizer.
              sequencerTrustThreshold = Thresholds.sequencerConnectionsSizeThreshold(nodes.size),
              sequencerLivenessMargin = Thresholds.sequencerConnectionsLivenessMargin(nodes.size),
              submissionRequestAmplification = SubmissionRequestAmplification(
                Thresholds.sequencerSubmissionRequestAmplification(nodes.size),
                ValidatorAppBackendConfig.DefaultSequencerRequestAmplificationPatience,
              ),
            )
          )
          eventually() {
            forAll(nodes)(
              _.sequencer.topology.synchronizer_trust_certificates
                .list(
                  store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
                  filterUid = member.filterString,
                ) should not be empty
            )
          }
        }

        clue("every node grants the participant unlimited traffic") {
          eventually(1.minute) {
            trafficOnEachNode(member).map(_.map(_._1)) shouldBe Seq.fill(nodes.size)(
              Some(NonNegativeLong.maxValue.value)
            )
          }
        }

        // Connecting vets Canton's admin workflows on the synchronizer and pushes the participant's
        // parties.
        clue("the participant connects to all four nodes") {
          participant.synchronizers.reconnect(synchronizerAlias) shouldBe true
        }

        // The participant admin API in tests does not allow pings, so the participant submits a
        // topology transaction instead.
        clue("the participant transacts") {
          participant.parties.enable(
            "bft-member",
            synchronizer = Some(synchronizerAlias),
            synchronizeParticipants = Seq(participant),
            synchronize = Some(NonNegativeDuration.ofSeconds(60)),
          )
        }

        clue("the nodes agree on the traffic the participant consumes") {
          eventually(1.minute) {
            val (limit, consumed) = trafficOnEachNode(member).distinct.loneElement.value
            limit shouldBe NonNegativeLong.maxValue.value
            consumed should be > 0L
          }
        }

        clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
          operators.foreach(_.stop())
          participant.synchronizers.disconnect(synchronizerAlias)
        }
      }
    }
  }

  // A node of the synchronizer. Node n uses ports 29n07 to 29n10, see
  // standalone-sync-operator-bft.conf.
  private case class Node(sequencer: SequencerClientReference, mediator: MediatorClientReference)

  private def node(n: Int)(implicit env: SpliceTestConsoleEnvironment): Node = {
    val base = 29000 + n * 100
    Node(
      new SequencerClientReference(
        env,
        s"syncOperatorBftSequencer$n",
        RemoteSequencerConfig(
          adminApi = FullClientConfig(port = Port.tryCreate(base + 9)),
          publicApi = SequencerApiClientConfig("localhost", Port.tryCreate(base + 8)),
        ),
      ),
      new MediatorClientReference(
        env,
        s"syncOperatorBftMediator$n",
        RemoteMediatorConfig(adminApi = FullClientConfig(port = Port.tryCreate(base + 7))),
      ),
    )
  }
}
