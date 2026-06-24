// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.stream.scaladsl.FileIO
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.http.v0.svStream as v0
import org.lfdecentralizedtrust.splice.http.v0.svStream.SvStreamResource
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.DsoPartyMigration
import org.lfdecentralizedtrust.splice.util.Codec

import java.nio.file.Files
import scala.concurrent.Future

/** Server-side handler for the SV streaming endpoints (see sv-stream-server.yaml). Serves the DSO
  * party ACS snapshot prepared by the initiate endpoint directly from the file written by the
  * sponsor. Returning a known-length [[HttpEntity.Default]] lets the `withRangeSupport` directive
  * applied in [[org.lfdecentralizedtrust.splice.sv.SvApp]] turn Range requests into resumable 206
  * Partial Content responses.
  */
class HttpSvStreamHandler(
    dsoPartyMigration: DsoPartyMigration
)(implicit protected val tracer: Tracer)
    extends v0.SvStreamHandler[TraceContext]
    with Spanning {

  protected val workflowId: String = this.getClass.getSimpleName

  override def onboardSvPartyMigrationSnapshot(
      respond: SvStreamResource.OnboardSvPartyMigrationSnapshotResponse.type
  )(
      candidatePartyId: String
  )(extracted: TraceContext): Future[SvStreamResource.OnboardSvPartyMigrationSnapshotResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.onboardSvPartyMigrationSnapshot") { _ => _ =>
      Codec.decode(Codec.Party)(candidatePartyId) match {
        case Left(err) =>
          Future.failed(HttpErrorHandler.badRequest(s"Invalid candidate party id: $err"))
        case Right(candidateParty) =>
          dsoPartyMigration.snapshotState(candidateParty) match {
            case DsoPartyMigration.SnapshotState.Ready(file) =>
              Future.successful(
                respond.OK(
                  HttpEntity.Default(
                    ContentTypes.`application/octet-stream`,
                    Files.size(file),
                    FileIO.fromPath(file),
                  )
                )
              )
            case DsoPartyMigration.SnapshotState.InProgress =>
              Future.successful(respond.Conflict)
            case DsoPartyMigration.SnapshotState.NotFound =>
              Future.successful(respond.NotFound)
            case DsoPartyMigration.SnapshotState.Failed(_) =>
              Future.successful(respond.InternalServerError)
          }
      }
    }
  }
}
