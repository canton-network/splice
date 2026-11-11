// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.{Identifier, Template}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.google.protobuf.ByteString
import io.circe.Json
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{ContractCompanion, ContractState}
import org.lfdecentralizedtrust.splice.store.StoreErrors
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{AcsStoreId, SelectFromAcsTableResult}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import scalaz.{@@, Tag}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder
import slick.jdbc.{GetResult, PositionedResult}
import slick.dbio.Effect
import slick.sql.SqlStreamingAction

trait AcsQueries extends Queries with AcsJdbcTypes {

  /** @param tableName Must be SQL-safe, as it needs to be interpolated unsafely.
    *                  This is fine, as all calls to this method should use static string constants.
    */
  protected def selectFromAcsTable[C, TCid <: ContractId[?], T](
      tableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      where: SQLActionBuilder = sql"true",
      orderLimit: SQLActionBuilder = sql"",
  )(implicit companionClass: ContractCompanion[C, TCid, T]) = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    (sql"""
       select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()}
       from #$tableName acs
       where acs.store_id = $storeId
         and acs.migration_id = $migrationId
         and acs.package_name = ${packageQualifiedName.packageName}
         and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
         and """ ++ where ++ sql"""
       """ ++ orderLimit).toActionBuilder.as[AcsQueries.SelectFromAcsTableResult]
  }

  implicit val GetResultSelectFromAcsTable: GetResult[AcsQueries.SelectFromAcsTableResult] =
    GetResult { prs =>
      import prs.*
      (AcsQueries.SelectFromAcsTableResult.apply _).tupled(
        (
          <<[AcsStoreId],
          <<[Long],
          <<[Long],
          <<[ContractId[Any]],
          <<[String],
          <<[PackageQualifiedName],
          <<[Json],
          <<[Array[Byte]],
          <<[Timestamp],
          <<[Option[Timestamp]],
        )
      )
    }

  /** Similar to [[selectFromAcsTable]] */
  protected def selectFromAcsTableWithState[C, TCid <: ContractId[?], T](
      tableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      additionalWhere: SQLActionBuilder = sql"",
      orderLimit: SQLActionBuilder = sql"",
  )(implicit companionClass: ContractCompanion[C, TCid, T]): SqlStreamingAction[Vector[
    SelectFromAcsTableResult
  ], SelectFromAcsTableResult, Effect.Read] = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    (sql"""
       select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()}
       from #$tableName acs
       where acs.store_id = $storeId
         and acs.migration_id = $migrationId
         and acs.package_name = ${packageQualifiedName.packageName}
         and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
         """ ++ additionalWhere ++ sql"""
       """ ++ orderLimit).toActionBuilder.as[AcsQueries.SelectFromAcsTableResult]
  }

  /** Same as [[selectFromAcsTable]], but joins with the store_descriptors and store_last_ingested_offsets table to get the last_ingested_offset.
    * This guarantees that the fetched contracts exist in the given offset,
    * whereas two separate queries (one to fetch the contract and one to fetch the offset) don't guarantee that.
    */
  protected def selectFromAcsTableWithOffset[C, TCid <: ContractId[?], T](
      tableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      where: SQLActionBuilder = sql"true",
      orderLimit: SQLActionBuilder = sql"",
  )(implicit companionClass: ContractCompanion[C, TCid, T]) = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    (sql"""
       select
         acs.store_id,
         acs.migration_id,
         o.last_ingested_offset,
         event_number,
         contract_id,
         template_id_package_id,
         template_id_qualified_name,
         package_name,
         create_arguments,
         created_event_blob,
         created_at,
         contract_expires_at
       from store_descriptors sd
           left join store_last_ingested_offsets o
               on sd.id = o.store_id
           left join #$tableName acs
               on o.store_id = acs.store_id
               and o.migration_id = acs.migration_id
               and acs.package_name = ${packageQualifiedName.packageName}
               and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
               and (""" ++ where ++ sql""")
       where sd.id = $storeId and o.migration_id = $migrationId
       """ ++ orderLimit).toActionBuilder
      .as[AcsQueries.SelectFromAcsTableResultWithOffset]
  }

  implicit val GetResultSelectFromAcsTableResultWithOffset
      : GetResult[AcsQueries.SelectFromAcsTableResultWithOffset] = { (pp: PositionedResult) =>
    val storeIdFromAcsRow = pp.<<[Option[AcsStoreId]]
    val migrationIdFromAcsRow = pp.<<[Option[Long]]
    AcsQueries.SelectFromAcsTableResultWithOffset(
      LegacyOffset.Api.assertFromStringToLong(pp.<<[String]),
      for {
        storeId <- storeIdFromAcsRow
        migration_id <- migrationIdFromAcsRow
      } yield AcsQueries.SelectFromAcsTableResult(
        storeId,
        migration_id,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
      ),
    )
  }

  protected def contractFromRow[C, TCId <: ContractId[?], T](companion: C)(
      row: AcsQueries.SelectFromAcsTableResult
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      decoder: TemplateJsonDecoder,
  ): Contract[TCId, T] = {
    row.toContract(companion)
  }

  /** Decodes a stored ACS row without a caller-supplied companion.
    * Resolves the template from the row via [[ContractCompanions]].
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  protected def genericContractFromRow(
      row: AcsQueries.SelectFromAcsTableResult
  )(implicit
      decoder: TemplateJsonDecoder
  ): Either[String, Contract[?, ?]] =
    ContractCompanions
      .lookup(row.templateId)
      .map { companion =>
        // `ContractCompanions.lookup` erases the companion's type parameters, so we have to cast
        // to concrete bounds in order for the `ContractCompanion` implicit to resolve
        row.toContract(
          companion.asInstanceOf[Contract.Companion.Template[ContractId[Template], Template]]
        )
      }
}

object AcsQueries {

  sealed trait AcsStoreIdTag
  type AcsStoreId = Int @@ AcsStoreIdTag
  val AcsStoreId = Tag.of[AcsStoreIdTag]

  case class SelectFromAcsTableResult(
      storeId: AcsStoreId,
      migrationId: Long,
      eventNumber: Long,
      contractId: ContractId[Any],
      templateIdPackageId: String,
      packageQualifiedName: PackageQualifiedName,
      createArguments: Json,
      createdEventBlob: Array[Byte],
      createdAt: Timestamp,
      contractExpiresAt: Option[Timestamp],
  ) extends StoreErrors {
    private[AcsQueries] lazy val templateId: Identifier = new Identifier(
      templateIdPackageId,
      packageQualifiedName.qualifiedName.moduleName,
      packageQualifiedName.qualifiedName.entityName,
    )

    def toContract[C, TCId <: ContractId[?], T](companion: C)(implicit
        companionClass: ContractCompanion[C, TCId, T],
        decoder: TemplateJsonDecoder,
    ): Contract[TCId, T] = {
      // safety check: if the PackageQualifiedNames don't match,
      // it means that we would be returning a contract of a different template
      // note that the packageId not matching is expected due to upgrades, but the name will be stable
      val expectedPackageQualifiedName = companionClass.packageQualifiedName(companion)
      if (expectedPackageQualifiedName != packageQualifiedName) {
        throw new IllegalStateException(
          s"Contract $contractId has a different package qualified name than expected. Expected: $expectedPackageQualifiedName - Got: $packageQualifiedName"
        )
      }

      companionClass
        .fromJson(companion)(
          templateId,
          contractId.contractId,
          createArguments,
          ByteString.copyFrom(createdEventBlob),
          createdAt.toInstant,
        )
        .fold(
          _ =>
            throw contractIdNotFound(
              PrettyContractId(companionClass.typeId(companion), contractId.contractId)
            ),
          identity,
        )
    }
  }

  object SelectFromAcsTableResult {
    def sqlColumnsCommaSeparated(qualifier: String = "") =
      s"""${qualifier}store_id,
          ${qualifier}migration_id,
          ${qualifier}event_number,
          ${qualifier}contract_id,
          ${qualifier}template_id_package_id,
          ${qualifier}template_id_qualified_name,
          ${qualifier}package_name,
          ${qualifier}create_arguments,
          ${qualifier}created_event_blob,
          ${qualifier}created_at,
          ${qualifier}contract_expires_at"""
  }

  case class SelectFromAcsTableResultWithOffset(
      offset: Long,
      row: Option[SelectFromAcsTableResult],
  )
}

trait SingleSynchronizerAcsQueries extends AcsQueries {

  protected def acsSynchronizerId: SynchronizerId

  protected def assignedContractFromRow[C, TCid <: ContractId[?], T](companion: C)(
      row: SelectFromAcsTableResult
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      templateJsonDecoder: TemplateJsonDecoder,
  ): AssignedContract[TCid, T] =
    AssignedContract(contractFromRow(companion)(row), acsSynchronizerId)

  protected def contractWithStateFromRow[C, TCid <: ContractId[?], T](companion: C)(
      row: SelectFromAcsTableResult
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      templateJsonDecoder: TemplateJsonDecoder,
  ): ContractWithState[TCid, T] =
    ContractWithState(
      contractFromRow(companion)(row),
      ContractState.Assigned(acsSynchronizerId),
    )
}
