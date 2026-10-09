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
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import scala.concurrent.{ExecutionContext, Future}

sealed trait PeerListing[+T]

object PeerListing {
  case object NotAvailableYet extends PeerListing[Nothing]
  case object NoPeerWillHold extends PeerListing[Nothing]
  final case class Available[T](value: T) extends PeerListing[T]
}

final case class ObjectsOnPeers(objects: Seq[ObjectKeyAndChecksum], peers: Seq[Uri])

final case class SnapshotOnPeers(recordTime: CantonTimestamp, perEncoding: Seq[ObjectsOnPeers])

trait BulkObjectListing {
  def updateObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[Seq[ObjectsOnPeers]]]

  def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[SnapshotOnPeers]]]
}

class BftBulkObjectListing(peerConnection: PeerBftScanConnection)(implicit ec: ExecutionContext)
    extends BulkObjectListing {

  override def updateObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[Seq[ObjectsOnPeers]]] =
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkUpdateHistoryObjectsWithPeers(
          startRecordTime,
          endRecordTime,
          pageSize,
          availableAt,
          encoding,
        )
      )
    )(perEncoding =>
      PeerListing.Available(perEncoding.map { case (page, peers) =>
        ObjectsOnPeers(page.objects, peers)
      })
    )

  override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[SnapshotOnPeers]]] =
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkAcsSnapshotObjectsWithPeers(recordTime, encoding)
      )
    )(sameSnapshotInEveryEncoding)

  private def sameSnapshotInEveryEncoding(
      perEncoding: Seq[(Option[BulkStorageObjects.SnapshotObjects], Seq[Uri])]
  ): PeerListing[Option[SnapshotOnPeers]] = {
    val recordTimes = perEncoding.map { case (snapshot, _) => snapshot.map(_.recordTime) }
    recordTimes.distinct match {
      case Seq(None) => PeerListing.Available(None)
      case Seq(Some(recordTime)) =>
        val objectsOnPeers = perEncoding.collect { case (Some(snapshot), peers) =>
          ObjectsOnPeers(snapshot.objects, peers)
        }
        PeerListing.Available(Some(SnapshotOnPeers(recordTime, objectsOnPeers)))
      case _ => PeerListing.NotAvailableYet
    }
  }

  private def inEveryEncoding[T, U](list: ScanStorageConfig.Encoding => Future[(T, List[Uri])])(
      merge: Seq[(T, Seq[Uri])] => PeerListing[U]
  ): Future[PeerListing[U]] =
    Future
      .traverse(ScanStorageConfig.Encoding.all.toList)(encoding => withPeers(list(encoding)))
      .map { listings =>
        val available = listings.collect { case PeerListing.Available(listed) => listed }
        if (listings.forall(_ == PeerListing.NoPeerWillHold)) PeerListing.NoPeerWillHold
        else if (available.size < listings.size) PeerListing.NotAvailableYet
        else merge(available)
      }

  private def withPeers[T](call: Future[(T, List[Uri])]): Future[PeerListing[(T, Seq[Uri])]] =
    call
      .map[PeerListing[(T, Seq[Uri])]] { case (value, peers) =>
        PeerListing.Available((value, peers))
      }
      .recover {
        case HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, _) =>
          PeerListing.NotAvailableYet
        case _: NoScanWillHaveData => PeerListing.NoPeerWillHold
      }
}
