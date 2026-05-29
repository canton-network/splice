// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv_admin as v0}
import org.lfdecentralizedtrust.splice.http.v0.sv_admin.SvAdminResource as r0
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.migration.SynchronizerNodeIdentities
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.auth.AdminAuthExtractor.AdminUserRequest

import scala.concurrent.{ExecutionContextExecutor, Future}

class HttpSvAdminHandler(
    config: SvAppBackendConfig,
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    synchronizerNodeService: SynchronizerNodeService[LocalSynchronizerNode],
    participantAdminConnection: ParticipantAdminConnection,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    protected val tracer: Tracer,
) extends v0.SvAdminHandler[AdminUserRequest]
    with Spanning
    with NamedLogging {

  protected val workflowId: String = this.getClass.getSimpleName
  private val dsoStore = dsoStoreWithIngestion.store

  override def cancelLogicalSynchronizerUpgrade(
      respond: r0.CancelLogicalSynchronizerUpgradeResponse.type
  )()(
      extracted: AdminUserRequest
  ): Future[r0.CancelLogicalSynchronizerUpgradeResponse] = {
    implicit val AdminUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.cancelLogicalSynchronizerUpgrade") { _ => _ =>
      for {
        decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
        sequencerId <- synchronizerNodeService.sequencerAdminConnection().flatMap(_.getSequencerId)
        _ <- participantAdminConnection
          .removeSequencerSuccessor(
            decentralizedSynchronizer,
            sequencerId,
          )
        _ <- participantAdminConnection
          .removeLsuAnnouncement(decentralizedSynchronizer)
      } yield r0.CancelLogicalSynchronizerUpgradeResponseOK
    }
  }

  override def getSynchronizerNodeIdentitiesDump(
      respond: r0.GetSynchronizerNodeIdentitiesDumpResponse.type
  )()(
      tuser: AdminUserRequest
  ): Future[r0.GetSynchronizerNodeIdentitiesDumpResponse] = {
    val AdminUserRequest(traceContext) = tuser
    withSpan(s"$workflowId.getSynchronizerNodeIdentitiesDump") { implicit tc => _ =>
      SynchronizerNodeIdentities
        .getSynchronizerNodeIdentities(
          participantAdminConnection,
          synchronizerNodeService.nodes.current,
          dsoStore,
          config.domains.global.alias,
          loggerFactory,
        )
        .map { response =>
          r0.GetSynchronizerNodeIdentitiesDumpResponse.OK(
            definitions.GetSynchronizerNodeIdentitiesDumpResponse(response.toHttp())
          )
        }
    }(traceContext, tracer)
  }
}
