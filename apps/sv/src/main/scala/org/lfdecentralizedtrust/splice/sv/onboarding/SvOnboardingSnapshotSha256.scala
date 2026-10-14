// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import com.digitalasset.canton.discard.Implicits.DiscardOps

import java.io.OutputStream
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.util.Base64
import scala.util.Try

object SvOnboardingSnapshotSha256 {

  def base64OfFile(file: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = new DigestInputStream(Files.newInputStream(file), digest)
    try in.transferTo(OutputStream.nullOutputStream()).discard
    finally in.close()
    Base64.getEncoder.encodeToString(digest.digest())
  }

  def canonicalBase64(encoded: String): Option[String] =
    Try(Base64.getDecoder.decode(encoded)).toOption
      .filter(_.length == 32)
      .map(Base64.getEncoder.encodeToString)

  def reprDigest(sha256Base64: String): String = s"sha-256=:$sha256Base64:"
}
