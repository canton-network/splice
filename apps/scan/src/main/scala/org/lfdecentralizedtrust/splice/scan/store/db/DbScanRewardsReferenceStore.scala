// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers, TcsStore}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsArchiveConfig,
  AcsQueries,
  AcsTables,
  DbAppStore,
  DbTcsStore,
  StoreDescriptor,
}
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.SelectFromAcsTableResult
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  ContractWithState,
  PackageQualifiedName,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbScanRewardsReferenceStore(
    override val key: ScanRewardsReferenceStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    migrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    override val defaultLimit: Limit,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = ScanRewardsReferenceTables.acsTableName,
      interfaceViewsTableNameOpt = None,
      acsStoreDescriptor = StoreDescriptor(
        version = 3,
        name = "DbScanRewardsReferenceStore",
        party = key.dsoParty,
        participant = participantId,
        key = Map(
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
          "synchronizerId" -> key.synchronizerId.toProtoPrimitive,
        ),
      ),
      migrationId = migrationId,
      ingestionConfig = ingestionConfig,
      acsArchiveConfigOpt = Some(
        AcsArchiveConfig.withIndexColumns(
          ScanRewardsReferenceTables.archiveTableName,
          ScanRewardsReferenceTables.ScanRewardsReferenceStoreRowData.hasIndexColumns.indexColumnNames,
        )
      ),
    )
    with ScanRewardsReferenceStore
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  override def waitUntilInitialized: Future[Unit] = multiDomainAcsStore.waitUntilAcsIngested()

  private val tcsStore = new DbTcsStore(
    multiDomainAcsStore,
    descriptor => SynchronizerId.tryFromString(descriptor.key("synchronizerId")),
  )

  override def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]] = {
    tcsStore.getEarliestArchivedAt().flatMap {
      case None =>
        Future.successful(Map.empty)
      case Some(ingestionStart) =>
        val afterIngestionStartTimes = recordTimes.filter(_ >= ingestionStart)
        if (afterIngestionStartTimes.isEmpty) Future.successful(Map.empty)
        else {
          val (minTime, maxTime) = afterIngestionStartTimes.foldLeft(
            (CantonTimestamp.MaxValue, CantonTimestamp.MinValue)
          ) { case ((lo, hi), t) => (lo.min(t), hi.max(t)) }
          lookupOpenMiningRoundsActiveWithin(minTime, maxTime).map { activeWithinResult =>
            afterIngestionStartTimes.flatMap { recordTime =>
              val roundsAtTime = TcsStore.contractsActiveAsOf(activeWithinResult, recordTime)
              val openRounds = roundsAtTime.filter { r =>
                CantonTimestamp.assertFromInstant(r.contract.payload.opensAt) <= recordTime
              }
              openRounds
                .minByOption(_.contract.payload.round.number)
                .flatMap { r =>
                  val opensAt = CantonTimestamp.assertFromInstant(r.contract.payload.opensAt)
                  Option.when(opensAt >= ingestionStart) {
                    recordTime -> (r.contract.payload.round.number.toLong, opensAt)
                  }
                }
            }.toMap
          }
        }
    }
  }

  override def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]] =
    lookupFeaturedAppRightsAsOf(asOf)
      .map(_.map(_.contract.payload.provider).toSet)

  override def lookupSvParticipantIdsAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]] =
    tcsStore.listAllContractsAsOf(DsoRules.COMPANION, asOf, limit = Some(2)).map {
      case Seq() => Set.empty[String]
      case Seq(c) =>
        import scala.jdk.CollectionConverters.*
        c.contract.payload.svs.values().asScala.toSet[splice.dsorules.SvInfo].map { info =>
          // svInfo.participantId is in the proto-primitive form accepted by
          // ParticipantId.tryFromProtoPrimitive (with `PAR::` prefix), while
          // verdict.submittingParticipantUid carries only the UID portion.
          // Normalize to the latter for direct comparison.
          ParticipantId.tryFromProtoPrimitive(info.participantId).uid.toProtoPrimitive
        }
      case multiple =>
        throw new IllegalStateException(
          s"Expected at most one active DsoRules contract as of $asOf, but found ${multiple.size}: " +
            multiple.map(_.contract.contractId.contractId).mkString(", ")
        )
    }

  def lookupOpenMiningRoundsActiveWithin(
      lowerBoundIncl: CantonTimestamp,
      upperBoundIncl: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[TcsStore.TemporalContractWithState[OpenMiningRound.ContractId, OpenMiningRound]]
  ] =
    tcsStore.listAllContractsActiveWithin(
      OpenMiningRound.COMPANION,
      lowerBoundIncl,
      upperBoundIncl,
    )

  def lookupFeaturedAppRightsAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    tcsStore.listAllContractsAsOf(FeaturedAppRight.COMPANION, asOf)

  def lookupOpenMiningRoundsAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]]] =
    tcsStore.listAllContractsAsOf(OpenMiningRound.COMPANION, asOf)

  override def lookupOpenMiningRoundByNumber(
      roundNumber: Long
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[OpenMiningRound.ContractId, OpenMiningRound]]] =
    waitUntilInitialized.flatMap { _ =>
      lookupOpenMiningRoundByNumberQuery(roundNumber)
    }

  private def lookupOpenMiningRoundByNumberQuery(
      roundNumber: Long
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[OpenMiningRound.ContractId, OpenMiningRound]]] = {
    val storeId = multiDomainAcsStore.acsStoreId
    val migrationId = multiDomainAcsStore.domainMigrationId
    val pqn = PackageQualifiedName.fromJavaCodegenCompanion(OpenMiningRound.COMPANION)
    val columns = SelectFromAcsTableResult.sqlColumnsCommaSeparated()
    val query =
      sql"""(
         select #$columns
         from #${ScanRewardsReferenceTables.acsTableName} acs
         where acs.store_id = $storeId
           and acs.migration_id = $migrationId
           and acs.package_name = ${pqn.packageName}
           and acs.template_id_qualified_name = ${pqn.qualifiedName}
           and acs.round = $roundNumber
       ) union all (
         select #$columns
         from #${ScanRewardsReferenceTables.archiveTableName} acs
         where acs.store_id = $storeId
           and acs.migration_id = $migrationId
           and acs.package_name = ${pqn.packageName}
           and acs.template_id_qualified_name = ${pqn.qualifiedName}
           and acs.round = $roundNumber
       ) limit 1""".as[SelectFromAcsTableResult]
    for {
      result <- futureUnlessShutdownToFuture(
        storage.query(query, "lookupOpenMiningRoundByNumber")
      )
    } yield result.headOption.map(contractFromRow(OpenMiningRound.COMPANION)(_))
  }

  override def listActiveCalculateRewardsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]] =
    for {
      _ <- waitUntilInitialized
      result <- futureUnlessShutdownToFuture(
        storage.query(
          selectFromAcsTable(
            ScanRewardsReferenceTables.acsTableName,
            multiDomainAcsStore.acsStoreId,
            multiDomainAcsStore.domainMigrationId,
            CalculateRewardsV2.COMPANION,
            orderLimit = sql"""order by acs.round asc limit ${sqlLimit(limit)}""",
          ),
          "listActiveCalculateRewardsV2",
        )
      )
      limited = applyLimit("listActiveCalculateRewardsV2", limit, result)
    } yield limited.map(contractFromRow(CalculateRewardsV2.COMPANION)(_))

  override def listActiveCalculateRewardsV2ForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]] =
    for {
      _ <- waitUntilInitialized
      result <- futureUnlessShutdownToFuture(
        storage.query(
          selectFromAcsTable(
            ScanRewardsReferenceTables.acsTableName,
            multiDomainAcsStore.acsStoreId,
            multiDomainAcsStore.domainMigrationId,
            CalculateRewardsV2.COMPANION,
            where = sql"""acs.round = $roundNumber""",
          ),
          "listActiveCalculateRewardsV2ForRound",
        )
      )
    } yield result.map(contractFromRow(CalculateRewardsV2.COMPANION)(_))
}
