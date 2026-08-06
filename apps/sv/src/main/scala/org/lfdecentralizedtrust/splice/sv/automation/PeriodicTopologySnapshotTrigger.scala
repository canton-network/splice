// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.config.PeriodicBackupDumpConfig
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.util.BackupDump

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.{Failure, Success}

/** As taking a topology snapshot is not a cheap operation, we limit this trigger to produce at most one snapshot per day.
  */
class PeriodicTopologySnapshotTrigger(
    synchronizerAlias: SynchronizerAlias,
    config: PeriodicBackupDumpConfig,
    triggerContext: TriggerContext,
    synchronizerNodeService: SynchronizerNodeService[LocalSynchronizerNode],
    participantAdminConnection: ParticipantAdminConnection,
    clock: Clock,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    actorSystem: ActorSystem,
    esf: ExecutionSequencerFactory,
) extends PeriodicTaskTrigger(config.backupInterval, triggerContext) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit traceContext: TraceContext): Future[TaskOutcome] = {
    participantAdminConnection
      .getPhysicalSynchronizerId(synchronizerAlias)
      .transformWith {
        case Failure(s: StatusRuntimeException) if s.getStatus.getCode == Status.Code.NOT_FOUND =>
          Future.successful(TaskNoop)
        case Failure(e) => Future.failed(e)
        case Success(physicalSynchronizerId) =>
          val now = clock.now
          val utcDate = now.toInstant.toString.split("T").head
          val folderName = s"topology_snapshot_$utcDate"
          for {
            snapshotExists <- validTopologySnapshotExists(startOffset =
              s"topology_snapshot_$utcDate"
            )
            res <-
              if (!snapshotExists)
                takeTopologySnapshot(
                  folderName,
                  now,
                  utcDate,
                  physicalSynchronizerId,
                )
              else Future.successful(TaskSuccess("Today's topology snapshot already exists."))
          } yield res
      }
  }

  private def validTopologySnapshotExists(startOffset: String): Future[Boolean] =
    for {
      res <- Future {
        blocking {
          val blobs = BackupDump.getBlobs(config.location, s"$startOffset", loggerFactory)
          Seq("onboarding-state.zst", "authorized", "metadata").forall(suffix =>
            blobs.exists(_.getName.endsWith(suffix))
          ) && blobs.size == 3
        }
      }
    } yield res

  private def takeTopologySnapshot(
      folderName: String,
      now: CantonTimestamp,
      utcDate: String,
      physicalSynchronizerId: PhysicalSynchronizerId,
  )(implicit
      traceContext: TraceContext,
      esf: ExecutionSequencerFactory,
      actorSystem: ActorSystem,
  ): Future[TaskSuccess] =
    for {
      sequencerAdminConnection <- synchronizerNodeService.sequencerAdminConnection()
      sequencerId <- sequencerAdminConnection.getSequencerId
      // uses onboardingStateV2 so we don't lose information when exporting
      _ = logger.info("Starting onboarding state stream into gcp bucket...")
      _ <- triggerContext.retryProvider.retry(
        RetryFor.Automation,
        "streamOnboardingState",
        "Stream onboarding state to GCP bucket",
        sequencerAdminConnection
          .streamOnboardingState(
            Right(now),
            config.location,
            Paths.get(s"$folderName/onboarding-state.zst").toString,
          )
          .andThen {
            case Success(storageObject) =>
              logger.info(
                s"Finished streaming with ${storageObject.name} weighting ${storageObject.size / 1000} KB"
              )
            case Failure(e) => logger.error("Failed to stream onboarding state.", e)
          },
        logger,
      )
      authorizedStore <- triggerContext.retryProvider.retry(
        RetryFor.Automation,
        "exportAuthorizedStoreSnapshot",
        "Export authorized store snapshot",
        sequencerAdminConnection.exportAuthorizedStoreSnapshot(sequencerId.uid),
        logger,
      )
      authorizedStoreFileName = s"$folderName/authorized"
      _ <- triggerContext.retryProvider.retry(
        RetryFor.Automation,
        "writeAuthorizedFile",
        "Write authorized store into GCP bucket",
        Future {
          blocking {
            val _ = BackupDump.writeBytes(
              config.location,
              Paths.get(authorizedStoreFileName),
              authorizedStore.toByteArray,
              loggerFactory,
            )
            if (
              BackupDump.getBlobs(config.location, authorizedStoreFileName, loggerFactory).isEmpty
            ) {
              throw Status.NOT_FOUND
                .withDescription(
                  s"Verification failed: authorized does not exist in '$folderName'"
                )
                .asRuntimeException
            }
          }
        },
        logger,
      )
      // list a summary of the transactions state at the time of the snapshot to validate further imports
      summary <- triggerContext.retryProvider.retry(
        RetryFor.Automation,
        "getTopologyTransactionsSummary",
        "Get topology transactions summary",
        sequencerAdminConnection.getTopologyTransactionsSummary(
          TopologyStoreId.Synchronizer(physicalSynchronizerId.logical),
          clock.now,
        ),
        logger,
      )
      // we create a single metadata file to store the amounts of the different transactions along the sequencerId
      metadataMap = summary.map(e => (e._1.code, e._2.toString)) +
        ("sequencerId" -> sequencerId.toProtoPrimitive) +
        ("physicalSynchronizerId" -> physicalSynchronizerId.toProtoPrimitive)
      metadataJson = Json
        .obj(metadataMap.map { case (k, v) => k -> Json.fromString(v) }.toSeq*)
        .spaces2
      metadataFileName = s"$folderName/metadata"
      _ <- triggerContext.retryProvider.retry(
        RetryFor.Automation,
        "writeMetadataFile",
        "Write metadata into GCP bucket",
        Future {
          blocking {
            val _ = BackupDump.write(
              config.location,
              Paths.get(metadataFileName),
              metadataJson,
              loggerFactory,
            )
            if (BackupDump.getBlobs(config.location, metadataFileName, loggerFactory).isEmpty) {
              throw Status.NOT_FOUND
                .withDescription(
                  s"Verification failed: metadata does not exist in '$folderName'"
                )
                .asRuntimeException
            }
          }
        },
        logger,
      )
    } yield TaskSuccess(
      s"Took a new topology snapshot on $utcDate."
    )
}
