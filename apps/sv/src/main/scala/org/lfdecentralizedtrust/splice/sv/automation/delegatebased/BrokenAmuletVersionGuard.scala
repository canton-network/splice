// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.automation.{TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.IgnoredPartiesStore

import scala.concurrent.Future

trait BrokenAmuletVersionGuard {
  protected def svConfig: SvAppBackendConfig
  protected def ignoredPartiesStore: IgnoredPartiesStore
  protected def logger: TracedLogger

  protected def completeWithIgnoredAmuletVersionCheck(
      vettedVersion: String,
      expiredOwners: Set[PartyId],
  )(
      fallback: => Future[TaskOutcome]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    if (
      svConfig.allIgnoredAmuletVersions.contains(vettedVersion) &&
      svConfig.parameters.enabledFeatures.ignorePartyIdWithBrokenAmulet
    ) {
      logger.info(
        s"Batch resolved to ignored version $vettedVersion, adding ${expiredOwners.size} parties to the ignore list: $expiredOwners"
      )
      ignoredPartiesStore.addAll(expiredOwners)
      Future.successful(
        TaskSuccess(
          s"Skipped batch with ignored version $vettedVersion: added ${expiredOwners.size} parties to ignore list"
        )
      )
    } else {
      fallback
    }
  }
}
