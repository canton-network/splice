// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection

import scala.concurrent.{ExecutionContext, Future}

trait BulkObjectListing {
  def updateObjectsPage(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      nextPageToken: Option[String],
  )(implicit tc: TraceContext): Future[BulkStorageObjects.UpdateObjectsPage]

  def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[BulkStorageObjects.SnapshotObjects]]
}

class BftBulkObjectListing(peerConnection: PeerBftScanConnection)(implicit ec: ExecutionContext)
    extends BulkObjectListing {

  override def updateObjectsPage(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      nextPageToken: Option[String],
  )(implicit tc: TraceContext): Future[BulkStorageObjects.UpdateObjectsPage] =
    peerConnection.connection.flatMap(
      _.listBulkUpdateHistoryObjects(
        startRecordTime,
        endRecordTime,
        pageSize,
        nextPageToken,
        Some(ScanStorageConfig.Encoding.CompactJson.damlValueEncoding),
      )
    )

  override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[BulkStorageObjects.SnapshotObjects]] =
    peerConnection.connection.flatMap(
      _.listBulkAcsSnapshotObjects(
        recordTime,
        Some(ScanStorageConfig.Encoding.CompactJson.damlValueEncoding),
      )
    )
}
