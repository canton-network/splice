// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.OptionT
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.data.CantonTimestamp
import io.circe.{Codec, Decoder, Encoder}
import org.lfdecentralizedtrust.splice.store.db.{DbKeyValueStore, StoreDescriptor}

import scala.concurrent.{ExecutionContext, Future}

trait KeyValueStore extends NamedLogging {

  def setValue[T](key: String, value: T)(implicit
      tc: TraceContext,
      encoder: Encoder[T],
  ): Future[Unit]

  def setValueIfNotExists[T](key: String, value: T)(implicit
      tc: TraceContext,
      encoder: Encoder[T],
  ): Future[Boolean]

  def getValue[T](
      key: String
  )(implicit tc: TraceContext, decoder: Decoder[T]): OptionT[Future, Decoder.Result[T]]

  def deleteKey(key: String)(implicit
      tc: TraceContext
  ): Future[Unit]

  def readValueAndLogOnDecodingFailure[T: Decoder](
      key: String
  )(implicit tc: TraceContext, ec: ExecutionContext): OptionT[Future, T] = {
    getValue[T](key)
      .subflatMap(
        _.fold(
          { failure =>
            logger.warn(s"Failed to decode $key from the db $failure")
            None
          },
          _.some,
        )
      )
  }

}

object KeyValueStore {

  implicit val timestampCodec: Codec[CantonTimestamp] =
    Codec
      .from[Long](implicitly, implicitly)
      .iemap(timestamp => CantonTimestamp.fromProtoPrimitive(timestamp).left.map(_.message))(
        _.toProtoPrimitive
      )

  def apply(
      descriptor: StoreDescriptor,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      lc: ErrorLoggingContext,
      cc: CloseContext,
      tc: TraceContext,
  ): Future[KeyValueStore] = {
    storage match {
      case storage: DbStorage =>
        DbKeyValueStore(
          descriptor,
          storage,
          loggerFactory,
        )

      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  }
}
