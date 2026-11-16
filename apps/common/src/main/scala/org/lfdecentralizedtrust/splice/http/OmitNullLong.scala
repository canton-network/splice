// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import io.circe.{Decoder, Encoder}

/** An opaque wrapper around [[Long]] intended for OpenAPI integer fields that are
  * conditionally omitted from JSON responses when their value is absent.
  *
  * Parallel to [[OmitNullString]]. Apply via `x-scala-type: ...OmitNullLong` on an
  * `integer` / `int64` field.
  */
final case class OmitNullLong(value: Long) extends AnyVal

object OmitNullLong {
  implicit val encoder: Encoder[OmitNullLong] =
    Encoder.encodeLong.contramap(_.value)

  implicit val decoder: Decoder[OmitNullLong] =
    Decoder.decodeLong.map(OmitNullLong(_))
}
