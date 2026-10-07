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
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkUpdateHistoryObjectsWithHolders(
          startRecordTime,
          endRecordTime,
          pageSize,
          availableAt,
          encoding,
        )
      )
    )(pages => Some(BulkStorageObjects.UpdateObjectsPage(pages.flatMap(_.objects), None)))

  override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[BulkStorageObjects.SnapshotObjects]]] =
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkAcsSnapshotObjectsWithHolders(recordTime, encoding)
      )
    )(sameSnapshot)

  private def sameSnapshot(
      snapshots: Seq[Option[BulkStorageObjects.SnapshotObjects]]
  ): Option[Option[BulkStorageObjects.SnapshotObjects]] =
    snapshots.flatten match {
      case Seq() => Some(None)
      case found @ (first +: _)
          if found.size == snapshots.size && found.forall(_.recordTime == first.recordTime) =>
        Some(Some(BulkStorageObjects.SnapshotObjects(first.recordTime, found.flatMap(_.objects))))
      case _ => None
    }

  private def inEveryEncoding[T](list: ScanStorageConfig.Encoding => Future[(T, List[Uri])])(
      merge: Seq[T] => Option[T]
  ): Future[PeerListing[T]] =
    Future
      .traverse(ScanStorageConfig.Encoding.all.toList)(encoding => fromHolders(list(encoding)))
      .map { listings =>
        val available = listings.collect { case PeerListing.Available(objects, holders) =>
          (objects, holders)
        }
        if (listings.contains(PeerListing.NoPeerWillHold)) PeerListing.NoPeerWillHold
        else if (available.size < listings.size) PeerListing.NotAvailableYet
        else
          merge(available.map(_._1)).fold[PeerListing[T]](PeerListing.NotAvailableYet)(
            PeerListing.Available(_, available.flatMap(_._2).distinct)
          )
      }

  private def fromHolders[T](call: Future[(T, List[Uri])]): Future[PeerListing[T]] =
    call
      .map[PeerListing[T]] { case (objects, holders) => PeerListing.Available(objects, holders) }
      .recover {
        case HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, _) =>
          PeerListing.NotAvailableYet
        case _: NoScanWillHaveData => PeerListing.NoPeerWillHold
      }
}
