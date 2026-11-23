// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.github.luben.zstd.ZstdInputStream
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Framing, Source, StreamConverters}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2
import org.lfdecentralizedtrust.splice.scan.admin.http.ProtobufJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** Reads the update objects that [[UpdateHistorySegmentBulkStorage]] writes
  * back into updates. Only the `protobuf_json` encoding is lossless, so only it
  * is decoded.
  *
  * An object is a concatenation of zstd frames that hold NDJSON, one update per
  * line, each line ending in a newline.
  */
object BulkStorageUpdateDecoder {

  private val encoding = ScanStorageConfig.Encoding.ProtobufJson

  private val objectIndex = (".*" + "updates_" + encoding.key + "_(\\d+)\\.zstd").r

  /** The `protobuf_json` update objects among `keys`, in the order they were
    * written. The index in the object key is not zero-padded, so it is compared
    * as a number.
    */
  def protobufJsonObjectsInOrder(keys: Seq[String]): Seq[String] =
    keys
      .collect { case key @ objectIndex(index) => (BigInt(index), key) }
      .sortBy(_._1)
      .map(_._2)

  /** Decodes the updates in one object, streaming: the object is not held in
    * memory as a whole.
    *
    * @param objectKey only used in error messages
    * @param maxLineBytes the stream fails on a line longer than this
    * @param readTimeout how long to wait for more bytes of the object before
    *   failing the stream
    */
  def decodeObject(
      objectKey: String,
      compressed: Source[ByteString, ?],
      maxLineBytes: Int,
      readTimeout: FiniteDuration,
  )(implicit mat: Materializer): Source[TreeUpdateWithMigrationId, NotUsed] =
    Source
      .lazySource { () =>
        val compressedStream = compressed.runWith(StreamConverters.asInputStream(readTimeout))
        // Closing the stream cancels the download, also when the decompressing
        // stream cannot be opened.
        StreamConverters.fromInputStream { () =>
          try new ZstdInputStream(compressedStream)
          catch {
            case NonFatal(e) =>
              compressedStream.close()
              throw e
          }
        }
      }
      .via(Framing.delimiter(ByteString("\n"), maxLineBytes, allowTruncation = false))
      .zipWithIndex
      .map { case (line, index) => decodeLine(objectKey, index + 1, line.utf8String) }
      .mapMaterializedValue(_ => NotUsed)

  private def decodeLine(
      objectKey: String,
      lineNumber: Long,
      line: String,
  ): TreeUpdateWithMigrationId = {
    def fail(cause: Throwable) =
      throw new IllegalStateException(
        s"Cannot decode line $lineNumber of bulk storage object $objectKey: ${cause.getMessage}",
        cause,
      )
    io.circe.parser.decode[UpdateHistoryItemV2](line) match {
      case Left(err) => fail(err)
      case Right(item) =>
        try ProtobufJsonScanHttpEncodings.httpToLapiUpdate(item)
        catch { case NonFatal(e) => fail(e) }
    }
  }
}
