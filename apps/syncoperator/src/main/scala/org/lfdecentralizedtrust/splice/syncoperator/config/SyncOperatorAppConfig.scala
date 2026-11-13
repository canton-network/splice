// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.config

import com.digitalasset.canton.admin.api.client.data.{
  SequencerConnectionPoolDelays,
  SubmissionRequestAmplification,
  SubscriptionLivenessLimits,
  SynchronizerLimits,
}
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong, PositiveInt}
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
  SpliceNodeConfig,
  SpliceParametersConfig,
  SplicePostgresConfig,
}
import org.lfdecentralizedtrust.splice.lsu.LsuRollForwardTimestamp

import java.nio.file.Path

// The sequencer of a synchronizer node this operator runs.
case class SyncOperatorSequencerConfig(
    adminApi: FullClientConfig,
    // Only needed for an upgrade: the mediator reaches the sequencer here.
    internalApi: Option[FullClientConfig] = None,
    // Only needed for an upgrade: published as the sequencer successor, so this is the url
    // validators reach the node on, not an address on the operator's own network.
    externalPublicApiUrl: Option[String] = None,
)

// The mediator of a synchronizer node this operator runs. Only needed for an upgrade, which
// initializes the successor's mediator from this one's identity.
case class SyncOperatorMediatorConfig(
    adminApi: FullClientConfig,
    sequencerRequestAmplification: SubmissionRequestAmplification =
      SubmissionRequestAmplification.NoAmplification,
    sequencerConnectionPoolDelays: SequencerConnectionPoolDelays =
      SequencerConnectionPoolDelays.default,
    subscriptionLivenessLimits: SubscriptionLivenessLimits = SubscriptionLivenessLimits.default,
)

/** A synchronizer node this operator runs, as a sequencer and mediator pair. */
case class SyncOperatorSynchronizerNodeConfig(
    sequencer: SyncOperatorSequencerConfig,
    mediator: Option[SyncOperatorMediatorConfig] = None,
    serial: Option[NonNegativeInt] = None,
    // We want to be able to override this for simtime tests
    topologyChangeDelayDuration: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofMillis(250),
    // None leaves Canton's own defaults in place, which is what a dedicated synchronizer is
    // bootstrapped with.
    synchronizerLimits: Option[SynchronizerLimits] = None,
)

case class SyncOperatorSynchronizerNodesConfig(
    current: SyncOperatorSynchronizerNodeConfig,
    // Set once the successor's sequencer and mediator are deployed, ahead of the upgrade time.
    successor: Option[SyncOperatorSynchronizerNodeConfig] = None,
)

/** An upgrade the operator has scheduled, mirroring the DSO's
  * `LogicalSynchronizerUpgradeSchedule`.
  */
case class SyncOperatorLsuConfig(
    // The announcement is published once this is reached, which is what freezes topology.
    topologyFreezeTime: LsuRollForwardTimestamp,
    upgradeTime: LsuRollForwardTimestamp,
    newPhysicalSynchronizerSerial: NonNegativeInt,
    // An upgrade may keep the protocol version, it cannot go back to an earlier one.
    newPhysicalSynchronizerProtocolVersion: ProtocolVersion,
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
    // The upgrade this operator has scheduled, if any. Read by the announcement trigger.
    lsu: Option[SyncOperatorLsuConfig] = None,
    // Where the predecessor's synchronizer state is dumped during an upgrade.
    lsuDumpPath: Option[Path] = None,
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
