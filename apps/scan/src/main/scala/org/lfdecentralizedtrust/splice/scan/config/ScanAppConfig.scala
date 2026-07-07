// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.data.CantonTimestamp
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
  ParticipantClientConfig,
  S3Config,
  SpliceBackendConfig,
  SpliceInstanceNamesConfig,
  SpliceParametersConfig,
  SplicePostgresConfig,
}

import org.lfdecentralizedtrust.splice.store.Limit

import java.time.Instant

trait BaseScanAppConfig {}

final case class ScanSynchronizerConfig(
    sequencer: FullClientConfig,
    mediator: FullClientConfig,
    cantonBft: Option[CantonBftPeerConfig],
)

final case class MediatorVerdictIngestionConfig(
    /** Max verdicts items for DB insert batch. */
    batchSize: Int = 50
)

final case class BulkStorageConfig(
    /** When new snapshot is not yet available, how long to wait for a new one. */
    snapshotPollingInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    // When more updates are not yet available, how long to wait for more.
    updatesPollingInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    // When BFT was not reached on objects, how long to wait before retrying.
    bftRetryInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    // The maximum parallelization for uploading multiple parts of the same object
    maxParallelPartUploads: Int = 4,
    staging: Option[S3Config] = None,
    committed: Option[S3Config] = None,
)

/** @param miningRoundsCacheTimeToLiveOverride Intended only for testing!
  *                                            By default depends on the `tickDuration` of rounds. This setting overrides that.
  */
case class ScanAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    postgres: SplicePostgresConfig = SplicePostgresConfig(),
    svUser: String,
    override val participantClient: ParticipantClientConfig,
    synchronizerNodes: ScanSynchronizerNodesConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    mediatorVerdictIngestion: MediatorVerdictIngestionConfig = MediatorVerdictIngestionConfig(),
    enableAppActivityRecordAndTrafficIngestion: Boolean = true,
    serveAppActivityRecordsAndTraffic: Boolean = true,
    isFirstSv: Boolean = false,
    // Max rounding error tolerated wrt actual total of minting allowances
    // and the per-round minting allowance from the CC whitepaper.
    rewardMintingAllowanceTolerance: BigDecimal = BigDecimal(0.1),
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration] = None,
    enableForcedAcsSnapshots: Boolean = false,
    // The migration id is normally read from the DB (the highest known migration id in the
    // update history). It only needs to be resolved from a sponsor to bootstrap a node that does
    // not yet have any migration id in its DB (e.g. a freshly joining scan). In that case, the
    // migration id is fetched from the sponsor scan configured via `sponsorScanUrl`, the same way
    // a joining SV fetches it from its sponsoring SV.
    sponsorScanUrl: Option[NetworkAppClientConfig] = None,
    parameters: SpliceParametersConfig = SpliceParametersConfig(),
    spliceInstanceNames: SpliceInstanceNamesConfig,
    updateHistoryBackfillEnabled: Boolean = true,
    updateHistoryBackfillBatchSize: Int = 100,
    updateHistoryBackfillImportUpdatesEnabled: Boolean = true,
    updateHistoryMaxPageSize: Int = Limit.DefaultMaxPageSize,
    txLogBackfillEnabled: Boolean = true,
    txLogBackfillBatchSize: Int = 100,
    cache: ScanCacheConfig = ScanCacheConfig(),
    acsStoreDescriptorUserVersion: Option[Long] = None,
    txLogStoreDescriptorUserVersion: Option[Long] = None,
    activityIngestionUserVersion: Option[Long] = None,
    bulkStorage: BulkStorageConfig = BulkStorageConfig(),
    tokenStandardSettlement: TokenStandardConfig.SettlementConfig =
      TokenStandardConfig.SettlementConfig(),
    publicUrl: Option[Uri] = None,
    // The thresholdDate from which external transaction hashes are included in the updates from internal ScanAPIs.
    // TODO(#4249): use on-ledger synchronization for switching record times
    externalTransactionHashThresholdTime: Option[Instant] =
      ScanAppBackendConfig.DefaultExternalTransactionHashThresholdTime,
    globalSynchronizerAlias: SynchronizerAlias = SynchronizerAlias.tryCreate("global"),
    rollForwardLsu: Option[ScanRollForwardLsuConfig] = None,
    // Set to false to disable the DB-level exclusive lock that prevents two scan instances
    // from running concurrently against the same database.  Only disable for migration scenarios
    // where intentional overlap is required.
    instanceLockEnabled: Boolean = true,
) extends SpliceBackendConfig
    with BaseScanAppConfig // TODO(DACH-NY/canton-network-node#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

final case class ScanRollForwardLsuConfig(
    upgradeTime: Option[
      CantonTimestamp
    ] // If not set, we assume that there is an LsuAnnouncement on the legacy synchronizer.
)

object ScanAppBackendConfig {
  val DefaultExternalTransactionHashThresholdTime: Option[Instant] =
    Some(java.time.Instant.parse("2030-01-01T00:00:00Z"))
}

final case class ScanSynchronizerNodesConfig(
    current: ScanSynchronizerConfig,
    successor: Option[ScanSynchronizerConfig],
    legacy: Option[ScanSynchronizerConfig],
)

final case class ScanCacheConfig(
    svNodeState: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 100,
    ),
    openMiningRounds: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    amuletRules: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    ansRules: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    totalRewardsCollected: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(2),
      maxSize = 1,
    ),
    rewardsCollectedInRound: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    amuletConfigForRound: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    roundOfLatestData: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    topProvidersByAppRewards: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(2),
      maxSize = 2000,
    ),
    topValidators: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(2),
      maxSize = 2000,
    ),
    validatorLicenseByValidator: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    totalPurchasedMemberTraffic: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 2000,
    ),
    cachedByParty: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 2000,
    ),
    voteRequests: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
)

final case class CacheConfig(
    ttl: NonNegativeFiniteDuration,
    maxSize: Long,
)

case class ScanAppClientConfig(
    adminApi: NetworkAppClientConfig,

    /** Configures how long clients cache the AmuletRules they receive from the ScanApp
      * before rehydrating their cached value. In general, clients have a mechanism to invalidate
      * their AmuletRules cache if it becomes outdated, however, as a safety-layer we
      * invalidate it periodically because no CC transactions on a node could go through
      * if its AmuletRules cache is outdated and the client never notices and rehydrates it.
      */
    amuletRulesCacheTimeToLive: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
) extends HttpClientConfig
    with BaseScanAppConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

object ScanAppClientConfig {
  val DefaultAmuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)

  val DefaultScansRefreshInterval: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)
}
