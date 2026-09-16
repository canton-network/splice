// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import com.digitalasset.canton.BaseTest
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.stream.StreamTcpException
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.BaseAppConnection
import org.scalatest.wordspec.AnyWordSpec

class SingleScanConnectionTest extends AnyWordSpec with BaseTest {

  "SingleScanConnection.httpStatusLabel" should {

    "extract the status code of an unexpected JSON response" in {
      SingleScanConnection.httpStatusLabel(
        new BaseAppConnection.UnexpectedHttpJsonResponse(StatusCodes.NotFound, Json.obj())
      ) should be("404")
    }

    "extract the status code of an unexpected malformed JSON response" in {
      SingleScanConnection.httpStatusLabel(
        new BaseAppConnection.UnexpectedHttpMalformedJsonResponse(
          StatusCodes.BadGateway,
          "not json",
        )
      ) should be("502")
    }

    "extract the status code of an unexpected text response" in {
      SingleScanConnection.httpStatusLabel(
        new BaseAppConnection.UnexpectedHttpTextResponse(StatusCodes.ServiceUnavailable, "nope")
      ) should be("503")
    }

    "extract the status code of an unexpected non-JSON response" in {
      SingleScanConnection.httpStatusLabel(
        new BaseAppConnection.UnexpectedHttpNonJsonResponse(StatusCodes.InternalServerError)
      ) should be("500")
    }

    "extract the status code of an HttpCommandException" in {
      SingleScanConnection.httpStatusLabel(
        HttpCommandException(
          HttpRequest(),
          StatusCodes.TooManyRequests,
          HttpCommandException.RawResponse("slow down"),
        )
      ) should be("429")
    }

    "report 'none' for failures without an HTTP status code" in {
      SingleScanConnection.httpStatusLabel(
        new StreamTcpException("connection refused")
      ) should be("none")
      SingleScanConnection.httpStatusLabel(new RuntimeException("boom")) should be("none")
    }
  }
}
