// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import org.apache.pekko.http.scaladsl.model.headers.{`X-Forwarded-For`, `X-Real-Ip`}
import org.apache.pekko.http.scaladsl.model.RemoteAddress
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.*

object ClientIpDirectives {

  /** Extracts the address of the client the request originated from, if it can be determined.
    *
    * The address is taken from the first of the following sources that yields an address:
    *   1. the `trustedClientIpHeader` (if configured and parseable as an IP literal), which is set
    *      by a trusted reverse proxy and hence cannot be spoofed by the client,
    *   1. the client-controlled `X-Forwarded-For` header (unless disabled),
    *   1. the client-controlled `X-Real-Ip` header (unless disabled),
    *
    * @param trustedClientIpHeader
    *   name of the header set by a trusted reverse proxy, matched case-insensitively. An empty name
    *   disables trusting a proxy header.
    * @param enableClientProvidedIpHeaders
    *   whether to fall back to the client-controlled `X-Forwarded-For`/`X-Real-Ip` headers when the
    *   trusted proxy header does not yield an address. Set to `false` to only rely on the trusted
    *   proxy header, so that clients cannot influence the extracted address by forging these headers.
    */
  def extractClientIp(
      trustedClientIpHeader: String,
      enableClientProvidedIpHeaders: Boolean,
  ): Directive1[Option[RemoteAddress]] = {
    val clientProvidedSources: Seq[Directive1[Option[RemoteAddress]]] =
      if (enableClientProvidedIpHeaders) Seq(forwardedForClientIp, realIpClientIp)
      else Seq.empty
    val sources = trustedClientIp(trustedClientIpHeader) +: clientProvidedSources
    firstDefined(sources*)
  }

  private def trustedClientIp(headerName: String): Directive1[Option[RemoteAddress]] = {
    val trimmedHeaderName = headerName.trim
    if (trimmedHeaderName.isEmpty) provide(None)
    else
      // matched case-insensitively (and locale independently) as the configured header name is not
      // required to be lowercase
      optionalHeaderValueByName(trimmedHeaderName).map(_.flatMap(parseIpLiteral))
  }

  private val forwardedForClientIp: Directive1[Option[RemoteAddress]] = {
    optionalHeaderValuePF { case `X-Forwarded-For`(Seq(address, _*)) => address }
  }

  private val realIpClientIp: Directive1[Option[RemoteAddress]] =
    optionalHeaderValuePF { case `X-Real-Ip`(address) => address }

  /** The value of the first directive that extracts a defined value, [[None]] if there is none. */
  private def firstDefined[A](
      directives: Directive1[Option[A]]*
  ): Directive1[Option[A]] =
    directives.foldRight(provide(Option.empty[A])) { (directive, fallback) =>
      directive.flatMap {
        case defined @ Some(_) => provide(defined)
        case None => fallback
      }
    }

  private def parseIpLiteral(value: String): Option[RemoteAddress] =
    `X-Real-Ip`
      .parseFromValueString(value.trim)
      .toOption
      .map(_.address)
}
