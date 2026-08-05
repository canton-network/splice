// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.*
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsQueries}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers, UpdateHistory}
import org.lfdecentralizedtrust.splice.util.PackageQualifiedName
import slick.dbio.DBIO
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotPerTableStore(
    storage: DbStorage,
    val updateHistory: UpdateHistory,
    dsoParty: PartyId,
    val currentMigrationId: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, closeContext: CloseContext)
    extends AcsSnapshotStore
    with AcsJdbcTypes
    with AcsQueries
    with LimitHelpers
    with NamedLogging {
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  override val profile: JdbcProfile = storage.profile.jdbc
  import profile.api.jdbcActionExtensionMethods

  // TODO: this is stupid
  private implicit def rowsAlteredByIdempotencyCheck[A](implicit
      row: DbStorage.RowsAltered[A]
  ): DbStorage.RowsAltered[Option[A]] = _.exists(row(_))

  private def historyId = updateHistory.historyId

  override def initializeSnapshot(
      initializeFrom: AcsSnapshot,
      targetRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    assert(targetRecordTime.isAfter(initializeFrom.snapshotRecordTime))

    val statement = initializeFrom match {
      case perTable: PerTableAcsSnapshot =>
        // This case can probably never happen, but the implementation is the same as save anyway
        createSnapshotFromPrevious(perTable.snapshotRecordTime, targetRecordTime)
      case legacy: LegacyAcsSnapshot =>
        val newDataTableName =
          AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotCreatesTableName(targetRecordTime)
        val newFilterTableName =
          AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotFilterTableName(targetRecordTime)
        // We need to join with update_history_creates for the missing data.
        // Luckily though this will only run once after enabling per-table ACS snapshots.
        // TODO: it'd be nice to log the inserted rows
        DBIO.seq(
          // TODO: insert into progress table
          AcsSnapshotPerTableStore.ACSTableDDL.createDataTable(targetRecordTime),
          AcsSnapshotPerTableStore.ACSTableDDL.createFilterTable(targetRecordTime),
          // TODO: unlocked balances are not defined
          sqlu"""insert into #$newDataTableName (contract_id, create_arguments, signatories, observers, unlocked_amulet_balance, locked_amulet_balance)
                                          select contract_id, create_arguments, signatories, observers, unlocked_amulet_balance, locked_amulet_balance
                                          from acs_snapshot_data data
                                          join update_history_creates creates on data.create_id = creates.row_id
                                          where data.row_id between ${legacy.firstRowId} and ${legacy.lastRowId}
                                            -- The source table `acs_snapshot_data` contains one row per stakeholder for each contract,
                                            -- the target table contains only one row per contract.
                                            -- We know the DSO is a stakeholder of all contracts in the scan ACS, so we can filter by that.
                                            and data.stakeholder = $dsoParty """,
          // TODO: intern template_id
          sqlu"""insert into #$newFilterTableName (contract_id, stakeholder, template_id, created_at)
                                          select   contract_id, stakeholder, template_id, created_at
                                          from acs_snapshot_data data
                                          join update_history_creates creates on data.create_id = creates.row_id
                                          where data.row_id between ${legacy.firstRowId} and ${legacy.lastRowId} """,
          // TODO: should this be created on save?
          AcsSnapshotPerTableStore.ACSTableDDL.createStakeholderFilterIndex(targetRecordTime),
        )
    }

    // TODO: what about idempotency?
    storage.queryAndUpdate(statement.transactionally, "initializeSnapshot")
  }

  override def initializeSnapshotFromImportUpdates(
      recordTime: CantonTimestamp,
      targetRecordTime: CantonTimestamp,
      migrationId: Long,
  )(implicit tc: TraceContext): Future[Unit] = {
    assert(targetRecordTime.isAfter(recordTime))
    val newDataTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotCreatesTableName(targetRecordTime)
    val newFilterTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotFilterTableName(targetRecordTime)

    val createsFilter =
      sql"""
        where history_id = $historyId
          and migration_id = ${migrationId}
          and record_time = ${CantonTimestamp.MinValue}
         """
    val statement = DBIO.seq(
      // TODO: unlocked balances are not defined
      (sql"""insert into #$newDataTableName (contract_id, create_arguments, signatories, observers, unlocked_amulet_balance, locked_amulet_balance)
                                      select contract_id, create_arguments, signatories, observers, unlocked_amulet_balance, locked_amulet_balance
                                      from update_history_creates creates """ ++ createsFilter).toActionBuilder.asUpdate,
      // TODO: intern template_id
      (sql"""insert into #$newFilterTableName (contract_id, stakeholder, template_id, created_at)
                                      select   contract_id, stakeholder, template_id, created_at
                                      from update_history_creates creates
                                      cross join unnest(array_cat(signatories, observers)) as stakeholders(stakeholder) """ ++ createsFilter).toActionBuilder.asUpdate,
      // TODO: should this be created on save?
    )

    // TODO: what about idempotency?
    storage.queryAndUpdate(statement.transactionally, "initializeSnapshotFromImportUpdates")
  }

  override def updateSnapshot(
      snapshot: IncrementalAcsSnapshot,
      targetRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    assert(snapshot.historyId == historyId)
    // snapshot.recordTime < targetRecordTime <= snapshot.targetRecordTime
    assert(targetRecordTime.isAfter(snapshot.recordTime))
    assert(!targetRecordTime.isAfter(snapshot.targetRecordTime))
    logger.debug(
      s"Updating snapshot ${snapshot.snapshotId} from ${snapshot.recordTime} to $targetRecordTime"
    )

    val dataTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotCreatesTableName(snapshot.targetRecordTime)
    val filterTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotFilterTableName(targetRecordTime)

    val createsFilter =
      sql"""
        where creates.history_id = $historyId
          and creates.migration_id = ${snapshot.migrationId}
          and creates.record_time > ${snapshot.recordTime}
          and creates.record_time <= $targetRecordTime
         """
    val exercisesFilter = {
      sql"""
          and exercise.history_id = $historyId
          and exercise.migration_id = ${snapshot.migrationId}
          and exercise.record_time > ${snapshot.recordTime}
          and exercise.record_time <= $targetRecordTime
          and exercise.consuming
        """
    }

    val statement = for {
      insertedDataRows <-
        // TODO: unlocked balances are not defined
        (sql"""insert into #$dataTableName (contract_id, create_arguments, signatories, observers, unlocked_amulet_balance, locked_amulet_balance)
                                      select contract_id, create_arguments, signatories, observers, unlocked_amulet_balance, locked_amulet_balance
                                      from update_history_creates creates """ ++
          createsFilter).toActionBuilder.asUpdate
      // TODO: intern template_id
      insertedFilterRows <- (sql"""insert into #$filterTableName (contract_id, stakeholder, template_id, created_at)
                                      select   contract_id, stakeholder, template_id, created_at
                                      from update_history_creates creates
                                      cross join unnest(array_cat(signatories, observers)) as stakeholders(stakeholder) """ ++
        createsFilter).toActionBuilder.asUpdate
      deletedDataRows <-
        (sql"""
          delete
          from #$dataTableName as s
          using update_history_exercises as exercise
          where s.contract_id = exercise.contract_id """ ++ exercisesFilter).toActionBuilder.asUpdate
      deletedFilterRows <-
        (sql"""
          delete
          from #$filterTableName as s
          using update_history_exercises as exercise
          where s.contract_id = exercise.contract_id """ ++ exercisesFilter).toActionBuilder.asUpdate
      _ <- sqlu"""
          update acs_incremental_snapshot -- TODO: this should be the new progress table
          set record_time = $targetRecordTime
          where snapshot_id = ${snapshot.snapshotId}
        """
    } yield {
      // TODO: log as required
      logger.info(
        s"Updated incremental snapshot ${snapshot.snapshotId} from ${snapshot.recordTime} to $targetRecordTime. ACS: +$insertedDataRows, -$deletedDataRows. ACS*stakeholder: +$insertedFilterRows, -$deletedFilterRows"
      )
      ()
    }
    storage.queryAndUpdate(statement.transactionally, "updateIncrementalSnapshot")
  }

  override def saveSnapshot(
      snapshotToSave: IncrementalAcsSnapshot,
      nextSnapshotTargetRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Option[Int]] = {
    // Saving means the snapshot is complete, we can start on the new one.
    logger.debug(
      s"Saving incremental snapshot ${snapshotToSave.snapshotId} at ${snapshotToSave.recordTime}"
    )
    assert(snapshotToSave.historyId == historyId)
    assert(snapshotToSave.recordTime == snapshotToSave.targetRecordTime)
    assert(nextSnapshotTargetRecordTime > snapshotToSave.targetRecordTime)

    val statement = for {
      - <- createSnapshotFromPrevious(snapshotToSave.targetRecordTime, nextSnapshotTargetRecordTime)
      // now that we know the snapshot won't be modified anymore, we can create the necessary indexes
      // TODO: drop the contract_id index
      // TODO: configure vacuum?
      _ <- AcsSnapshotPerTableStore.ACSTableDDL.createStakeholderFilterIndex(
        snapshotToSave.targetRecordTime
      )
    } yield Option(0) // TODO: define

    // TODO: what about idempotency?
    storage.queryAndUpdate(statement.transactionally, "initializeSnapshotFromImportUpdates")
  }

  // TODO: it'd be nice if this returned some data
  private def createSnapshotFromPrevious(
      previousTime: CantonTimestamp,
      newTime: CantonTimestamp,
  ) = {
    val oldDataTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotCreatesTableName(previousTime)
    val oldFilterTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotFilterTableName(previousTime)
    val newDataTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotCreatesTableName(newTime)
    val newFilterTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotFilterTableName(newTime)

    // TODO: this includes no indexes nor primary key. Should they be created on save?
    DBIO.seq(
      sqlu"create table #$newDataTableName as table #$oldDataTableName",
      sqlu"create table #$newFilterTableName as table #$oldFilterTableName",
    )
  }

  override def deleteSnapshot(
      snapshot: IncrementalAcsSnapshot
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    deleteSnapshot(snapshot.targetRecordTime)
  }

  override def deleteSnapshot(snapshot: AcsSnapshot)(implicit tc: TraceContext): Future[Unit] = {
    deleteSnapshot(snapshot.snapshotRecordTime)
  }

  private def deleteSnapshot(
      snapshotRecordTime: CantonTimestamp
  )(implicit tc: TraceContext): Future[Unit] = {
    val dataTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotCreatesTableName(snapshotRecordTime)
    val filterTableName =
      AcsSnapshotPerTableStore.ACSTableDDL.acsSnapshotFilterTableName(snapshotRecordTime)

    val statement = for {
      _ <- sqlu"drop table if exists #$dataTableName"
      _ <- sqlu"drop table if exists #$filterTableName"
    } yield ()

    storage.queryAndUpdate(statement.transactionally, "deleteSnapshot")
  }

  // TODO: at what point do we give them the new progress?
  override def getIncrementalSnapshot()(implicit
      tc: TraceContext
  ): Future[Option[IncrementalAcsSnapshot]] = {
    Future.failed(io.grpc.Status.UNIMPLEMENTED.withDescription("TODO").asRuntimeException())
  }

  override def lookupSnapshotAtOrBefore(migrationId: Long, before: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[AcsSnapshot]] = {
    // TODO: This is verbatim from AcsSnapshotStore
    storage
      .querySingle(
        sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance, data_table_name
            from acs_snapshot
            where snapshot_record_time <= $before
              and migration_id = $migrationId
              and history_id = $historyId
            order by snapshot_record_time desc
            limit 1""".as[AcsSnapshot].headOption,
        "lookupSnapshotBefore",
      )
      .value
  }

  def lookupSnapshotAfter(
      migrationId: Long,
      after: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Option[AcsSnapshot]] = {
    // TODO: This is verbatim from AcsSnapshotStore
    val select =
      sql"select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance, data_table_name "
    val orderLimit = sql" order by snapshot_record_time asc limit 1 "
    val sameMig = select ++ sql""" from acs_snapshot
            where snapshot_record_time > $after
              and migration_id = $migrationId
              and history_id = $historyId """ ++ orderLimit
    val largerMig = select ++ sql""" from acs_snapshot
            where migration_id > $migrationId
              and history_id = $historyId """ ++ orderLimit

    val query =
      sql"select * from ((" ++ sameMig ++ sql") union all (" ++ largerMig ++ sql")) all_queries order by snapshot_record_time asc limit 1"

    storage
      .querySingle(
        query.toActionBuilder.as[AcsSnapshot].headOption,
        "lookupSnapshotAfter",
      )
      .value

  }

  override def queryAcsSnapshot(
      migrationId: Long,
      snapshot: CantonTimestamp,
      after: Option[Long],
      limit: Limit,
      partyIds: Seq[PartyId],
      templates: Seq[PackageQualifiedName],
  )(implicit tc: TraceContext): Future[QueryAcsSnapshotResult] = {
    Future.failed(io.grpc.Status.UNIMPLEMENTED.withDescription("TODO").asRuntimeException())
  }
}

object AcsSnapshotPerTableStore {

  object ACSTableDDL {
    // for both table names: epochMilli has no special characters and we don't take snapshots every nanosecond
    def acsSnapshotCreatesTableName(snapshotRecordTime: CantonTimestamp) =
      s"acs_snapshot_creates_${snapshotRecordTime.toEpochMilli}"
    def acsSnapshotFilterTableName(snapshotRecordTime: CantonTimestamp) =
      s"acs_snapshot_filter_${snapshotRecordTime.toEpochMilli}"

    // In principle, we could define a single table where we have one row per (contract_id, stakeholder).
    // The problem with that is that we then duplicate the create_arguments, which can be large (> 1MB).
    // The query plan will do an index-only scan on the filter table, then join via Primary Key on
    // the data table containing the create_arguments.

    def createDataTable(snapshotRecordTime: CantonTimestamp) = {
      val tableName = acsSnapshotCreatesTableName(snapshotRecordTime)
      sqlu"""
        create table #$tableName
          (
              contract_id       text   primary key,
              create_arguments  jsonb  not null,
              signatories       text[] not null,
              observers         text[] not null,
              unlocked_amulet_balance numeric,
              locked_amulet_balance   numeric
          );
          """
    }

    def createFilterTable(snapshotRecordTime: CantonTimestamp) = {
      val referencedAcsSnapshotCreatesTableName = acsSnapshotCreatesTableName(snapshotRecordTime)
      val tableName = acsSnapshotFilterTableName(snapshotRecordTime)
      sqlu"""
        create table #$tableName
          (
              contract_id text not null references #$referencedAcsSnapshotCreatesTableName (contract_id),
              stakeholder text not null,
              template_id text not null,
              created_at bigint not null,
              -- created_at and template_id are implied by contract_id (meaning: they're redundant),
              -- but this allows us to filter by (stakeholder, template_id) and sort by (created_at, contract_id)
              -- without defining additional indexes.
              primary key (stakeholder, template_id, created_at, contract_id)
          );
          """
    }

    // TODO: we need an index over contract_id for the deletes

    def createStakeholderFilterIndex(snapshotRecordTime: CantonTimestamp) = {
      val filterTableName = acsSnapshotFilterTableName(snapshotRecordTime)
      sqlu"""
       create index #${filterTableName}_s_ca_cid on #$filterTableName (stakeholder, created_at, contract_id);
          """
    }
  }

}
