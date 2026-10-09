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
  final case class Available[T](value: T, holders: Seq[Uri]) extends PeerListing[T]
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
    )((pages, holders) =>
      PeerListing.Available(
        BulkStorageObjects.UpdateObjectsPage(pages.flatMap(_.objects), None),
        holders,
      )
    )

  override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[BulkStorageObjects.SnapshotObjects]]] =
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkAcsSnapshotObjectsWithHolders(recordTime, encoding)
      )
    )(sameSnapshotInEveryEncoding)

  private def sameSnapshotInEveryEncoding(
      snapshots: Seq[Option[BulkStorageObjects.SnapshotObjects]],
      holders: Seq[Uri],
  ): PeerListing[Option[BulkStorageObjects.SnapshotObjects]] =
    snapshots.map(_.map(_.recordTime)).distinct match {
      case Seq(None) => PeerListing.Available(None, holders)
      case Seq(Some(recordTime)) =>
        val objects = snapshots.flatten.flatMap(_.objects)
        PeerListing.Available(
          Some(BulkStorageObjects.SnapshotObjects(recordTime, objects)),
          holders,
        )
      case _ => PeerListing.NotAvailableYet
    }

  private def inEveryEncoding[T, U](list: ScanStorageConfig.Encoding => Future[(T, List[Uri])])(
      merge: (Seq[T], Seq[Uri]) => PeerListing[U]
  ): Future[PeerListing[U]] =
    Future
      .traverse(ScanStorageConfig.Encoding.all.toList)(encoding => fromHolders(list(encoding)))
      .map { listings =>
        val available = listings.collect { case PeerListing.Available(value, holders) =>
          (value, holders)
        }
        if (listings.forall(_ == PeerListing.NoPeerWillHold)) PeerListing.NoPeerWillHold
        else if (available.size < listings.size) PeerListing.NotAvailableYet
        else merge(available.map(_._1), available.flatMap(_._2).distinct)
      }

  private def fromHolders[T](call: Future[(T, List[Uri])]): Future[PeerListing[T]] =
    call
      .map[PeerListing[T]] { case (value, holders) => PeerListing.Available(value, holders) }
      .recover {
        case HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, _) =>
          PeerListing.NotAvailableYet
        case _: NoScanWillHaveData => PeerListing.NoPeerWillHold
      }
}
