// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.store.S3BucketConnection

import scala.concurrent.ExecutionContext

class UpdateHistoryBulkStorageCommitFromStaging(
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    getBulkReader: => BulkStorageReader,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, actorSystem: ActorSystem)
    extends UpdateHistoryBulkStorageWriter
    with NamedLogging {
  override def processSegmentsFlow(implicit
      tc: TraceContext
  ): Flow[UpdatesSegment, UpdatesSegment, NotUsed] =
    BulkStorageCommitFromStaging(
      stagingS3Connection,
      committedS3Connection,
      segment =>
        getBulkReader
          .getStagingObjectsForUpdateHistorySegment(segment)
          .map(objects => objects.objects),
      loggerFactory,
    )
}
