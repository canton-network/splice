// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client

import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.lfdecentralizedtrust.splice.http.v0.definitions.OnboardSvDownloadPendingResponse
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.OnboardSvDownloadResponse
import org.scalatest.wordspec.AnyWordSpec

import java.time.OffsetDateTime
import scala.concurrent.Future

class SvStreamClientTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private def accepted(json: String) =
    SvStreamClient
      .httpClient(
        _ =>
          Future.successful(
            HttpResponse(
              StatusCodes.Accepted,
              entity = HttpEntity(ContentTypes.`application/json`, json),
            )
          ),
        "http://sponsor",
      )
      .onboardSvDownload("id")
      .value
      .futureValue

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
  }
}
