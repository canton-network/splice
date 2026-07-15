// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.p2p.grpc.P2PGrpcNetworking.P2PEndpoint
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.{
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.store.DsoRulesStore
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.DsoRulesTopologyStateReconciler
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional
import scala.util.control.NonFatal

abstract class SequencerBftPeerReconciler(
    sequencerAdminConnection: SequencerAdminConnection,
    scanConnection: AggregatingScanConnection,
    retryProvider: RetryProvider,
) extends DsoRulesTopologyStateReconciler[BftPeerDifference]
    with NamedLogging {

  override protected def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesStore.DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[BftPeerDifference]] = {
    for {
      sequencerInitialized <- retryProvider.retry(
        RetryFor.Automation,
        "sequencer_init_status",
        "Check if sequencer is initialized",
        sequencerAdminConnection
          .isNodeInitialized(),
        logger,
      )
      result <-
        if (!sequencerInitialized) Future.successful(Seq.empty)
        else
          for {
            sequencerId <- sequencerAdminConnection.getSequencerId
            psid <- sequencerAdminConnection.getPhysicalSynchronizerId()
            serialId = psid.serial.unwrap.toLong
            sequencers = dsoRulesAndState
              .currentSynchronizerNodeConfigs()
              .flatMap(config =>
                config.sequencerIdentity.toScala
                  .map(_.sequencerId)
                  .orElse(config.sequencer.toScala.map(_.sequencerId))
              )
              .flatMap(sequencerId =>
                SequencerId
                  .fromProtoPrimitive(sequencerId, "sequencerId")
                  .fold(
                    error => {
                      logger.warn(s"Failed to parse sequencer id $sequencerId. $error")
                      None
                    },
                    Some(_),
                  )
              )
            dsoSequencersWithoutSelf = sequencers.filter(_ != sequencerId)
            sequencersFromScan <- getAllBftSequencers()
            dsoSequencersWithEndpoint = dsoSequencersWithoutSelf.map { sequencerId =>
              sequencerId -> sequencersFromScan
                .find(scanSequencer =>
                  scanSequencer.id == sequencerId && scanSequencer.serialId == serialId
                )
                .map(_.peerId)
            }
            dsoSequencerEndpoints = dsoSequencersWithEndpoint.flatMap(_._2)
            configuredPeers <- sequencerAdminConnection
              .listConfiguredPeerEndpoints()
            peersToAdd = dsoSequencerEndpoints
              .filterNot(endpoint => configuredPeers.exists(_.id == endpoint.id))
            candidatePeersToRemove = configuredPeers
              .filterNot(peer => dsoSequencerEndpoints.exists(_.id == peer.id))
            peersToRemove <- computePeersToRemove(
              candidatePeersToRemove,
              dsoSequencersWithEndpoint,
            )
          } yield {
            if (peersToAdd.nonEmpty || peersToRemove.nonEmpty)
              Seq(
                BftPeerDifference(
                  peersToAdd,
                  peersToRemove.map(_.id),
                  configuredPeers,
                )
              )
            else Seq()
          }
    } yield result
  }

  /** If all DSO sequencers have an associated peer endpoint advertised by scan, any configured peer
    * that does not correspond to one of those endpoints is stale and safe to remove.
    *
    * Otherwise we cannot rely on scan alone (as some scans can be unavailable), so we cross-check the peer network status to find the
    * sequencer id backing each candidate endpoint. Removal is only safe if that sequencer id is no
    * longer part of the DSO sequencers, or if it is now associated with a different endpoint. If no
    * sequencer id can be found for a candidate endpoint we keep it and log a warning.
    */
  private def computePeersToRemove(
      candidatePeersToRemove: Seq[P2PEndpoint],
      dsoSequencersWithEndpoint: Seq[(SequencerId, Option[P2PEndpoint])],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[P2PEndpoint]] = {
    val allDsoSequencersHaveEndpoint = dsoSequencersWithEndpoint.forall { case (_, endpoint) =>
      endpoint.isDefined
    }
    if (candidatePeersToRemove.isEmpty || allDsoSequencersHaveEndpoint) {
      Future.successful(candidatePeersToRemove)
    } else {
      sequencerAdminConnection.listCurrentPeerEndpoints().map { networkStatus =>
        candidatePeersToRemove.filter { peer =>
          networkStatus.collectFirst {
            case (Some(sequencerId), Some(endpointId)) if endpointId == peer.id => sequencerId
          } match {
            case Some(sequencerId) =>
              val sequencerNoLongerInDso =
                !dsoSequencersWithEndpoint.exists { case (dsoSequencerId, _) =>
                  dsoSequencerId == sequencerId
                }
              val sequencerMovedToDifferentEndpoint =
                dsoSequencersWithEndpoint.exists { case (dsoSequencerId, endpoint) =>
                  dsoSequencerId == sequencerId && endpoint.exists(_.id != peer.id)
                }
              sequencerNoLongerInDso || sequencerMovedToDifferentEndpoint
            case None =>
              logger.warn(
                s"Could not find a sequencer id for the configured peer endpoint ${peer.id} in the peer network status; not removing it to be safe."
              )
              false
          }
        }
      }
    }
  }

  private def getAllBftSequencers()(implicit ec: ExecutionContext, tc: TraceContext) = {
    scanConnection
      .fromAllScans(includeSelf = false) { scan =>
        scan
          .listSvBftSequencers()
          .recover { case NonFatal(ex) =>
            logger.warn(s"Failed to read bft sequencers list from scan ${scan.url}", ex)
            Seq.empty
          }
      }
      .map(_.flatten)
  }
}

object SequencerBftPeerReconciler {
  case class BftPeerDifference(
      toAdd: Seq[P2PEndpoint],
      toRemove: Seq[P2PEndpoint.Id],
      currentPeers: Seq[P2PEndpoint],
  )
}
