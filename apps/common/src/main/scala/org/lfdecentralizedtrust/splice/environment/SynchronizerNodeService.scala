// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.github.blemale.scaffeine.Scaffeine

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Hands out whichever synchronizer node is live, so callers keep working across an upgrade. The
  * switch is sticky once it has happened.
  */
class SynchronizerNodeService[T <: SynchronizerNode](
    val nodes: SynchronizerNode.LocalSynchronizerNodes[T],
    cacheExpiration: NonNegativeFiniteDuration,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val successorActiveRef = new java.util.concurrent.atomic.AtomicReference(false)

  private val successorActiveCache =
    ScaffeineCache.buildTracedAsync[Future, Unit, Boolean](
      Scaffeine().expireAfterWrite(cacheExpiration.asFiniteApproximation),
      implicit tc =>
        _ =>
          retryProvider.getValueWithRetries(
            RetryFor.WaitingOnInitDependency,
            "successor_active",
            "whether the successor synchronizer is active",
            successorActiveUncached(),
            logger,
          ),
    )(logger, "successorActive")

  /** The successor rejects traffic reads until the predecessor's traffic state has been transferred
    * onto it, which is the point from which grants have to go to the successor to survive the
    * upgrade.
    */
  private def successorActiveUncached()(implicit tc: TraceContext): Future[Boolean] =
    nodes.successor match {
      case None => Future.successful(false)
      case Some(successor) =>
        (for {
          sequencerId <- successor.sequencerAdminConnection.getSequencerId
          _ <- successor.sequencerAdminConnection.lookupSequencerTrafficControlState(sequencerId)
        } yield true).recover { case NonFatal(err) =>
          logger.debug(s"Successor synchronizer is not serving traffic state yet: $err")
          false
        }
    }

  private def successorActive()(implicit tc: TraceContext): Future[Boolean] =
    if (successorActiveRef.get()) {
      Future.successful(true)
    } else {
      successorActiveCache.get(()).map { active =>
        if (active) {
          logger.info("Switching connection to successor synchronizer")
          successorActiveRef.set(active)
        }
        active
      }
    }

  def activeSynchronizerNode()(implicit tc: TraceContext): Future[T] =
    nodes.successor match {
      case None => Future.successful(nodes.current)
      case Some(successor) =>
        successorActive().map {
          if (_) {
            successor
          } else {
            nodes.current
          }
        }
    }

  def sequencerAdminConnection()(implicit tc: TraceContext): Future[SequencerAdminConnection] =
    activeSynchronizerNode().map(_.sequencerAdminConnection)
}
