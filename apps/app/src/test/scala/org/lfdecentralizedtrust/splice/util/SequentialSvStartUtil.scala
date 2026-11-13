package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.admin.SequencerBftAdminData.{
  PeerConnectionStatus,
  PeerEndpointHealth,
  PeerEndpointHealthStatus,
}
import com.digitalasset.canton.topology.SequencerId
import org.lfdecentralizedtrust.splice.console.{AppBackendReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition

import scala.concurrent.duration.*

/** Onboards the BFT sequencers of the SVs one at a time if the BFT ordering topology does not contain all of them yet,
  * which is the case for the first multi-SV test that runs against a Canton instance.
  *
  * When several sequencers join at once (e.g. 1 -> 4), the existing sequencers have no authenticated peer for a few
  * seconds and reject or lose submissions, which then stay in flight until their max sequencing time. Up to 3 nodes,
  * the BFT weak quorum is 1, so the sequencers never reject submissions. When the 4th sequencer joins, 3 nodes are
  * already authenticated.
  */
trait SequentialSvStartUtil { this: BaseTest =>

  private val sequencerReadinessTimeout = 2.minutes

  /** Starts the SV apps among `nodes` one at a time, each together with its scan and validator if they are among
    * `nodes`, if their sequencers are not all in the BFT ordering topology yet. Does nothing otherwise. The caller
    * starts the remaining nodes.
    */
  protected def startSvsOneAtATimeIfNeeded(nodes: Seq[AppBackendReference]): Unit = {
    val svs = bftSvs(nodes.collect { case sv: SvAppBackendReference => sv })
    // Tests that start SVs whose sequencers are not running keep the parallel start
    if (
      svs.sizeIs > 1 && svs.forall(_.sequencerClient.health.is_running()) &&
      !allSequencersOnboarded(svs)
    ) {
      logger.info(
        s"BFT ordering topology does not contain the sequencers of all of ${svs.map(_.name)}, starting them one at a time"
      )
      svs.foreach { sv =>
        val svNodes =
          sv +: nodes.filter(n => n.name == s"${sv.name}Scan" || n.name == s"${sv.name}Validator")
        // Users are usually allocated after the apps are started
        EnvironmentDefinition
          .ensureAllocatedAdminUser(sv.config.ledgerApiUser, sv.participantClientWithAdminToken)
          .discard
        logger.info(s"Starting ${svNodes.map(_.name)}")
        svNodes.foreach(_.start())
        svNodes.foreach(_.waitForInitialization())
        waitUntilSequencersAreReady(
          bftSvs(sv.spliceConsoleEnvironment.svs.local).filter(_.is_running)
        )
      }
    }
  }

  private def bftSvs(svs: Seq[SvAppBackendReference]): Seq[SvAppBackendReference] =
    svs
      .filterNot(_.name.endsWith("Local"))
      .filterNot(_.name.endsWith("Onboarded"))
      .filter(_.config.localSynchronizerNodes.current.sequencer.isCantonBftSequencer)
      .sortBy(_.name)

  private def allSequencersOnboarded(svs: Seq[SvAppBackendReference]): Boolean =
    svs.forall(_.sequencerClient.health.initialized()) && {
      val ids = svs.map(_.sequencerClient.id).toSet
      svs.forall(sv =>
        ids.subsetOf(sv.sequencerClient.bft.get_ordering_topology().sequencerIds.toSet)
      )
    }

  private def waitUntilSequencersAreReady(svs: Seq[SvAppBackendReference]): Unit =
    eventually(sequencerReadinessTimeout) {
      svs.foreach { sv =>
        withClue(s"sequencer of ${sv.name} is initialized") {
          sv.sequencerClient.health.initialized() shouldBe true
        }
      }
      val ids = svs.map(_.sequencerClient.id).toSet
      svs.foreach { sv =>
        val sequencer = sv.sequencerClient
        withClue(s"ordering topology of ${sv.name}") {
          sequencer.bft.get_ordering_topology().sequencerIds.toSet should contain allElementsOf ids
        }
        withClue(s"authenticated peers of ${sv.name}") {
          authenticatedPeers(sv) should contain allElementsOf (ids - sequencer.id)
        }
      }
    }

  private def authenticatedPeers(sv: SvAppBackendReference): Set[SequencerId] =
    sv.sequencerClient.bft
      .get_peer_network_status()
      .endpointStatuses
      .collect {
        case PeerConnectionStatus.PeerEndpointStatus(
              _,
              _,
              PeerEndpointHealth(PeerEndpointHealthStatus.Authenticated(sequencerId), _),
            ) =>
          sequencerId
        case PeerConnectionStatus.PeerIncomingConnection(sequencerId) => sequencerId
      }
      .toSet
}
