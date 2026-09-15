// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client.commands

import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpCommand, HttpCommandException}
import org.lfdecentralizedtrust.splice.auth.AuthToken
import org.lfdecentralizedtrust.splice.config.AuthTokenSourceConfig
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.environment.BaseAppConnection
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration.Duration

class HttpScanAppClientTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private implicit val mat: Materializer = Materializer(actorSystem)
  private implicit val templateDecoder: TemplateJsonDecoder = mock[TemplateJsonDecoder]

  private final class TestConnection extends BaseAppConnection(loggerFactory) {
    override protected def timeouts = HttpScanAppClientTest.this.timeouts
    override def serviceName: String = "scan-test"

    def call[Res, Result, Client](
        url: Uri,
        command: HttpCommand[Res, Result, Client],
    )(implicit
        templateDecoder: TemplateJsonDecoder,
        httpClient: HttpClient,
        tc: TraceContext,
        ec: scala.concurrent.ExecutionContext,
        mat: Materializer,
    ): Future[Result] =
      runHttpCmd(url, command, List.empty[HttpHeader])(templateDecoder, httpClient, tc, ec, mat)
  }

  "HttpScanAppClient.GetBulkObjectChecksums" should {

    "be classified as not-yet by isNotYet logic for 404 and 501 when executed through runHttpCmd" in {
      val command =
        HttpScanAppClient.GetBulkObjectChecksums(CantonTimestamp.Epoch, Seq("object-key"))

      val isNotYet: Future[
        org.lfdecentralizedtrust.splice.http.v0.definitions.GetBulkObjectChecksumsResponse
      ] => Future[Boolean] =
        _.map(_ => false).recover {
          case e: BaseAppConnection.UnexpectedHttpJsonResponse =>
            e.statusCode == StatusCodes.NotFound || e.statusCode == StatusCodes.NotImplemented
          case e: HttpCommandException =>
            e.status == StatusCodes.NotFound || e.status == StatusCodes.NotImplemented
          case _ => false
        }

      def classifyAsNotYet(status: StatusCode): Boolean = {
        implicit val httpClient: HttpClient = new HttpClient {
          override val requestParameters: HttpClient.HttpRequestParameters =
            HttpClient.HttpRequestParameters(NonNegativeDuration(Duration.Zero))
          override def withOverrideParameters(
              newParameters: HttpClient.HttpRequestParameters
          ): HttpClient = this
          override def executeRequest(client: String, operation: String)(
              request: HttpRequest
          ): Future[HttpResponse] = {
            request.method shouldBe HttpMethods.POST
            request.uri.path.toString should include("/v0/history/bulk/checksums")
            Future.successful(
              HttpResponse(
                status,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  """{"error":"not caught up yet"}""",
                ),
              )
            )
          }
          override def getToken(authConfig: AuthTokenSourceConfig): Future[Option[AuthToken]] =
            Future.successful(None)
        }

        val resultF = new TestConnection().call(Uri("http://localhost"), command)
        isNotYet(resultF).futureValue
      }

      classifyAsNotYet(StatusCodes.NotFound) shouldBe true
      classifyAsNotYet(StatusCodes.NotImplemented) shouldBe true
    }
  }
}
