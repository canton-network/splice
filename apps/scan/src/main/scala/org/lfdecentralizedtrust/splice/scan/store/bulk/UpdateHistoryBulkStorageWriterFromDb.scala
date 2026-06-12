// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, UpdateHistory}

import scala.concurrent.ExecutionContext

class UpdateHistoryBulkStorageWriterFromDb(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    updateHistory: UpdateHistory,
    s3Connection: S3BucketConnection,
    historyMetrics: HistoryMetrics,
    val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends UpdateHistoryBulkStorageWriter
    with NamedLogging
    with Spanning {
//  override val description = "Update History Bulk Storage (Staging)"
//  override val kvStoreKey = "latest_updates_segment_in_bulk_storage"
//
//  override val processedSegmentMetric: MetricHandle.Gauge[CantonTimestamp] =
//    historyMetrics.BulkStorage.latestUpdatesSegment

  override def processSegment(
      segment: UpdatesSegment
  )(implicit tc: TraceContext): Flow[UpdatesSegment, UpdatesSegment, NotUsed] = {
    UpdateHistorySegmentBulkStorage
      .asFlow(
        storageConfig,
        appConfig,
        updateHistory,
        s3Connection,
        historyMetrics,
        loggerFactory,
      )
      .map(keys => {
        logger.debug(
          s"Successfully dumped updates segment $segment to bulk storage, with object keys: $keys"
        )
        segment
      })
  }

}
