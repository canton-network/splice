// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.lsu.{LsuAnnouncementTriggerBase, LsuSchedule}
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorLsuConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Publishes the LSU announcement for the upgrade this operator has scheduled in its own config.
  *
  * Written through the sequencer, which owns the synchronizer's namespace. The operator's
  * participant holds no key in it.
  */
class DedicatedLsuAnnouncementTrigger(
    override protected val context: TriggerContext,
    override protected val connection: SequencerAdminConnection,
    lsuConfig: SyncOperatorLsuConfig,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends LsuAnnouncementTriggerBase {

  override protected def currentPhysicalSynchronizerId()(implicit
      tc: TraceContext
  ): Future[PhysicalSynchronizerId] = connection.getPhysicalSynchronizerId()

  override protected def upgradeDueAt(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[LsuSchedule]] =
    Future.successful(
      // A file-backed time is only readable once the operator has written it, so until then the
      // upgrade is not scheduled yet rather than misconfigured.
      Try(
        (lsuConfig.topologyFreezeTime.getTimestamp(), lsuConfig.upgradeTime.getTimestamp())
      ) match {
        case Failure(err) =>
          logger.debug(s"No upgrade scheduled yet, the configured times are unreadable: $err")
          None
        case Success((freezeTime, upgradeTime)) =>
          Option.when(!now.isBefore(freezeTime))(
            LsuSchedule(
              upgradeTime = upgradeTime,
              successorSerial = lsuConfig.newPhysicalSynchronizerSerial,
              successorProtocolVersion = lsuConfig.newPhysicalSynchronizerProtocolVersion,
            )
          )
      }
    )
}
