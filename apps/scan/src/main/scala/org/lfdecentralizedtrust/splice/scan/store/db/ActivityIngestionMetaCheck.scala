// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.EnsureResult

import scala.concurrent.Future

/** Thin wrapper around [[DbAppActivityRecordStore.ensureMeta]].
  * Will be removed once meta table maintenance is fully internal to the store.
  */
class ActivityIngestionMetaCheck(
    activityStore: DbAppActivityRecordStore,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  def ensure(
      ingestionStart: Option[(Long, Long)] = None
  )(implicit tc: TraceContext): Future[EnsureResult] =
    activityStore.ensureMeta(ingestionStart)
}

object ActivityIngestionMetaCheck
