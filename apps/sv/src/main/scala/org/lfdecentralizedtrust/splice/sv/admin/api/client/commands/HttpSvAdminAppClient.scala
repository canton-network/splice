// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import cats.data.EitherT
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.http.v0.sv_admin as http
import org.lfdecentralizedtrust.splice.sv.migration.SynchronizerNodeIdentities
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.Future

object HttpSvAdminAppClient {
  import http.SvAdminClient as Client

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result, Client] {
    val createGenClientFn = (fn, host, ec, mat) => Client.httpClient(fn, host)(ec, mat)
  }

  case class GetSynchronizerNodeIdentitiesDump()
      extends BaseCommand[
        http.GetSynchronizerNodeIdentitiesDumpResponse,
        SynchronizerNodeIdentities,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetSynchronizerNodeIdentitiesDumpResponse] =
      client.getSynchronizerNodeIdentitiesDump(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetSynchronizerNodeIdentitiesDumpResponse.OK(response) =>
      SynchronizerNodeIdentities.fromHttp(response.identities)
    }
  }

  case class CancelLogicalSynchronizerUpgrade()
      extends BaseCommand[http.CancelLogicalSynchronizerUpgradeResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.CancelLogicalSynchronizerUpgradeResponse] =
      client.cancelLogicalSynchronizerUpgrade(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CancelLogicalSynchronizerUpgradeResponse.OK =>
      Right(())
    }
  }
}
