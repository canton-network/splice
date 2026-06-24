// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.google.protobuf.ByteString

import java.io.OutputStream
import scala.concurrent.{Future, Promise}

/** A [[io.grpc.stub.StreamObserver]] that writes each streamed chunk to the given
  * [[java.io.OutputStream]] instead of accumulating the whole stream in memory. This keeps the
  * memory footprint bounded regardless of how large the streamed payload (e.g. an ACS snapshot)
  * is.
  *
  * The caller is responsible for opening the [[OutputStream]] and for closing it once
  * [[resultFuture]] has completed (in both the success and failure cases).
  */
class ChunkWritingObserver[T](out: OutputStream, chunkOf: T => ByteString)
    extends io.grpc.stub.StreamObserver[T] {
  private val promise: Promise[Unit] = Promise[Unit]()

  def resultFuture: Future[Unit] = promise.future

  override def onNext(value: T): Unit =
    try chunkOf(value).writeTo(out)
    catch { case t: Throwable => promise.tryFailure(t).discard }

  override def onError(t: Throwable): Unit = promise.tryFailure(t).discard

  override def onCompleted(): Unit = promise.trySuccess(()).discard
}
