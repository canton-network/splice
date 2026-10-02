// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.SynchronizerAlias
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.lsu.{LsuAnnouncementTriggerBase, LsuSchedule}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class LsuAnnouncementTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    override protected val connection: ParticipantAdminConnection,
    syncAlias: SynchronizerAlias,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends LsuAnnouncementTriggerBase {

  override protected def currentPhysicalSynchronizerId()(implicit
      tc: TraceContext
  ): Future[PhysicalSynchronizerId] = connection.getPhysicalSynchronizerId(syncAlias)

  override protected def upgradeDueAt(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[LsuSchedule]] =
    store.getDsoRules().map {
      _.payload.config.nextScheduledLogicalSynchronizerUpgrade.toScala
        .filter(schedule => !now.toInstant.isBefore(schedule.topologyFreezeTime))
        .map { schedule =>
          LsuSchedule(
            upgradeTime = CantonTimestamp.assertFromInstant(schedule.upgradeTime),
            successorSerial =
              NonNegativeInt.tryCreate(schedule.newPhysicalSynchronizerSerial.toInt),
            successorProtocolVersion =
              ProtocolVersion.tryCreate(schedule.newPhysicalSynchronizerProtocolVersion),
          )
        }
    }
}
