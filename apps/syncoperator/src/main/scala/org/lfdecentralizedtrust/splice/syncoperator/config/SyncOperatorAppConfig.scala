// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.config

import com.digitalasset.canton.config.*
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
  ParticipantClientConfig,
  SpliceBackendConfig,
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

case class SyncOperatorAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    postgres: SplicePostgresConfig = SplicePostgresConfig(),
    // Ledger API user of the operator party.
    operatorUser: String,
    participantClient: ParticipantClientConfig,
    synchronizerNodes: SyncOperatorSynchronizerNodesConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    parameters: SpliceParametersConfig = SpliceParametersConfig(batching = BatchingConfig()),
    // Set to false to disable the DB-level exclusive lock that prevents two sync operator instances
    // from running concurrently against the same database.  Only disable for migration scenarios
    // where intentional overlap is required.
    instanceLockEnabled: Boolean = true,
) extends SpliceBackendConfig {
  override val nodeTypeName: String = "syncoperator"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class SyncOperatorAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
