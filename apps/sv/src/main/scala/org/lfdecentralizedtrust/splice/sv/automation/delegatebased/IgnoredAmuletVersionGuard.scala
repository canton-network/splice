// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.topology.PartyId
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import org.lfdecentralizedtrust.splice.automation.{TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.IgnoredPartiesStore
import org.lfdecentralizedtrust.splice.util.UnresponsiveParties

import scala.concurrent.{ExecutionContext, Future}

trait IgnoredAmuletVersionGuard {
  protected def svConfig: SvAppBackendConfig
  protected def ignoredPartiesStore: IgnoredPartiesStore
  protected def svTaskContext: SvTaskBasedTrigger.Context

  protected def completeWithIgnoredAmuletVersionCheck(
      vettedVersion: String,
      expiredOwners: Set[PartyId],
      enableUnresponsivePartiesAutoIgnore: Boolean,
  )(
      fallback: => Future[TaskOutcome]
  )(implicit ec: ExecutionContext): Future[TaskOutcome] = {
    if (
      svConfig.allIgnoredAmuletVersions.contains(vettedVersion) &&
      svConfig.parameters.enabledFeatures.ignorePartyIdWithIgnoredAmulet
    ) {
      ignoredPartiesStore.addAll(expiredOwners)
      Future.successful(
        TaskSuccess(
          s"Skipped batch with ignored version $vettedVersion: added ${expiredOwners.size} parties to ignore list: $expiredOwners"
        )
      )
    } else {
      val enableNaiveUnresponsivePartiesAutoIgnore =
        svConfig.parameters.enabledFeatures.naiveUnresponsivePartiesAutoIgnore && enableUnresponsivePartiesAutoIgnore
      fallback.recoverWith {
        case ex: StatusRuntimeException if enableNaiveUnresponsivePartiesAutoIgnore =>
          extractUnresponsiveParties(ex) match {
            case parties if parties.nonEmpty =>
              val partiesToIgnore = parties - svTaskContext.dsoStore.key.dsoParty.partyId
              ignoredPartiesStore.addAll(partiesToIgnore)
              Future.successful(
                TaskSuccess(
                  s"Batch failed due to unresponsive parties, added ${partiesToIgnore.size} to ignore list: $partiesToIgnore"
                )
              )
            case _ => Future.failed(ex)
          }
      }
    }
  }

  private def extractUnresponsiveParties(ex: StatusRuntimeException): Set[PartyId] = {
    val statusProto = StatusProto.fromThrowable(ex)
    val errorDetails = ErrorDetails.from(statusProto)
    errorDetails
      .collectFirst { case UnresponsiveParties(parties) => parties }
      .getOrElse(Set.empty)
  }

}
