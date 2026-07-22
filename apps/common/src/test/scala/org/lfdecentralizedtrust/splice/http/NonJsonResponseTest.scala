// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.config.NonNegativeDuration
import io.circe.syntax.*
import org.lfdecentralizedtrust.splice.auth.AuthToken
import org.lfdecentralizedtrust.splice.config.AuthTokenSourceConfig
import org.lfdecentralizedtrust.splice.http.v0.definitions.Version
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.http.v0.external.common_admin.{
  CommonAdminClient,
  GetVersionResponse,
}
import org.scalatest.compatible.Assertion
import org.scalatest.wordspec.AnyWordSpec

import java.time.{OffsetDateTime, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.duration.Duration

class NonJsonResponseTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private implicit val mat: Materializer = Materializer(actorSystem)

  private def runGetVersion(resp: ResponseEntity)(
      expectation: Either[Either[Throwable, HttpResponse], GetVersionResponse] => Assertion
  ): Assertion = {
    implicit val httpClient: HttpClient = new HttpClient {
      val requestParameters = HttpClient.HttpRequestParameters(NonNegativeDuration(Duration.Zero))
      def withOverrideParameters(newParameters: HttpClient.HttpRequestParameters): HttpClient = this
      def executeRequest(client: String, operation: String)(
          request: HttpRequest
      ): Future[HttpResponse] = Future.successful(HttpResponse(StatusCodes.OK, entity = resp))
      def getToken(authConfig: AuthTokenSourceConfig): Future[Option[AuthToken]] =
        Future.successful(None)
    }

    val client = CommonAdminClient.httpClient(HttpClient.createHttpFn("", ""), "http://localhost")
    expectation(client.getVersion().value.futureValue)
  }

  private val testHtmlBody = "<html><body>test</body></html>"

  "CommonAdminClient.getVersion" should {
    "include the response body in the error when the response is not JSON" in {
      runGetVersion(HttpEntity(ContentTypes.`text/html(UTF-8)`, testHtmlBody)) {
        case Left(Left(err)) => err.getMessage should endWith(testHtmlBody)
        case other => fail(s"expected Left(Left(throwable)), got: $other")
      }
    }

    "decode a well-formed application/json response" in {
      runGetVersion(
        HttpEntity(
          ContentTypes.`application/json`,
          Version(
            "1.2.3",
            OffsetDateTime.of(2026, 7, 23, 0, 0, 0, 0, ZoneOffset.UTC),
          ).asJson.noSpaces,
        )
      ) {
        case Right(_: GetVersionResponse.OK) => succeed
        case other => fail(s"expected GetVersionResponse.OK, got: $other")
      }
    }
  }
}
