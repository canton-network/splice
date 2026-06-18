// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.store.{S3BucketConnection, TimestampWithMigrationId}

import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotBulkStorageCommitFromStaging(
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    getBulkReader: => BulkStorageReader,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AcsSnapshotBulkStorageWriter
    with BulkStorageCommitFromStaging[TimestampWithMigrationId]
    with NamedLogging {
  override def getNextSnapshotTimestampAfter(
      last: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[Option[TimestampWithMigrationId]] = {
    getBulkReader
      .getTimestampOfStagingAcsSnapshotAfter(last.timestamp)
      .map(
        _.map(ts => TimestampWithMigrationId(ts, -1L))
      ) // migration IDs are not used in bulk storage, so we set it to a fake -1
  }

  override def shouldProcessSnapshotAt(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Boolean = true // all objects in staging should be moved to committed

  override def processSnapshotsFlow(implicit
      tc: TraceContext,
      actorSystem: ActorSystem,
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] = {
    processFlow(
      stagingS3Connection,
      committedS3Connection,
      ts =>
        getBulkReader
          .getStagingObjectsForAcsSnapshotAt(ts.timestamp)
          .map(objects => objects.objects),
    )
  }
}
