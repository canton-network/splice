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

  /** Check and ensure meta row exists.
    *
    * @param ingestionStart `Some((firstRecordTimeMicros, earliestRound))` when
    *                       the batch has activity records, `None` otherwise.
    *                       A meta row is only inserted when this is `Some`.
    */
  def ensure(
      ingestionStart: Option[(Long, Long)] = None
  )(implicit tc: TraceContext): Future[EnsureResult] = {
    if (checked.get()) Future.successful(Checked(Resume))
    else {
      activityStore.lookupMaxMetaVersions().flatMap { existing =>
        checkMetaVersions(existing, versions.code, versions.user) match {
          case InsertMeta =>
            ingestionStart match {
              case None =>
                Future.successful(NotReady)
              case Some((firstRecordTimeMicros, earliestIngestedRound)) =>
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
                    activityStore.setStartedIngestingAt(firstRecordTimeMicros)
                    checked.set(true)
                    Checked(InsertMeta)
                  }
            }
          case Resume =>
            activityStore
              .lookupActivityRecordMeta(versions.code, versions.user)
              .map { metaO =>
                metaO.foreach(m => activityStore.setStartedIngestingAt(m.startedIngestingAt))
                checked.set(true)
                Checked(Resume)
              }
          case d: DowngradeDetected =>
            Future.successful(Checked(d))
        }
      }
    }
  }
}

object ActivityIngestionMetaCheck {

  sealed trait EnsureResult
  case class Checked(result: MetaCheckResult) extends EnsureResult
  case object NotReady extends EnsureResult

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
