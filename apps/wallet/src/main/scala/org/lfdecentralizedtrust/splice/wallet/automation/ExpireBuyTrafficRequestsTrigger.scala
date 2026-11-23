// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.buytrafficrequest as trafficRequestCodegen
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.util.BuyTrafficRequestUtil
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ExpireBuyTrafficRequestsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      trafficRequestCodegen.BuyTrafficRequest.ContractId,
      trafficRequestCodegen.BuyTrafficRequest,
    ](
      store.multiDomainAcsStore,
      ExpireBuyTrafficRequestsTrigger.listExpiredWithBoundedTrackingId(store),
      trafficRequestCodegen.BuyTrafficRequest.COMPANION,
    ) {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          trafficRequestCodegen.BuyTrafficRequest.ContractId,
          trafficRequestCodegen.BuyTrafficRequest,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      user = store.key.endUserParty.toProtoPrimitive
      _ <- user match {
        case task.work.contract.payload.endUserParty =>
          val cmd = install.exercise(
            _.exerciseWalletAppInstall_BuyTrafficRequest_Expire(
              task.work.contractId
            )
          )
          connection
            .submit(Seq(store.key.validatorParty), Seq(store.key.endUserParty), cmd)
            .noDedup
            .yieldResult()
        case _ =>
          Future.failed(
            Status.INTERNAL
              .withDescription(
                s"User ($user) is not the requester of extra traffic (${task.work.contract.payload.endUserParty})"
              )
              .asRuntimeException()
          )
      }
    } yield TaskSuccess("expired buy traffic request")
  }
}

object ExpireBuyTrafficRequestsTrigger {

  /** Skips requests whose tracking id exceeds the limit of the current Daml code,
    * as they cannot be expired. See [[BuyTrafficRequestUtil.hasValidTrackingId]].
    */
  private def listExpiredWithBoundedTrackingId(store: UserWalletStore)(implicit
      ec: ExecutionContext
  ): MultiDomainExpiredContractTrigger.ListExpiredContracts[
    trafficRequestCodegen.BuyTrafficRequest.ContractId,
    trafficRequestCodegen.BuyTrafficRequest,
  ] = (now, limit) =>
    implicit tc =>
      store
        .listExpiredBuyTrafficRequests(now, limit)(tc)
        .map(
          _.filter(request => BuyTrafficRequestUtil.hasValidTrackingId(request.contract.payload))
        )
}
