// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

/** Upper bounds on the length of `Text` values that the Daml code enforces.
  *
  * Must be kept in sync with `Splice.TextLimits` in `daml/splice-amulet`.
  * The backends use them to reject over-long input before submitting a command.
  */
object TextLimits {

  /** Names and identifiers, e.g., ANS entry names and SV names. */
  val maxIdentifierLength: Int = 255

  /** Tracking ids that clients choose to deduplicate requests, inherited from `TransferOffer.trackingId`. */
  val maxTrackingIdLength: Int = 279

  /** Free-form text, e.g., descriptions and URLs. */
  val maxFreeFormTextLength: Int = 4096

  /** Signed tokens with which SV candidates request to be onboarded. */
  val maxSvOnboardingTokenLength: Int = 1900

  /** Validator onboarding secrets, including their JSON encoding with the sponsoring SV and the party hint.
    * Only enforced by the SV app, as the SV is the sole signatory of the contracts storing them.
    */
  val maxValidatorOnboardingSecretLength: Int = 1024

  /** The length of `value` as Daml's `DA.Text.length` computes it, i.e., in Unicode code points. */
  def damlTextLength(value: String): Int = value.codePointCount(0, value.length)

  def isWithinMaxLength(maxLength: Int, value: String): Boolean =
    damlTextLength(value) <= maxLength

  /** Returns an error message if `value` is longer than `maxLength`. */
  def checkMaxLength(name: String, maxLength: Int, value: String): Either[String, Unit] =
    Either.cond(
      isWithinMaxLength(maxLength, value),
      (),
      s"$name must be at most $maxLength characters long, but has length ${damlTextLength(value)}",
    )

  /** Throws an `INVALID_ARGUMENT` error, which the HTTP APIs report as 400, if `value` is longer than `maxLength`. */
  def requireMaxLength(name: String, maxLength: Int, value: String): Unit =
    checkMaxLength(name, maxLength, value).left.foreach { error =>
      throw io.grpc.Status.INVALID_ARGUMENT.withDescription(error).asRuntimeException()
    }
}
