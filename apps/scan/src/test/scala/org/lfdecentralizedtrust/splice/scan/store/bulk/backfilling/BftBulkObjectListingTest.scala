// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig.Encoding
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class BftBulkObjectListingTest extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val tc: TraceContext = TraceContext.empty

  private def scanUrl(name: String) = Uri(s"http://${name}scan.example.com")
  private val sv1 = scanUrl("sv1")
  private val sv2 = scanUrl("sv2")
  private val sv3 = scanUrl("sv3")
  private val at = CantonTimestamp.Epoch
  private def obj(key: String) = ObjectKeyAndChecksum(key, s"checksum-of-$key")
  private val notYet = HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, "not yet")

  private def listingOver(connection: BftScanConnection): BftBulkObjectListing = {
    val peerConnection = mock[PeerBftScanConnection]
    when(peerConnection.connection(any[TraceContext])).thenReturn(Future.successful(connection))
    new BftBulkObjectListing(peerConnection)
  }

  private def updatesIn(
      connection: BftScanConnection,
      encoding: Encoding,
      result: Future[(BulkStorageObjects.UpdateObjectsPage, List[Uri])],
  ) =
    when(
      connection.listBulkUpdateHistoryObjectsWithHolders(
        any[CantonTimestamp],
        any[CantonTimestamp],
        any[Int],
        any[CantonTimestamp],
        eqTo(encoding),
      )(any[ExecutionContext], any[TraceContext])
    ).thenReturn(result)

  private def snapshotIn(
      connection: BftScanConnection,
      encoding: Encoding,
      result: Future[(Option[BulkStorageObjects.SnapshotObjects], List[Uri])],
  ) =
    when(
      connection.listBulkAcsSnapshotObjectsWithHolders(any[CantonTimestamp], eqTo(encoding))(
        any[ExecutionContext],
        any[TraceContext],
      )
    ).thenReturn(result)

  private def page(keys: String*) = BulkStorageObjects.UpdateObjectsPage(keys.map(obj), None)
  private def snapshot(recordTime: CantonTimestamp, keys: String*) =
    Some(BulkStorageObjects.SnapshotObjects(recordTime, keys.map(obj)))

  "BftBulkObjectListing" should {
    "list update objects in every encoding, from the peers that agreed on either" in {
      val connection = mock[BftScanConnection]
      updatesIn(
        connection,
        Encoding.CompactJson,
        Future.successful((page("s/updates_compact_json_0.zstd"), List(sv1, sv2))),
      )
      updatesIn(
        connection,
        Encoding.ProtobufJson,
        Future.successful((page("s/updates_protobuf_json_0.zstd"), List(sv2, sv3))),
      )

      listingOver(connection).updateObjects(at, at, 10, at).futureValue shouldBe
        PeerListing.Available(
          page("s/updates_compact_json_0.zstd", "s/updates_protobuf_json_0.zstd"),
          Seq(sv1, sv2, sv3),
        )
    }

    "wait until the peers list every encoding" in {
      val connection = mock[BftScanConnection]
      updatesIn(
        connection,
        Encoding.CompactJson,
        Future.successful((page("s/updates_compact_json_0.zstd"), List(sv1, sv2))),
      )
      updatesIn(connection, Encoding.ProtobufJson, Future.failed(notYet))

      listingOver(connection).updateObjects(at, at, 10, at).futureValue shouldBe
        PeerListing.NotAvailableYet
    }

    "list a snapshot in every encoding" in {
      val connection = mock[BftScanConnection]
      snapshotIn(
        connection,
        Encoding.CompactJson,
        Future.successful((snapshot(at, "s/ACS_compact_json_0.zstd"), List(sv1, sv2))),
      )
      snapshotIn(
        connection,
        Encoding.ProtobufJson,
        Future.successful((snapshot(at, "s/ACS_protobuf_json_0.zstd"), List(sv1, sv2))),
      )

      listingOver(connection).snapshotObjectsAtOrBefore(at).futureValue shouldBe
        PeerListing.Available(
          snapshot(at, "s/ACS_compact_json_0.zstd", "s/ACS_protobuf_json_0.zstd"),
          Seq(sv1, sv2),
        )
    }

    "wait when the encodings list different snapshots" in {
      val connection = mock[BftScanConnection]
      snapshotIn(
        connection,
        Encoding.CompactJson,
        Future.successful((snapshot(at.plusSeconds(3600), "s/ACS_compact_json_0.zstd"), List(sv1))),
      )
      snapshotIn(
        connection,
        Encoding.ProtobufJson,
        Future.successful((snapshot(at, "s/ACS_protobuf_json_0.zstd"), List(sv1))),
      )

      listingOver(connection).snapshotObjectsAtOrBefore(at).futureValue shouldBe
        PeerListing.NotAvailableYet
    }
  }
}
