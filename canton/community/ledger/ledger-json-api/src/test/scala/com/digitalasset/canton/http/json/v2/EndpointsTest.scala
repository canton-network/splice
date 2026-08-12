// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.http.json.v2

import com.digitalasset.canton.http.json.v2.Endpoints.{Jwt, extractWsJwtToken}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EndpointsTest extends AnyFlatSpec with Matchers {

  behavior of "Endpoints.extractWsJwtToken"

  it should "extract the JWT when the header has no extra whitespace" in {
    extractWsJwtToken(Some("daml.ws.auth,jwt.token.abc123")) shouldBe Some(Jwt("abc123"))
  }

  it should "extract the JWT when the subprotocols are comma-space separated, as sent by browsers and " +
    "standard WebSocket clients (RFC 6455)" in {
      extractWsJwtToken(Some("daml.ws.auth, jwt.token.abc123")) shouldBe Some(Jwt("abc123"))
    }

  it should "extract the JWT regardless of subprotocol order" in {
    extractWsJwtToken(Some("jwt.token.abc123, daml.ws.auth")) shouldBe Some(Jwt("abc123"))
  }

  it should "extract the JWT when it is the only requested subprotocol" in {
    extractWsJwtToken(Some("jwt.token.abc123")) shouldBe Some(Jwt("abc123"))
  }

  it should "tolerate extra surrounding and internal whitespace around subprotocols" in {
    extractWsJwtToken(Some("  daml.ws.auth  ,   jwt.token.abc123  ")) shouldBe Some(Jwt("abc123"))
  }

  it should "return None when the header is absent" in {
    extractWsJwtToken(None) shouldBe None
  }

  it should "return None when no subprotocol carries a JWT" in {
    extractWsJwtToken(Some("daml.ws.auth")) shouldBe None
  }

  it should "return None for an empty header value" in {
    extractWsJwtToken(Some("")) shouldBe None
  }
}
