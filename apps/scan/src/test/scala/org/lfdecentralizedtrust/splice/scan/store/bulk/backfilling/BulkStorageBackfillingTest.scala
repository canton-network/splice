// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.Sink
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageBackfillingConfig, ScanStorageConfigs}
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdatesSegment
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

class BulkStorageBackfillingTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  private val storageConfig = ScanStorageConfigs.scanStorageConfigV1
  private val migrationId = 0L

  private def ts(day: Int): CantonTimestamp =
    CantonTimestamp.tryFromInstant(Instant.parse(f"2026-01-$day%02dT00:00:00Z"))

  private def folder(fromDay: Int, toDay: Int): String =
    storageConfig.getSegmentFolder(ts(fromDay), Some(ts(toDay)))

  private def segment(fromDay: Int, toDay: Int): UpdatesSegment =
    UpdatesSegment(
      TimestampWithMigrationId(ts(fromDay), migrationId),
      TimestampWithMigrationId(ts(toDay), migrationId),
    )

  private def objects(folderName: String, n: Int): Seq[ObjectKeyAndChecksum] =
    (0 until n).map(i => ObjectKeyAndChecksum(s"$folderName/updates_compact_json_$i.zstd", s"d-$i"))

  private class InMemoryProgress extends BackfillingProgress {
    val updates = new AtomicReference[Option[UpdatesSegment]](None)
    val snapshots = new AtomicReference[Option[TimestampWithMigrationId]](None)
    val complete = new AtomicReference[Int](0)
    override def readUpdatesCursor(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(updates.get())
    override def persistUpdatesCursor(segment: UpdatesSegment)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ) = Future.successful(updates.set(Some(segment)))
    override def readSnapshotsCursor(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(snapshots.get())
    override def persistSnapshotsCursor(ts: TimestampWithMigrationId)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ) = Future.successful(snapshots.set(Some(ts)))
    override def isComplete(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(complete.get() > 0)
    override def markComplete()(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(complete.updateAndGet(_ + 1)).map(_ => ())
    override def resetCompletion()(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(complete.set(0))
  }

  private class FakeListing(
      folders: Seq[(String, Seq[ObjectKeyAndChecksum])],
      snapshotsByTime: Seq[(CantonTimestamp, Seq[ObjectKeyAndChecksum])],
  ) extends BulkObjectListing {
    override def updateObjectsPage(
        startRecordTime: CantonTimestamp,
        endRecordTime: CantonTimestamp,
        pageSize: Int,
        nextPageToken: Option[String],
    )(implicit tc: TraceContext): Future[BulkStorageObjects.UpdateObjectsPage] = {
      val inRange = folders.filter { case (name, _) =>
        val (from, to) = storageConfig.getStartAndEndTimestampsForFolder(name) match {
          case Right(range) => range
          case Left(err) => throw new IllegalStateException(err)
        }
        to > startRecordTime && from < endRecordTime
      }
      val afterToken = nextPageToken.fold(inRange)(token => inRange.filter(_._1 > token))
      val cumulative = afterToken.scanLeft(0)(_ + _._2.size).drop(1)
      val fitting = afterToken.zip(cumulative).takeWhile { case (_, total) => total <= pageSize }
      val page = if (fitting.isEmpty) afterToken.take(1) else fitting.map(_._1)
      val token = if (page.size < afterToken.size) page.lastOption.map(_._1) else None
      Future.successful(BulkStorageObjects.UpdateObjectsPage(page.flatMap(_._2), token))
    }

    override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
        tc: TraceContext
    ): Future[Option[BulkStorageObjects.SnapshotObjects]] =
      Future.successful(
        snapshotsByTime.lastOption.map { case (latest, _) =>
          if (recordTime > latest)
            BulkStorageObjects.SnapshotObjects(latest, snapshotsByTime.last._2)
          else {
            val grid = storageConfig.computeBulkSnapshotTimeAtOrBefore(recordTime)
            BulkStorageObjects.SnapshotObjects(
              grid,
              snapshotsByTime.collectFirst { case (t, objs) if t == grid => objs }.getOrElse(Seq.empty),
            )
          }
        }
      )
  }

  private class RecordingCopier extends ObjectCopier {
    val copied = new AtomicReference[Vector[String]](Vector.empty)
    override def copy(objects: Seq[ObjectKeyAndChecksum])(implicit tc: TraceContext) =
      Future.successful(copied.updateAndGet(_ ++ objects.map(_.key))).map(_ => ())
  }

  private val folders = Seq(
    folder(1, 2) -> objects(folder(1, 2), 2),
    folder(2, 3) -> objects(folder(2, 3), 2),
    folder(3, 4) -> objects(folder(3, 4), 1),
  )
  private val snapshots = Seq(
    ts(2) -> Seq(ObjectKeyAndChecksum(s"${folder(2, 3)}/ACS_compact_json_0.zstd", "s-2")),
    ts(3) -> Seq(ObjectKeyAndChecksum(s"${folder(3, 4)}/ACS_compact_json_0.zstd", "s-3")),
  )

  private def run(progress: InMemoryProgress, copier: RecordingCopier, pageSize: Int = 3) =
    new BulkStorageBackfilling(
      BulkStorageBackfillingConfig(enabled = true, pageSize = pageSize),
      storageConfig,
      migrationId,
      new FakeListing(folders, snapshots),
      copier,
      progress,
      CatchUpWithPeers,
      loggerFactory,
    ).mksrc().runWith(Sink.seq)

  "BulkStorageBackfilling" should {
    "copy every update object and snapshot in order, then mark completion once" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      run(progress, copier).map { steps =>
        copier.copied.get() shouldBe (folders.flatMap(_._2) ++ snapshots.flatMap(_._2)).map(_.key)
        steps should contain(BulkStorageBackfilling.SnapshotSkipped(ts(1)))
        progress.updates.get() shouldBe Some(segment(3, 4))
        progress.snapshots.get() shouldBe Some(TimestampWithMigrationId(ts(3), migrationId))
        progress.complete.get() shouldBe 1
        steps.last shouldBe BulkStorageBackfilling.Completed
      }
    }

    "page through the update folders with the peers' page token" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      run(progress, copier, pageSize = 2).map { steps =>
        steps.collect { case s: BulkStorageBackfilling.UpdatesPageCopied => s.objects } shouldBe
          Seq(2, 2, 1)
        copier.copied.get().size shouldBe 7
      }
    }

    "resume from the persisted cursors and skip what was already copied" in {
      val progress = new InMemoryProgress
      progress.updates.set(Some(segment(2, 3)))
      progress.snapshots.set(Some(TimestampWithMigrationId(ts(2), migrationId)))
      val copier = new RecordingCopier
      run(progress, copier).map { _ =>
        copier.copied.get() shouldBe (folders(2)._2 ++ snapshots(1)._2).map(_.key)
        progress.complete.get() shouldBe 1
      }
    }

    "do nothing once complete" in {
      val progress = new InMemoryProgress
      progress.complete.set(1)
      val copier = new RecordingCopier
      run(progress, copier).map { steps =>
        steps shouldBe empty
        copier.copied.get() shouldBe empty
      }
    }

    "finish when the peers have no objects at all" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      new BulkStorageBackfilling(
        BulkStorageBackfillingConfig(enabled = true),
        storageConfig,
        migrationId,
        new FakeListing(Seq.empty, Seq.empty),
        copier,
        progress,
        CatchUpWithPeers,
        loggerFactory,
      ).mksrc().runWith(Sink.seq).map { steps =>
        copier.copied.get() shouldBe empty
        progress.complete.get() shouldBe 1
        steps.last shouldBe BulkStorageBackfilling.Completed
      }
    }
  }
}
