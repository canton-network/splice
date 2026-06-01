// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

abstract class TriggerEnabledSynchronization {

  /** Blocks until the trigger is allowed to start its next unit of work. */
  def waitForTriggerEnabled()(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit]
}

object TriggerEnabledSynchronization {
  final class NoopTriggerEnabledSynchronization extends TriggerEnabledSynchronization {
    override def waitForTriggerEnabled()(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Unit] = Future.unit
  }

  /** Triggers that are always enabled */
  val Noop = new NoopTriggerEnabledSynchronization()

  def fromDomainTime(
      domainTimeSync: DomainTimeSynchronization
  ): TriggerEnabledSynchronization = new TriggerEnabledSynchronization {
    override def waitForTriggerEnabled()(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Unit] = {
      for {
        _ <- domainTimeSync.waitForDomainTimeSync()
      } yield ()
    }
  }
}
