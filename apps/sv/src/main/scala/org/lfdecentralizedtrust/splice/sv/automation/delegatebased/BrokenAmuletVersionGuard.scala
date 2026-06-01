// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.automation.{TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.IgnoredPartiesStore

import scala.concurrent.Future

trait BrokenAmuletVersionGuard {
  protected def svConfig: SvAppBackendConfig
  protected def ignoredPartiesStore: IgnoredPartiesStore

  protected def completeWithBrokenVersionCheck(
      vettedVersion: String,
      expiredOwners: Seq[PartyId],
  )(
      fallback: => Future[TaskOutcome]
  ): Future[TaskOutcome] = {
    if (
      svConfig.brokenAmuletVersions.contains(vettedVersion) &&
      svConfig.parameters.enabledFeatures.ignorePartyIdWithBrokenAmulet
    ) {
      ignoredPartiesStore.addAll(expiredOwners)
      Future.successful(
        TaskSuccess(
          s"Skipped batch with broken version $vettedVersion: added ${expiredOwners.size} parties to ignore list"
        )
      )
    } else {
      fallback
    }
  }
}
