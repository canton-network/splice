// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api

import com.digitalasset.canton.BaseTest
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.scalatest.wordspec.AnyWordSpec

class TraceContextDirectivesTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  private val traceId = "0af7651916cd43dd8448eb211c80319c"

  private val nestedRoute: Route =
    withTraceContext { outer =>
      withTraceContext { inner =>
        complete(s"${outer.traceId.getOrElse("")} ${inner.traceId.getOrElse("")}")
      }
    }

  private def traceIds(): Seq[String] = responseAs[String].split(' ').toSeq

  private def traceparentHeaders() = response.headers.filter(_.is("traceparent"))

  "withTraceContext" should {

    "provide the same trace context to nested uses for a request without a traceparent header" in {
      Get("/") ~> nestedRoute ~> check {
        status shouldBe StatusCodes.OK
        val ids = traceIds()
        ids should have size 2
        ids.head should not be empty
        ids.distinct should have size 1
      }
    }

    "provide the trace context of the traceparent header to nested uses" in {
      val request =
        HttpRequest().addHeader(RawHeader("traceparent", s"00-$traceId-b7ad6b7169203331-01"))
      request ~> nestedRoute ~> check {
        status shouldBe StatusCodes.OK
        traceIds() shouldBe Seq(traceId, traceId)
      }
    }

    "respond with a single traceparent header that has the trace id of the request" in {
      Get("/") ~> nestedRoute ~> check {
        val headers = traceparentHeaders()
        headers should have size 1
        headers.head.value should include(traceIds().head)
      }
    }

    "provide a new trace context to each request" in {
      val first = Get("/") ~> nestedRoute ~> check(traceIds().head)
      val second = Get("/") ~> nestedRoute ~> check(traceIds().head)
      first should not be second
    }
  }
}
