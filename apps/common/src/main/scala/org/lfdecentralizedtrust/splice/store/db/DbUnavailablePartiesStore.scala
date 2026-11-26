// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.store.db.AsUpdateReturning.`SQLActionBuilder asUpdateReturning`
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.{ExecutionContext, Future}

class DbUnavailablePartiesStore(
    storage: DbStorage,
    val storeId: Int,
    baseDuration: NonNegativeFiniteDuration,
    maxIgnoreDuration: NonNegativeFiniteDuration,
    clock: Clock,
    protectedParties: Set[PartyId] = Set.empty,
    val loggerFactory: NamedLoggerFactory,
    baseJitter: NonNegativeFiniteDuration = NonNegativeFiniteDuration.Zero,
)(implicit
    val ec: ExecutionContext,
    val loggingContext: ErrorLoggingContext,
    val closeContext: CloseContext,
) extends UnavailablePartiesStore
    with Queries
    with NamedLogging {

  val profile: JdbcProfile = storage.profile.jdbc

  private val baseMicros = baseDuration.underlying.toMicros
  private val maxMicros = maxIgnoreDuration.underlying.toMicros
  private val jitterMicros = baseJitter.underlying.toMicros

  override def addParties(
      parties: Seq[PartyId]
  )(implicit tc: TraceContext): Future[Set[PartyId]] = {
    val partiesToIgnore = parties.toSet -- protectedParties
    addPartiesAt(partiesToIgnore.toSeq, clock.now.toMicros).map(_ => partiesToIgnore)
  }

  override def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]] =
    listPartiesAt(clock.now.toMicros)

  /** Anti-join condition for batch queries, to be appended to a where clause: excludes rows where
    * one of the party fields is currently unavailable. The field expressions are interpolated
    * into the SQL as-is, so only pass string literals, never user input.
    */
  def excludeUnavailableClause(
      partyFields: Seq[String],
      partyArrayFields: Seq[String] = Seq.empty,
  ): SQLActionBuilder = {
    val nowMicros = clock.now.toMicros
    val scalarClauses = partyFields.map(f => sql""" and not exists (
          select 1 from dso_unavailable_parties u
          where u.store_id = $storeId
            and u.updated_at + u.ignore_duration > $nowMicros
            and u.party = #$f)""")
    val arrayClauses = partyArrayFields.map(f => sql""" and not exists (
          select 1 from dso_unavailable_parties u
          where u.store_id = $storeId
            and u.updated_at + u.ignore_duration > $nowMicros
            and u.party in (select jsonb_array_elements_text(#$f)))""")
    (scalarClauses ++ arrayClauses)
      .foldLeft(SQLActionBuilderChain(sql""))(_ ++ _)
      .toActionBuilder
  }

  /** Adds or updates parties only outside the ignore window.
    *  a. For new parties, it sets updated_at to now and the ignore_duration to base_duration plus a random jitter
    *     in [0, baseJitter), capped at max_ignore_duration, so that parties marked together do not all become eligible again at the same time.
    *  b. For existing parties, it updates updated_at to now and doubles the ignore_duration (up to max_ignore_duration)
    */
  private[splice] def addPartiesAt(parties: Seq[PartyId], nowMicros: Long)(implicit
      tc: TraceContext
  ): Future[Unit] =
    if (parties.isEmpty) Future.unit
    else {
      val partyArray = parties.distinct.toArray
      logger.debug(s"Marking ${partyArray.length} parties as unavailable at $nowMicros")
      storage
        .update(
          sql"""insert into dso_unavailable_parties
                  (party, updated_at, ignore_duration, store_id)
                select u.party, $nowMicros, least($baseMicros + (random() * $jitterMicros)::bigint, $maxMicros), $storeId
                from unnest($partyArray) as u(party)
                on conflict (store_id, party) do update
                  set updated_at = excluded.updated_at,
                      ignore_duration = least(
                            dso_unavailable_parties.ignore_duration * 2,
                            $maxMicros)
                  where dso_unavailable_parties.updated_at + dso_unavailable_parties.ignore_duration <= excluded.updated_at
             """.asUpdate,
          "addParties",
        )
        .map(_.discard)
    }

  // Removes specific parties from the table upon successful transaction processing.
  def removeParties(parties: Seq[PartyId])(implicit tc: TraceContext): Future[Seq[PartyId]] =
    if (parties.isEmpty) Future.successful(Seq.empty)
    else {
      val partyArray = parties.distinct.toArray
      storage
        .queryAndUpdate(
          sql"""delete from dso_unavailable_parties
                where party = any($partyArray) and store_id = $storeId
                returning party""".asUpdateReturning[PartyId],
          "removeParties",
        )
        .map(_.toSeq)
    }

  // List all parties for which updated_at + ignore_duration > nowMicros.
  private[splice] def listPartiesAt(nowMicros: Long)(implicit
      tc: TraceContext
  ): Future[Seq[PartyId]] =
    storage.query(
      sql"""select party
            from dso_unavailable_parties
            where updated_at + ignore_duration > $nowMicros and store_id = $storeId""".as[PartyId],
      "listParties",
    )
}

object DbUnavailablePartiesStore {
  def apply(
      storeDescriptor: StoreDescriptor,
      storage: DbStorage,
      baseDuration: NonNegativeFiniteDuration,
      maxIgnoreDuration: NonNegativeFiniteDuration,
      clock: Clock,
      protectedParties: Set[PartyId],
      loggerFactory: NamedLoggerFactory,
      baseJitter: NonNegativeFiniteDuration = NonNegativeFiniteDuration.Zero,
  )(implicit
      ec: ExecutionContext,
      lc: ErrorLoggingContext,
      cc: CloseContext,
      tc: TraceContext,
  ): Future[DbUnavailablePartiesStore] =
    StoreDescriptorStore
      .getStoreIdForDescriptor(storeDescriptor, storage)
      .map(storeId =>
        new DbUnavailablePartiesStore(
          storage,
          storeId,
          baseDuration,
          maxIgnoreDuration,
          clock,
          protectedParties,
          loggerFactory,
          baseJitter,
        )
      )
}
