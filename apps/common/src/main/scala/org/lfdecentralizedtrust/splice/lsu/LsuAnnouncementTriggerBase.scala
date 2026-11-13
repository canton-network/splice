// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.lsu

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{PhysicalSynchronizerId, SynchronizerId}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType
import org.lfdecentralizedtrust.splice.lsu.LsuAnnouncementTriggerBase.LsuAnnouncementTask

import scala.concurrent.{ExecutionContext, Future}

/** Publishes the `LsuAnnouncement` for a scheduled upgrade. Every other LSU step keys off it.
  *
  * Subclasses supply the schedule and the connection that authorizes the announcement.
  */
abstract class LsuAnnouncementTriggerBase(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[LsuAnnouncementTask] {

  /** The connection authorized to write the announcement for this synchronizer. */
  protected def connection: TopologyAdminConnection

  protected def currentPhysicalSynchronizerId()(implicit
      tc: TraceContext
  ): Future[PhysicalSynchronizerId]

  /** The scheduled upgrade, once its topology freeze time has been reached. Subclasses gate on the
    * freeze time themselves so a schedule that is not due yet is never parsed.
    */
  protected def upgradeDueAt(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[LsuSchedule]]

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[LsuAnnouncementTask]] =
    upgradeDueAt(now).flatMap {
      case Some(schedule) =>
        for {
          psid <- currentPhysicalSynchronizerId()
          existingAnnouncement <- connection.lookupSynchronizerLsuAnnouncement(
            psid.logical,
            TimeQuery.HeadState,
            TopologyTransactionType.AuthorizedState,
          )
          wasRemoved <- connection.wasLsuAnnouncementRemoved(psid.logical, schedule.successorSerial)
        } yield {
          if (psid.serial >= schedule.successorSerial) { Seq.empty }
          else if (wasRemoved) {
            logger.info(
              s"Not creating LSU announcement for serial ${schedule.successorSerial} as it was previously cancelled"
            )
            Seq.empty
          } else {
            existingAnnouncement match {
              case Some(announcement)
                  if announcement.mapping.successorSynchronizerId.serial == schedule.successorSerial =>
                Seq.empty
              case _ =>
                Seq(LsuAnnouncementTask(psid.logical, schedule))
            }
          }
        }
      case None => Future.successful(Seq.empty)
    }

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[LsuAnnouncementTask]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val schedule = task.work.schedule
    for {
      _ <- connection.ensureLsuAnnouncement(
        task.work.synchronizerId,
        schedule.upgradeTime,
        schedule.successorSerial,
        schedule.successorProtocolVersion,
      )
    } yield TaskSuccess(
      s"Published LSU announcement for upgrade at ${schedule.upgradeTime} with psid ${schedule.successorSerial}"
    )
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[LsuAnnouncementTask]
  )(implicit tc: TraceContext): Future[Boolean] =
    for {
      existingAnnouncement <- connection.lookupSynchronizerLsuAnnouncement(
        task.work.synchronizerId,
        TimeQuery.HeadState,
        TopologyTransactionType.AuthorizedState,
      )
    } yield existingAnnouncement.exists(
      _.mapping.successorSynchronizerId.serial == task.work.schedule.successorSerial
    )
}

object LsuAnnouncementTriggerBase {

  final case class LsuAnnouncementTask(
      synchronizerId: SynchronizerId,
      schedule: LsuSchedule,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("synchronizerId", _.synchronizerId),
      param("schedule", _.schedule),
    )
  }
}

/** A scheduled logical synchronizer upgrade. */
final case class LsuSchedule(
    upgradeTime: CantonTimestamp,
    successorSerial: NonNegativeInt,
    successorProtocolVersion: ProtocolVersion,
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] = prettyOfClass(
    param("upgradeTime", _.upgradeTime),
    param("successorSerial", _.successorSerial),
    param("successorProtocolVersion", _.successorProtocolVersion),
  )
}
