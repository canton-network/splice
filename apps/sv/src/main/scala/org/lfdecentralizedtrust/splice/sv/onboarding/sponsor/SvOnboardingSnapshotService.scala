// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import better.files.File
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  RunOnClosing,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, SequencerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.syntax.*
import io.grpc.Status
import org.apache.pekko.Done
import org.apache.pekko.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{Materializer, QueueOfferResult}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.*
import org.lfdecentralizedtrust.splice.sv.util.JsonCodec.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

class SvOnboardingSnapshotService(
    config: SvOnboardingSnapshotsConfig,
    clock: Clock,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has {

  private val exportsDirectory: Path =
    (File(config.directory) / "exports").createDirectories().path

  private val mutex = Mutex()
  private val jobs = mutable.Map.empty[SvOnboardingSnapshotId, Job]

  private def shuttingDown: Boolean = isClosing || retryProvider.isClosing

  private def shutdownError =
    Status.UNAVAILABLE
      .withDescription("SV onboarding snapshot exports are shutting down")
      .asRuntimeException()

  private def directory(id: SvOnboardingSnapshotId): Path =
    exportsDirectory.resolve(id.value)

  private def storedIds: Seq[SvOnboardingSnapshotId] =
    File(exportsDirectory).children.flatMap { entry =>
      val name = entry.name
      Try(UUID.fromString(name)).toOption
        .filter(_.toString == name)
        .map(_ => SvOnboardingSnapshotId(name))
    }.toList

  private def readSnapshot(
      id: SvOnboardingSnapshotId
  )(implicit tc: TraceContext): Option[Snapshot] = {
    val validId = Try(UUID.fromString(id.value)).toOption.exists(_.toString == id.value)
    if (!validId) None
    else {
      val dir = directory(id)
      val failure = dir.resolve("failure.json")
      val metadata = dir.resolve("metadata.json")
      def timestamp(value: String) = CantonTimestamp.assertFromInstant(Instant.parse(value))
      Try {
        if (Files.isRegularFile(failure)) {
          val persisted =
            decode[PersistedFailure](Files.readString(failure)).fold(throw _, identity)
          Some(Snapshot(timestamp(persisted.createdAt), Stored.Failed(persisted.reason)))
        } else if (Files.isRegularFile(metadata)) {
          val persisted =
            decode[PersistedSnapshot](Files.readString(metadata)).fold(throw _, identity)
          val file = dir.resolve("snapshot")
          val stored: Stored =
            if (Files.notExists(file)) Stored.Unusable("Snapshot file is missing")
            else if (!Files.isRegularFile(file) || Files.size(file) != persisted.size)
              Stored.Unusable("Snapshot file size has changed")
            else
              Stored.Finished(
                persisted.key,
                file,
              )
          Some(Snapshot(timestamp(persisted.createdAt), stored))
        } else None
      } match {
        case Success(snapshot) => snapshot
        case Failure(error) =>
          logger.warn(s"Cannot recover onboarding snapshot $id; it will be removed", error)
          None
      }
    }
  }

  private def writeAtomically(dir: Path, name: String, content: String): Unit = {
    val temporary = dir.resolve(s"$name.tmp")
    Files.writeString(temporary, content)
    Files.move(temporary, dir.resolve(s"$name.json"), StandardCopyOption.ATOMIC_MOVE)
    ()
  }

  private def recordFailure(id: SvOnboardingSnapshotId, createdAt: CantonTimestamp, reason: String)(
      implicit tc: TraceContext
  ): Unit = {
    val dir = directory(id)
    Try(
      writeAtomically(
        dir,
        "failure",
        PersistedFailure(createdAt.toInstant.toString, reason).asJson.noSpaces,
      )
    ) match {
      case Failure(error) =>
        logger.warn(s"Failed to record the failure of onboarding snapshot $id; removing it", error)
        deleteSnapshot(id, dir).discard
      case Success(_) => removeLeftovers(id)
    }
  }

  private def recordUnusable(
      id: SvOnboardingSnapshotId,
      createdAt: CantonTimestamp,
      reason: String,
  )(implicit
      tc: TraceContext
  ): Unit = {
    logger.warn(s"Onboarding snapshot $id is unusable: $reason")
    recordFailure(id, createdAt, reason)
  }

  private def removeLeftovers(id: SvOnboardingSnapshotId)(implicit tc: TraceContext): Unit = {
    val dir = directory(id)
    Try(
      Seq("metadata.json", "metadata.tmp", "snapshot").foreach(name =>
        Files.deleteIfExists(dir.resolve(name)).discard
      )
    ).failed.foreach(error =>
      logger.warn(s"Failed to delete onboarding snapshot file $id; cleanup will retry", error)
    )
  }

  private def deleteSnapshot(id: SvOnboardingSnapshotId, dir: Path)(implicit
      tc: TraceContext
  ): Boolean =
    Try {
      Seq("metadata.json", "metadata.tmp", "failure.json", "failure.tmp", "snapshot").foreach(
        name => Files.deleteIfExists(dir.resolve(name)).discard
      )
      Files.delete(dir)
    } match {
      case Success(_) =>
        logger.info(s"Removed onboarding snapshot file $id")
        true
      case Failure(error) =>
        logger.warn(s"Failed to delete onboarding snapshot file $id; cleanup will retry", error)
        false
    }

  private def removeExpiredNow(now: CantonTimestamp)(implicit tc: TraceContext): Int = {
    storedIds.count { id =>
      if (jobs.contains(id)) false
      else
        readSnapshot(id) match {
          case Some(Snapshot(createdAt, stored)) if createdAt.plus(config.retention.asJava) > now =>
            stored match {
              case Stored.Finished(_, _) => ()
              case Stored.Failed(_) => removeLeftovers(id)
              case Stored.Unusable(reason) => recordUnusable(id, createdAt, reason)
            }
            false
          case _ => deleteSnapshot(id, directory(id))
        }
    }
  }

  private val (queue, queueTerminated) = Source
    .queue[QueuedExport](config.queueSize.value)
    .mapAsyncUnordered(config.parallelism.value)(runExport)
    .toMat(Sink.ignore)(Keep.both)
    .run()

  queueTerminated.onComplete { result =>
    if (!shuttingDown)
      retryProvider
        .logTerminationAndRecoverOnShutdown("onboarding snapshot export queue", logger)(result)(
          TraceContext.empty
        )
        .discard
  }

  retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name: String = "terminate onboarding snapshot export queue"
    override def done: Boolean = queueTerminated.isCompleted
    override def run()(implicit tc: TraceContext): Unit = stopExports()
  })(TraceContext.empty)

  private def stopExports(): Unit =
    try queue.complete()
    catch {
      case _: IllegalStateException => ()
    }

  def prepare(key: SnapshotKey, exportTo: Path => Future[Unit])(implicit
      tc: TraceContext
  ): Future[SvOnboardingSnapshotId] = Future(blocking {
    mutex.exclusive {
      if (shuttingDown) throw shutdownError
      val existing = jobs
        .collectFirst { case (id, job) if job.key == key => id }
        .orElse {
          storedIds.find(id =>
            readSnapshot(id).exists {
              case Snapshot(createdAt, Stored.Finished(`key`, _)) =>
                createdAt.plus(config.retention.asJava) > clock.now
              case _ => false
            }
          )
        }
      existing.getOrElse {
        val id = SvOnboardingSnapshotId(UUID.randomUUID().toString)
        val file = directory(id).resolve("snapshot")
        queue.offer(QueuedExport(id, file, exportTo, tc)) match {
          case Enqueued =>
            jobs.update(id, Job(key, clock.now, None))
            logger.info(s"Queued onboarding snapshot export $id for $key")
            id
          case Dropped =>
            val message =
              s"Too many onboarding snapshot exports queued (max ${config.queueSize.value})"
            logger.warn(s"Rejected onboarding snapshot export for $key: $message")
            throw Status.UNAVAILABLE.withDescription(message).asRuntimeException()
          case QueueClosed => throw shutdownError
          case QueueOfferResult.Failure(cause) =>
            logger.warn(s"Failed to queue onboarding snapshot export for $key", cause)
            throw Status.UNAVAILABLE
              .withDescription("Failed to queue the onboarding snapshot export")
              .withCause(cause)
              .asRuntimeException()
        }
      }
    }
  })

  def lookup(id: SvOnboardingSnapshotId)(implicit tc: TraceContext): Option[SnapshotState] =
    blocking {
      mutex.exclusive {
        if (shuttingDown) throw shutdownError
        jobs.get(id) match {
          case Some(Job(_, queuedAt, None)) => Some(SnapshotState.Queued(queuedAt))
          case Some(Job(_, queuedAt, Some(startedAt))) =>
            Some(SnapshotState.Exporting(queuedAt, startedAt))
          case None =>
            readSnapshot(id).filter(_.createdAt.plus(config.retention.asJava) > clock.now).map {
              case Snapshot(_, Stored.Finished(_, file)) => SnapshotState.Ready(file)
              case Snapshot(_, Stored.Failed(reason)) => SnapshotState.Failed(reason)
              case Snapshot(createdAt, Stored.Unusable(reason)) =>
                recordUnusable(id, createdAt, reason)
                SnapshotState.Failed(reason)
            }
        }
      }
    }

  def removeExpired(now: CantonTimestamp)(implicit tc: TraceContext): Future[Int] =
    Future(blocking(mutex.exclusive(removeExpiredNow(now))))

  private def runExport(queued: QueuedExport): Future[Done] = {
    implicit val tc: TraceContext = queued.traceContext
    Future.delegate(exportAndRecord(queued)).recover { case NonFatal(error) =>
      logger.warn(s"Onboarding snapshot export ${queued.id} failed", error)
      Try(blocking(mutex.exclusive {
        jobs.remove(queued.id).discard
        recordFailure(queued.id, clock.now, "Snapshot export failed")
      })).failed.foreach(recordError =>
        logger.warn(
          s"Failed to record the failure of onboarding snapshot ${queued.id}",
          recordError,
        )
      )
      Done
    }
  }

  private def exportAndRecord(queued: QueuedExport): Future[Done] = {
    implicit val tc: TraceContext = queued.traceContext
    val start = blocking(mutex.exclusive {
      val running = !shuttingDown
      if (running) jobs.updateWith(queued.id)(_.map(_.copy(startedAt = Some(clock.now)))).discard
      running
    })
    if (!start) Future.successful(Done)
    else {
      logger.info(s"Starting onboarding snapshot export ${queued.id}")
      Future
        .fromTry(Try {
          Files.createDirectories(queued.file.getParent)
          queued.exportTo(queued.file)
        })
        .flatten
        .transform { result =>
          blocking {
            mutex.exclusive {
              jobs.get(queued.id).foreach { job =>
                val published = result.flatMap { _ =>
                  Try {
                    val metadata = PersistedSnapshot(
                      job.key,
                      clock.now.toInstant.toString,
                      Files.size(queued.file),
                    )
                    writeAtomically(queued.file.getParent, "metadata", metadata.asJson.noSpaces)
                  }
                }
                jobs.remove(queued.id).discard
                published match {
                  case Success(_) =>
                    logger.info(s"Finished onboarding snapshot export ${queued.id}")
                  case Failure(error) if shuttingDown =>
                    logger.info(
                      s"Onboarding snapshot export ${queued.id} stopped by shutdown: $error"
                    )
                    deleteSnapshot(queued.id, queued.file.getParent).discard
                  case Failure(error) =>
                    logger.warn(s"Onboarding snapshot export ${queued.id} failed", error)
                    recordFailure(queued.id, clock.now, "Snapshot export failed")
                }
              }
            }
          }
          Success(Done)
        }
    }
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit val tc: TraceContext = TraceContext.empty
    stopExports()
    Seq(
      AsyncCloseable(
        "waiting for onboarding snapshot exports to finish",
        queueTerminated,
        timeouts.shutdownShort,
      )
    )
  }
}

object SvOnboardingSnapshotService {
  sealed trait SnapshotKey
  object SnapshotKey {
    final case class Acs(synchronizerId: SynchronizerId, party: PartyId, activationTime: Instant)
        extends SnapshotKey
    final case class SequencerOnboardingState(
        synchronizerId: SynchronizerId,
        sequencerId: SequencerId,
    ) extends SnapshotKey
  }

  sealed trait SnapshotState
  object SnapshotState {
    final case class Queued(queuedAt: CantonTimestamp) extends SnapshotState
    final case class Exporting(queuedAt: CantonTimestamp, startedAt: CantonTimestamp)
        extends SnapshotState
    final case class Ready(file: Path) extends SnapshotState
    final case class Failed(reason: String) extends SnapshotState
  }

  private final case class Job(
      key: SnapshotKey,
      queuedAt: CantonTimestamp,
      startedAt: Option[CantonTimestamp],
  )

  private final case class Snapshot(createdAt: CantonTimestamp, stored: Stored)
  private sealed trait Stored
  private object Stored {
    final case class Finished(key: SnapshotKey, file: Path) extends Stored
    final case class Unusable(reason: String) extends Stored
    final case class Failed(reason: String) extends Stored
  }

  private final case class QueuedExport(
      id: SvOnboardingSnapshotId,
      file: Path,
      exportTo: Path => Future[Unit],
      traceContext: TraceContext,
  )

  private implicit val acsKeyEncoder: Encoder[SnapshotKey.Acs] = deriveEncoder
  private implicit val acsKeyDecoder: Decoder[SnapshotKey.Acs] = deriveDecoder
  private implicit val sequencerKeyEncoder: Encoder[SnapshotKey.SequencerOnboardingState] =
    deriveEncoder
  private implicit val sequencerKeyDecoder: Decoder[SnapshotKey.SequencerOnboardingState] =
    deriveDecoder
  private implicit val keyEncoder: Encoder[SnapshotKey] = deriveEncoder
  private implicit val keyDecoder: Decoder[SnapshotKey] = deriveDecoder

  private final case class PersistedSnapshot(key: SnapshotKey, createdAt: String, size: Long)
  private implicit val encoder: Encoder[PersistedSnapshot] = deriveEncoder
  private implicit val decoder: Decoder[PersistedSnapshot] = deriveDecoder

  private final case class PersistedFailure(createdAt: String, reason: String)
  private implicit val failureEncoder: Encoder[PersistedFailure] = deriveEncoder
  private implicit val failureDecoder: Decoder[PersistedFailure] = deriveDecoder
}
