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

final case class HeldObjects(objects: Seq[ObjectKeyAndChecksum], holders: Seq[Uri])

final case class HeldSnapshot(recordTime: CantonTimestamp, encodings: Seq[HeldObjects])

trait BulkObjectListing {
  def updateObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[Seq[HeldObjects]]]

  def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[HeldSnapshot]]]
}

class BftBulkObjectListing(peerConnection: PeerBftScanConnection)(implicit ec: ExecutionContext)
    extends BulkObjectListing {

  override def updateObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[Seq[HeldObjects]]] =
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
    )(perEncoding =>
      PeerListing.Available(perEncoding.map { case (page, holders) =>
        HeldObjects(page.objects, holders)
      })
    )

  override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[HeldSnapshot]]] =
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkAcsSnapshotObjectsWithHolders(recordTime, encoding)
      )
    )(sameSnapshotInEveryEncoding)

  private def sameSnapshotInEveryEncoding(
      perEncoding: Seq[(Option[BulkStorageObjects.SnapshotObjects], Seq[Uri])]
  ): PeerListing[Option[HeldSnapshot]] =
    perEncoding.map(_._1.map(_.recordTime)).distinct match {
      case Seq(None) => PeerListing.Available(None)
      case Seq(Some(recordTime)) =>
        val encodings = perEncoding.collect { case (Some(snapshot), holders) =>
          HeldObjects(snapshot.objects, holders)
        }
        PeerListing.Available(Some(HeldSnapshot(recordTime, encodings)))
      case _ => PeerListing.NotAvailableYet
    }

  private def inEveryEncoding[T, U](list: ScanStorageConfig.Encoding => Future[(T, List[Uri])])(
      merge: Seq[(T, Seq[Uri])] => PeerListing[U]
  ): Future[PeerListing[U]] =
    Future
      .traverse(ScanStorageConfig.Encoding.all.toList)(encoding => withHolders(list(encoding)))
      .map { listings =>
        val available = listings.collect { case PeerListing.Available(listed) => listed }
        if (listings.forall(_ == PeerListing.NoPeerWillHold)) PeerListing.NoPeerWillHold
        else if (available.size < listings.size) PeerListing.NotAvailableYet
        else merge(available)
      }

  private def withHolders[T](call: Future[(T, List[Uri])]): Future[PeerListing[(T, Seq[Uri])]] =
    call
      .map[PeerListing[(T, Seq[Uri])]] { case (value, holders) =>
        PeerListing.Available((value, holders))
      }
      .recover {
        case HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, _) =>
          PeerListing.NotAvailableYet
        case _: NoScanWillHaveData => PeerListing.NoPeerWillHold
      }
}
