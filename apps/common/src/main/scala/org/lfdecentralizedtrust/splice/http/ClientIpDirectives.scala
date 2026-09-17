// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import org.apache.pekko.http.scaladsl.model.headers.`X-Real-Ip`
import org.apache.pekko.http.scaladsl.model.RemoteAddress
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.*

object ClientIpDirectives {

  /** Extracts the address of the client the request originated from, if it can be determined.
    *
    * The headers in `clientIpHeaders` are tried in order and the first one that is present and
    * yields an IP literal determines the address. Note that headers set by the client itself (such
    * as `X-Forwarded-For` and `X-Real-Ip`) can be spoofed unless they are overwritten by a reverse
    * proxy the client cannot bypass.
    *
    * @param clientIpHeaders
    *   names of the headers carrying the client IP, in order of precedence. Matched
    *   case-insensitively, as the configured header names are not required to be lowercase. An
    *   empty list disables the extraction.
    */
  def extractClientIp(clientIpHeaders: Seq[String]): Directive1[Option[RemoteAddress]] =
    firstDefined(clientIpHeaders.map(_.trim).filter(_.nonEmpty).map(clientIpFromHeader)*)

  private def clientIpFromHeader(headerName: String): Directive1[Option[RemoteAddress]] =
    optionalHeaderValueByName(headerName).map(_.flatMap(parseFirstIpLiteral))

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

  private def parseFirstIpLiteral(value: String): Option[RemoteAddress] =
    value.split(',').headOption.flatMap(parseIpLiteral)

  private def parseIpLiteral(value: String): Option[RemoteAddress] =
    `X-Real-Ip`
      .parseFromValueString(value.trim)
      .toOption
      .map(_.address)
}
