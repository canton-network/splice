// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.config

import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
  SpliceNodeConfig,
  SpliceParametersConfig,
  SplicePostgresConfig,
}

// The sequencer of a synchronizer node this operator runs.
case class SyncOperatorSequencerConfig(
    adminApi: FullClientConfig
)

/** A synchronizer node this operator runs. */
case class SyncOperatorSynchronizerNodeConfig(
    sequencer: SyncOperatorSequencerConfig
)

case class SyncOperatorSynchronizerNodesConfig(
    current: SyncOperatorSynchronizerNodeConfig
)

/** Traffic control parameters for the dedicated synchronizer. These should always match mainnet.
  * They are configurable to allow adjusting if mainnet changes without waiting for a new release
  * but dsync operators must not change them outside of this.
  */
case class SyncOperatorTrafficControlConfig(
    // charge 4 per 10,000, i.e., 0.04% of write cost for every read.
    readVsWriteScalingFactor: PositiveInt = PositiveInt.tryCreate(4),
    setBalanceRequestSubmissionWindowSize: PositiveFiniteDuration =
      PositiveFiniteDuration.ofMinutes(2),
    baseEventCost: NonNegativeLong = NonNegativeLong.zero,
)

case class SyncOperatorAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    postgres: SplicePostgresConfig = SplicePostgresConfig(),
    synchronizerNodes: SyncOperatorSynchronizerNodesConfig,
    trafficControl: SyncOperatorTrafficControlConfig = SyncOperatorTrafficControlConfig(),
    override val automation: AutomationConfig = AutomationConfig(),
    parameters: SpliceParametersConfig = SpliceParametersConfig(batching = BatchingConfig()),
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(10),
    // Set to false to disable the DB-level exclusive lock that prevents two sync operator instances
    // from running concurrently against the same database.  Only disable for migration scenarios
    // where intentional overlap is required.
    instanceLockEnabled: Boolean = true,
) extends SpliceNodeConfig {
  override val nodeTypeName: String = "syncoperator"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class SyncOperatorAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
