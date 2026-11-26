// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.github.luben.zstd.ZstdOutputStream
import io.circe.syntax.*
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  ProtobufJsonScanHttpEncodings,
  ScanHttpEncodings,
  ScanJsonSupport,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.store.{StoreTestBase, TreeUpdateWithMigrationId}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse

import java.io.ByteArrayOutputStream
import java.time.Instant
import scala.concurrent.duration.*

class BulkStorageUpdateDecoderTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem {

  private val party = mkPartyId("alice")
  private val otherDomain = com.digitalasset.canton.topology.SynchronizerId
    .tryFromString("other::domain")

  private def transaction(i: Int, migrationId: Long = 0L) = {
    val recordTime = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i.toLong)
    val tx = mkCreateTx(
      1,
      Seq(appRewardCoupon(round = 0, provider = party, contractId = validContractId(i))),
      recordTime,
      Seq(party),
      dummyDomain,
      "",
      recordTime,
      updateId = s"tx-$i",
    )
    TreeUpdateWithMigrationId(
      UpdateHistoryResponse(TransactionTreeUpdate(tx), dummyDomain),
      migrationId,
    )
  }

  private def assignment(i: Int) = {
    val reassignment = mkReassignment(
      1,
      toAssignEvent(
        appRewardCoupon(round = 0, provider = party, contractId = validContractId(i)),
        s"rid-$i",
        otherDomain,
        dummyDomain,
        1L,
        Map.empty,
      ),
      com.digitalasset.canton.data.CantonTimestamp.assertFromInstant(
        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i.toLong)
      ),
    )
    TreeUpdateWithMigrationId(
      UpdateHistoryResponse(ReassignmentUpdate(reassignment), dummyDomain),
      0L,
    )
  }

  private def unassignment(i: Int) = {
    val reassignment = mkReassignment(
      1,
      toUnassignEvent(
        appRewardCoupon(round = 0, provider = party, contractId = validContractId(i)).contractId,
        s"rid-$i",
        dummyDomain,
        otherDomain,
        1L,
      ),
      com.digitalasset.canton.data.CantonTimestamp.assertFromInstant(
        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i.toLong)
      ),
    )
    TreeUpdateWithMigrationId(
      UpdateHistoryResponse(ReassignmentUpdate(reassignment), dummyDomain),
      0L,
    )
  }

  /** The line [[UpdateHistorySegmentBulkStorage]] writes for an update. */
  private def encode(update: TreeUpdateWithMigrationId): String = {
    import ScanJsonSupport.*
    ScanHttpEncodings
      .encodeUpdateV2(
        update,
        ScanStorageConfig.Encoding.ProtobufJson.damlValueEncoding,
        ScanHttpEncodings.V1,
      )
      .asJson
      .noSpacesSortKeys
  }

  /** What bulk storage gives back for an update: its lossless encoding,
    * decoded.
    */
  private def roundTripped(update: TreeUpdateWithMigrationId): TreeUpdateWithMigrationId =
    ProtobufJsonScanHttpEncodings.httpToLapiUpdate(
      ScanHttpEncodings.encodeUpdateV2(
        update,
        ScanStorageConfig.Encoding.ProtobufJson.damlValueEncoding,
        ScanHttpEncodings.V1,
      )
    )

  private def zstdFrame(text: String): ByteString = {
    val out = new ByteArrayOutputStream()
    val zstd = new ZstdOutputStream(out)
    zstd.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    zstd.close()
    ByteString(out.toByteArray)
  }

  /** An object as written to bulk storage: one zstd frame per group of lines,
    * each line ending in a newline.
    */
  private def objectOf(frames: Seq[Seq[TreeUpdateWithMigrationId]]): ByteString =
    frames
      .map(lines => zstdFrame(lines.map(encode(_) + "\n").mkString))
      .foldLeft(ByteString.empty)(_ ++ _)

  private def decode(
      key: String,
      bytes: ByteString,
      chunkSize: Int = 1000,
      maxLineBytes: Int = 1 << 20,
  ) =
    BulkStorageUpdateDecoder
      .decodeObject(key, Source(bytes.grouped(chunkSize).toList), maxLineBytes, 10.seconds)
      .runWith(Sink.seq)

  "BulkStorageUpdateDecoder" should {

    "decode every update type from a multi-frame object, in order" in {
      val updates =
        Seq(transaction(1), assignment(2), unassignment(3), transaction(4, migrationId = 1L))
      val bytes = objectOf(Seq(updates.take(2), updates.drop(2)))
      decode("segment/updates_protobuf_json_0.zstd", bytes).futureValue shouldBe updates.map(
        roundTripped
      )
    }

    "decode transactions without loss" in {
      val updates = (1 to 20).map(transaction(_))
      decode(
        "segment/updates_protobuf_json_0.zstd",
        objectOf(Seq(updates)),
        chunkSize = 7,
      ).futureValue shouldBe updates
    }

    "fail on a line that is not a valid update, naming the object and the line" in {
      val bytes = zstdFrame(encode(transaction(1)) + "\n" + "{}\n")
      val failure = decode("segment/updates_protobuf_json_3.zstd", bytes).failed.futureValue
      failure.getMessage should (include("line 2") and include(
        "segment/updates_protobuf_json_3.zstd"
      ))
    }

    "fail on a truncated object" in {
      val bytes = zstdFrame(encode(transaction(1)))
      decode("segment/updates_protobuf_json_0.zstd", bytes).failed.futureValue shouldBe a[
        org.apache.pekko.stream.scaladsl.Framing.FramingException
      ]
    }

    "fail on a line longer than the limit" in {
      val bytes = objectOf(Seq(Seq(transaction(1))))
      decode(
        "segment/updates_protobuf_json_0.zstd",
        bytes,
        maxLineBytes = 100,
      ).failed.futureValue shouldBe a[
        org.apache.pekko.stream.scaladsl.Framing.FramingException
      ]
    }

    "order the protobuf_json objects of a segment by their numeric index" in {
      val folder = "2026-01-01T00:00:00Z~2026-01-02T00:00:00Z"
      def key(encoding: ScanStorageConfig.Encoding, i: Int) =
        s"$folder/${encoding.storageKey("updates", i)}"
      val keys = Seq(10, 2, 0, 1, 11).flatMap(i =>
        Seq(
          key(ScanStorageConfig.Encoding.LedgerApiJson, i),
          key(ScanStorageConfig.Encoding.ProtobufJson, i),
        )
      ) :+ s"$folder/${ScanStorageConfig.Encoding.ProtobufJson.storageKey("ACS", 0)}"
      BulkStorageUpdateDecoder.protobufJsonObjectsInOrder(keys) shouldBe Seq(0, 1, 2, 10, 11).map(
        key(ScanStorageConfig.Encoding.ProtobufJson, _)
      )
    }
  }
}
