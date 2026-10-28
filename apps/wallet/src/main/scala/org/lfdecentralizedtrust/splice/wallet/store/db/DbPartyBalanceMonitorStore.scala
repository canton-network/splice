// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.lfdecentralizedtrust.splice.wallet.store.PartyBalanceMonitorStore
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

/** @param acsStoreId the id of the wallet ACS store of the party being monitored */
class DbPartyBalanceMonitorStore(
    storage: DbStorage,
    acsStoreId: AcsStoreId,
)(implicit ec: ExecutionContext, closeContext: CloseContext)
    extends PartyBalanceMonitorStore {

  private val storeId: Int = AcsStoreId.unwrap(acsStoreId)

  override def getLastBelowLimit()(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
    futureUnlessShutdownToFuture(
      storage
        .querySingle(
          sql"""select last_below_limit_at
                from wallet_party_balance_monitor
                where store_id = $storeId""".as[Long].headOption,
          "getLastBelowLimit",
        )
        .value
    ).map(_.map(CantonTimestamp.assertFromLong))

  override def setLastBelowLimit(timestamp: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Unit] =
    storage
      .update(
        sqlu"""insert into wallet_party_balance_monitor (store_id, last_below_limit_at)
               values ($storeId, ${timestamp.toMicros})
               on conflict (store_id) do update
               set last_below_limit_at = excluded.last_below_limit_at""",
        "setLastBelowLimit",
      )
      .map(_ => ())
}
