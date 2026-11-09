// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import io.circe.{Decoder, Encoder}

/** An opaque wrapper around `Vector[A]` intended for OpenAPI array fields that are
  * conditionally omitted from JSON responses when their value is absent.
  *
  * Parallel to [[OmitNullString]]. Using a dedicated type (instead of a bare
  * `Vector[A]`) makes it explicit in both the OpenAPI spec and the generated
  * Scala code which array fields are omitted when null.
  *
  * In the OpenAPI spec, apply via `x-scala-array-type: ...OmitNullArray` on the
  * array field (NOT `x-scala-type`, which guardrail only honours on primitive
  * types).
  */
final case class OmitNullArray[A](value: Vector[A])

object OmitNullArray {
  implicit def encoder[A: Encoder]: Encoder[OmitNullArray[A]] =
    Encoder.encodeVector[A].contramap(_.value)

  implicit def decoder[A: Decoder]: Decoder[OmitNullArray[A]] =
    Decoder.decodeVector[A].map(OmitNullArray(_))
}
