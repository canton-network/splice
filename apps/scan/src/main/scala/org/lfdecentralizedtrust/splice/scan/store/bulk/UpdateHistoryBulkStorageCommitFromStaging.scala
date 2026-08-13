// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SpliceLedgerClient}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.{S3BucketConnection, TimestampWithMigrationId}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContextExecutor, Future}

class UpdateHistoryBulkStorageCommitFromStaging(
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    bulkStorageReader: BulkStorageReader,
    appConfig: BulkStorageConfig,
    store: ScanStore,
    svName: String,
    ledgerClient: SpliceLedgerClient,
    scanConnection: PeerBftScanConnection,
    automationConfig: AutomationConfig,
    upgradesConfig: UpgradesConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    actorSystem: ActorSystem,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends UpdateHistoryBulkStorageWriter
    with NamedLogging {
  override def processSegmentsFlow(implicit
      tc: TraceContext
  ): Flow[UpdatesSegment, UpdatesSegment, NotUsed] =
    BulkStorageCommitFromStaging(
      stagingS3Connection,
      committedS3Connection,
      segment =>
        bulkStorageReader
          .getStagingObjectsForUpdateHistorySegment(segment)
          .map(objects => objects.objects)
          .recover {
            // If we restart after all objects have been moved already, the staging objects will not be found.
            case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
              Seq.empty
          },
      appConfig,
      store,
      svName,
      ledgerClient,
      scanConnection,
      automationConfig,
      upgradesConfig,
      clock,
      retryProvider,
      loggerFactory,
    )

  override def getNextSegmentAfter(
      after: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Future[Option[UpdatesSegment]] = {
    bulkStorageReader
      .getStagingSegmentStartingAt(
        after.map(_.toTimestamp.timestamp)
      )
      .map(
        _.map(segment =>
          // We don't care about migration IDs in commit-from-staging pipelines
          UpdatesSegment(
            TimestampWithMigrationId(segment._1, -1L),
            TimestampWithMigrationId(segment._2, -1L),
          )
        )
      )
  }
}
