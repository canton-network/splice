// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.store.db.ScanRewardsReferenceTables.ScanRewardsReferenceStoreRowData
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData
import org.lfdecentralizedtrust.splice.util.{Contract, TemplateJsonDecoder}

import scala.concurrent.{ExecutionContext, Future}

/** This is a temporal contract store (TcsStore) to provide efficient asOf round
  * lookups of FeaturedAppRight and OpenMiningRound contracts
  * necessary for rewards calculations. It is a separate store with its own
  * tables to enable it to have its own indexing scheme and pruning schedule to
  * ensure consistent performance.
  */
trait ScanRewardsReferenceStore extends AppStore {

  def key: ScanRewardsReferenceStore.Key

  override def dsoPartyId = key.dsoParty

  /** Waits for this store to be initialized.
    * All other methods on this store will independently wait for initialization
    * to complete before returning results, this method is useful for cases where
    * the caller wants to wait for initialization to complete before starting
    * to use this store.
    */
  def waitUntilInitialized: Future[Unit]

  /** For a batch of record times, resolve the oldest open mining round at each time.
    * Returns map from record_time to (roundNumber, roundOpensAt).
    * This will wait till the round info could be obtained for record_times
    * which are yet to be ingested.
    *
    * On the other hand if round info could not be obtained for a particular record_time
    * then the Map will not contain the entry for that.
    * This could happen in two scenarios
    * 1. If the record_time or the round's openAt is before the ingestion start.
    * 2. When the ingestion start could not be determined
    *    This will happen if no contracts ingestion has happened in the archived table,
    *    ie the store ingestion has just begun and no OpenMiningRound archival has been observed.
    */
  def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]]

  def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]]

  /** Returns the set of SV participant UIDs from the DsoRules active as of the given time.
    * Returns an empty set only if asOf time is before the creation time of oldest DsoRules ingested.
    */
  def lookupSvParticipantIdsAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]]

  /** Look up an OpenMiningRound contract by its round number.
    * Checks both the active ACS table and the archive table,
    * since the round may have already been closed by the time the trigger runs.
    */
  def lookupOpenMiningRoundByNumber(
      roundNumber: Long
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[OpenMiningRound.ContractId, OpenMiningRound]]]

  /** The highest OpenMiningRound round number archived at or before asOf.
    * Returns None without waiting when asOf is before the earliest observed
    * archival, otherwise waits until the store's ingestion has reached asOf.
    */
  def lookupLatestArchivedOpenMiningRound(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[Long]]

  /** List active CalculateRewardsV2 contracts, sorted by round number ascending.
    */
  def listActiveCalculateRewardsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]]

  /** List active CalculateRewardsV2 contracts for the given round.
    */
  def listActiveCalculateRewardsV2ForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]]

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[
    ScanRewardsReferenceStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] =
    ScanRewardsReferenceStore.contractFilter(key)
}

object ScanRewardsReferenceStore {

  def apply(
      key: ScanRewardsReferenceStore.Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      migrationId: Long,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
      defaultLimit: Limit,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): ScanRewardsReferenceStore = {
    val dbStore = new db.DbScanRewardsReferenceStore(
      key = key,
      storage = storage,
      loggerFactory = loggerFactory,
      retryProvider = retryProvider,
      migrationId = migrationId,
      participantId = participantId,
      ingestionConfig = ingestionConfig,
      defaultLimit = defaultLimit,
    )
    new CachingScanRewardsReferenceStore(dbStore, loggerFactory)
  }

  case class Key(
      dsoParty: PartyId,
      synchronizerId: SynchronizerId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("dsoParty", _.dsoParty),
      param("synchronizerId", _.synchronizerId),
    )
  }

  def contractFilter(
      key: ScanRewardsReferenceStore.Key
  ): MultiDomainAcsStore.ContractFilter[
    ScanRewardsReferenceStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter
    val dso = key.dsoParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter[
      ScanRewardsReferenceStoreRowData,
      AcsInterfaceViewRowData.NoInterfacesIngested,
    ](
      key.dsoParty,
      templateFilters = Map(
        mkFilter(splice.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanRewardsReferenceStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(splice.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanRewardsReferenceStoreRowData(
              contract = contract,
              featuredAppRightProvider =
                Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
        },
        mkFilter(splice.dsorules.DsoRules.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanRewardsReferenceStoreRowData(contract = contract)
        },
        mkFilter(splice.amulet.rewardaccountingv2.CalculateRewardsV2.COMPANION)(
          co => co.payload.dso == dso,
          versionGuard = { case (pkgVersionSupport, now) =>
            (tc) => pkgVersionSupport.supportsTrafficBasedAppRewards(Seq(key.dsoParty), now)(tc)
          },
        ) { contract =>
          ScanRewardsReferenceStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
      ),
      interfaceFilters = Map.empty,
      synchronizerFilter = Some(key.synchronizerId),
    )
  }
}
