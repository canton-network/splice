// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client

import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.lfdecentralizedtrust.splice.http.v0.definitions.OnboardSvDownloadPendingResponse
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.OnboardSvDownloadResponse
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotSha256
import org.scalatest.wordspec.AnyWordSpec

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import scala.concurrent.Future

class SvStreamClientTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private def download(response: HttpResponse) =
    SvStreamClient
      .httpClient(_ => Future.successful(response), "http://sponsor")
      .onboardSvDownload("id")
      .value
      .futureValue

  private def accepted(json: String) =
    download(
      HttpResponse(
        StatusCodes.Accepted,
        entity = HttpEntity(ContentTypes.`application/json`, json),
      )
    )

  "onboardSvDownload" should {

    "decode a queued export" in {
      accepted("""{"status":"queued","queued_at":"2026-01-01T00:00:00Z"}""") shouldBe Right(
        OnboardSvDownloadResponse.Accepted(
          OnboardSvDownloadPendingResponse(
            OnboardSvDownloadPendingResponse.Status.Queued,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
          )
        )
      )
    }

    "decode a running export with its start time" in {
      accepted(
        """{"status":"exporting","queued_at":"2026-01-01T00:00:00Z","started_at":"2026-01-01T00:01:00Z"}"""
      ) shouldBe Right(
        OnboardSvDownloadResponse.Accepted(
          OnboardSvDownloadPendingResponse(
            OnboardSvDownloadPendingResponse.Status.Exporting,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            Some(OffsetDateTime.parse("2026-01-01T00:01:00Z")),
          )
        )
      )
    }

    "combine Repr-Digest header lines in order" in {
      def base64(algorithm: String) =
        Base64.getEncoder.encodeToString(MessageDigest.getInstance(algorithm).digest("x".getBytes))
      val sha512 = s"sha-512=:${base64("SHA-512")}:"
      val sha256 = s"sha-256=:${base64("SHA-256")}:"
      inside(
        download(
          HttpResponse(
            StatusCodes.OK,
            headers = List(RawHeader("Repr-Digest", sha512), RawHeader("Repr-Digest", sha256)),
            entity = HttpEntity(ContentTypes.`application/octet-stream`, "x".getBytes),
          )
        )
      ) { case Right(OnboardSvDownloadResponse.OK(_, Some(reprDigest))) =>
        reprDigest shouldBe s"$sha512, $sha256"
        SvOnboardingSnapshotSha256.base64FromReprDigest(reprDigest) shouldBe Some(base64("SHA-256"))
      }
    }
  }
}
