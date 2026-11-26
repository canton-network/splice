// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.unavailableparties

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore

import scala.concurrent.{ExecutionContext, Future}

/** Keeps the unavailable parties table consistent with the configuration: configured ignored
  * parties are always present, protected parties never are, and entries of other stores are removed.
  */
class UnavailablePartiesCleanupTrigger(
    override protected val context: TriggerContext,
    store: UnavailablePartiesStore,
    configuredIgnoredParties: Set[PartyId],
    protectedParties: Set[PartyId],
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    val mat: Materializer,
) extends PollingTrigger {

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      _ <- store.ensureParties((configuredIgnoredParties -- protectedParties).toSeq)
      removedProtected <- store.removeParties(protectedParties.toSeq)
      removedOtherStores <- store.removeOtherStores()
    } yield {
      if (removedProtected.nonEmpty)
        logger.info(s"Removed protected parties from the unavailable parties: $removedProtected")
      if (removedOtherStores > 0)
        logger.info(s"Removed $removedOtherStores unavailable parties entries of other stores")
      false
    }
}
