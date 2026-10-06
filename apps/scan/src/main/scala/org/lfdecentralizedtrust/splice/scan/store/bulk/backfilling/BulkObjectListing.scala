// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.NoScanWillHaveData
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection

import scala.concurrent.{ExecutionContext, Future}

sealed trait PeerListing[+T]

object PeerListing {
  case object NotAvailableYet extends PeerListing[Nothing]
  case object NoPeerWillHold extends PeerListing[Nothing]
  final case class Available[T](objects: T, holders: Seq[Uri]) extends PeerListing[T]
}

trait BulkObjectListing {
  def updateObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[BulkStorageObjects.UpdateObjectsPage]]

  def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[BulkStorageObjects.SnapshotObjects]]]
}

class BftBulkObjectListing(peerConnection: PeerBftScanConnection)(implicit ec: ExecutionContext)
    extends BulkObjectListing {

  override def updateObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[BulkStorageObjects.UpdateObjectsPage]] =
    fromHolders(
      peerConnection.connection.flatMap(
        _.listBulkUpdateHistoryObjectsWithHolders(
          startRecordTime,
          endRecordTime,
          pageSize,
          availableAt,
          ScanStorageConfig.Encoding.CompactJson,
        )
      )
    )

  override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[BulkStorageObjects.SnapshotObjects]]] =
    fromHolders(
      peerConnection.connection.flatMap(
        _.listBulkAcsSnapshotObjectsWithHolders(
          recordTime,
          ScanStorageConfig.Encoding.CompactJson,
        )
      )
    )

  private def fromHolders[T](call: Future[(T, List[Uri])]): Future[PeerListing[T]] =
    call
      .map[PeerListing[T]] { case (objects, holders) => PeerListing.Available(objects, holders) }
      .recover {
        case HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, _) =>
          PeerListing.NotAvailableYet
        case _: NoScanWillHaveData => PeerListing.NoPeerWillHold
      }
}
