// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.ActivityIngestionMetaCheck.*

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

/** Tracks and validates activity record ingestion metadata.
  *
  * This class manages the lifecycle of the `app_activity_record_meta`
  * table row that records when ingestion started and which versions
  * are running.
  *
  * On the first batch that produces activity records, [[ensure]]
  * creates the meta row storing the current code/user version and the
  * earliest ingested round. On subsequent batches it is a no-op. If a
  * version downgrade is detected, it returns [[DowngradeDetected]] so
  * the caller can shut down.
  */
class ActivityIngestionMetaCheck(
    activityStore: DbAppActivityRecordStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val versions = activityStore.ingestionVersions

  private val checked = new AtomicBoolean(false)

  /** Whether the meta check has completed successfully at least once. */
  def isChecked: Boolean = checked.get()

  def ensure(
      firstRecordTimeMicros: Long,
      earliestIngestedRound: Long,
  )(implicit tc: TraceContext): Future[MetaCheckResult] = {
    if (checked.get()) Future.successful(Resume)
    else {
      activityStore.lookupMaxMetaVersions().flatMap { existing =>
        checkMetaVersions(existing, versions.code, versions.user) match {
          case InsertMeta =>
            val label = if (existing.isDefined) "version upgrade" else "initializing"
            logger.info(
              s"Activity record meta $label: codeVersion=${versions.code}, " +
                s"userVersion=${versions.user}, startedIngestingAt=$firstRecordTimeMicros, " +
                s"earliestIngestedRound=$earliestIngestedRound"
            )
            activityStore
              .insertActivityRecordMeta(
                versions.code,
                versions.user,
                firstRecordTimeMicros,
                earliestIngestedRound,
              )
              .map { _ =>
                checked.set(true)
                InsertMeta
              }
          case Resume =>
            checked.set(true)
            Future.successful(Resume)
          case d: DowngradeDetected =>
            Future.successful(d)
        }
      }
    }
  }
}

object ActivityIngestionMetaCheck {

  sealed trait MetaCheckResult
  case object InsertMeta extends MetaCheckResult
  case object Resume extends MetaCheckResult
  final case class DowngradeDetected(
      runningCode: Int,
      runningUser: Int,
      storedCode: Int,
      storedUser: Int,
  ) extends MetaCheckResult {
    def message: String =
      s"Activity ingestion version downgrade detected: " +
        s"running=($runningCode,$runningUser), stored=($storedCode,$storedUser). " +
        s"Shutting down to prevent data corruption."
  }

  def checkMetaVersions(
      existing: Option[(Int, Int)],
      runningCode: Int,
      runningUser: Int,
  ): MetaCheckResult = existing match {
    case None => InsertMeta
    case Some((storedCode, storedUser)) =>
      if (runningCode < storedCode || runningUser < storedUser)
        DowngradeDetected(runningCode, runningUser, storedCode, storedUser)
      else if (runningCode > storedCode || runningUser > storedUser)
        InsertMeta
      else
        Resume
  }
}
