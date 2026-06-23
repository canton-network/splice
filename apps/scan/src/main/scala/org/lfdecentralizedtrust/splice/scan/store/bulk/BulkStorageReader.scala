// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdateHistoryBulkStorage.UpdateHistoryObjectsResponse
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, PageLimit, S3BucketConnection}

import scala.concurrent.{ExecutionContext, Future}

class BulkStorageReader(
    val acsSnapshotBulkStorageStaging: AcsSnapshotBulkStorage,
    val acsSnapshotBulkStorageCommitted: AcsSnapshotBulkStorage,
    val updateHistoryBulkStorage: UpdateHistoryBulkStorage,
    storageConfig: ScanStorageConfig,
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private def getAcsSnapshotObjects(
      timestamp: CantonTimestamp,
      s3Connection: S3BucketConnection,
      storageConfig: ScanStorageConfig,
  ): Future[AcsSnapshotObjects] = {
    for {
      objects <- s3Connection
        // A single object currently holds ~700K contracts, we apply a Limit just for safety,
        // but we don't expect to get anywhere near 1000 such objects in the foreseeable future
        // (hence the HardLimit, just as a safety precaution).
        .listObjects(
          storageConfig.findSegmentFolderPrefixByStartTimestamp(timestamp),
          _.matches(".*ACS_\\d+\\.zstd"),
          HardLimit.tryCreate(Limit.DefaultMaxPageSize),
        )
      objectsWithChecksums <- s3Connection.getChecksums(objects)
    } yield {
      if (objects.isEmpty) {
        throw Status.NOT_FOUND
          .withDescription(
            s"No snapshot objects found in bulk storage at expected timestamp $timestamp, this may be because the timestamp is before network genesis"
          )
          .asRuntimeException()
      }
      logger.trace(
        s"Found snapshot in bulk storage at timestamp $timestamp, with objects: ${objects.mkString(", ")}"
      )
      AcsSnapshotObjects(timestamp, objectsWithChecksums)
    }
  }

  def getCommittedObjectsForAcsSnapshotAtOrBefore(
      atOrBeforeTimestamp: CantonTimestamp
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[AcsSnapshotObjects] = {
    for {
      snapshotTs <-
        acsSnapshotBulkStorageCommitted.persistentProgress.readLatestProcessedSnapshotTimestamp
          .map {
            case None =>
              throw Status.NOT_FOUND
                .withDescription("no snapshot in committed bulk storage yet")
                .asRuntimeException()
            case Some(ts) if ts.timestamp < atOrBeforeTimestamp =>
              logger.trace(
                s"Latest snapshot in committed bulk storage is at ${ts.timestamp}, which is before the requested timestamp $atOrBeforeTimestamp, returning that one"
              )
              ts.timestamp
            case Some(ts) => storageConfig.computeBulkSnapshotTimeAtOrBefore(atOrBeforeTimestamp)
          }
      objects <- getAcsSnapshotObjects(snapshotTs, committedS3Connection, storageConfig)
    } yield {
      objects
    }
  }

  def getTimestampOfStagingAcsSnapshotAfter(
      afterTimestamp: CantonTimestamp
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[CantonTimestamp]] = {
    logger.trace(s"Looking for the next snapshot in staging bulk storage after $afterTimestamp")
    acsSnapshotBulkStorageStaging.persistentProgress.readLatestProcessedSnapshotTimestamp
      .flatMap {
        case None =>
          logger.debug(
            s"No snapshot in staging bulk storage yet"
          )
          Future.successful(None)
        case Some(ts) if ts.timestamp <= afterTimestamp =>
          logger.debug(
            s"Latest snapshot in staging bulk storage is at ${ts.timestamp}, which is not after the requested timestamp $afterTimestamp"
          )
          Future.successful(None)
        case Some(ts) if afterTimestamp == CantonTimestamp.MinValue =>
          logger.debug(
            "snapshots exist in staging bulk storage, but `afterTimestamp` is at minimum value, finding the first snapshot expected in staging bulk storage"
          )
          getFirstAcsSnapshotTimestampAfterGenesis.map(Some(_)).recover {
            case ex if Status.fromThrowable(ex).getCode == Status.Code.NOT_FOUND =>
              // FIXME: once we have committed snapshots, update this message to be "have not been copied to committed bulk storage yet"
              logger.debug(
                "Could not yet find the first snapshot, most probably because the first updates have not been dumped to bulk storage yet, returning None to retry later"
              )
              None
          }
        case Some(ts) =>
          logger.trace(
            s"Latest snapshot in staging bulk storage is at ${ts.timestamp}, which is after the requested timestamp $afterTimestamp, computing the next snapshot timestamp after $afterTimestamp (which is larger than min time of ${CantonTimestamp.MinValue})"
          )
          Future.successful(
            Some(
              storageConfig.computeSnapshotTimeAfter(
                afterTimestamp,
                storageConfig.bulkAcsSnapshotPeriodHours,
              )
            )
          )
      }
  }

  private def getFirstAcsSnapshotTimestampAfterGenesis: Future[CantonTimestamp] = {
    val genesisPrefix =
      storageConfig.findSegmentFolderPrefixByStartTimestamp(CantonTimestamp.MinValue)

    // FIXME: once we have committed updates, this needs to use committed instead of staging
    stagingS3Connection.listObjects(genesisPrefix, _ => true, PageLimit.tryCreate(1)).map {
      objects =>
        val firstObject = objects.headOption.getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              s"No updates found in staging bulk storage for the genesis segment"
            )
            .asRuntimeException()
        )
        val folderPrefix = firstObject.substring(0, firstObject.lastIndexOf('/') + 1)
        storageConfig.getStartAndEndTimestampsForFolder(folderPrefix) match {
          case Left(err) =>
            throw Status.INTERNAL
              .withDescription(
                s"Cannot parse folder name $folderPrefix, error: $err"
              )
              .asRuntimeException()
          case Right((_, segmentEnd)) =>
            logger.debug(
              s"Found first snapshot in staging bulk storage, ending at timestamp $segmentEnd, (based on object: $firstObject)"
            )
            segmentEnd
        }
    }
  }

  // TODO: improve consistency below on querying staging vs committed vs both
  def getStagingObjectsForAcsSnapshotAt(
      timestamp: CantonTimestamp
  ): Future[AcsSnapshotObjects] = {
    getAcsSnapshotObjects(timestamp, stagingS3Connection, storageConfig)
  }

  def getUpdatesBetweenDates(
      afterRecordTime: CantonTimestamp,
      atOrBeforeRecordTime: CantonTimestamp,
      limit: PageLimit,
      nextPageTokenO: Option[String],
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[UpdateHistoryObjectsResponse] =
    getUpdatesBetweenDatesFromBucket(
      afterRecordTime,
      atOrBeforeRecordTime,
      limit,
      nextPageTokenO,
      stagingS3Connection,
    )

  private def getUpdatesBetweenDatesFromBucket(
      afterRecordTime: CantonTimestamp,
      atOrBeforeRecordTime: CantonTimestamp,
      limit: PageLimit,
      nextPageTokenO: Option[String],
      s3Connection: S3BucketConnection,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[UpdateHistoryObjectsResponse] = {

    def isFolderInRange(folder: String): Boolean = {
      storageConfig.getStartAndEndTimestampsForFolder(folder) match {
        case Left(err) =>
          throw io.grpc.Status.INTERNAL
            .withDescription(
              s"Cannot parse folder name $folder, error: $err"
            )
            .asRuntimeException()
        case Right((folderStart, folderEnd)) =>
          folderStart < atOrBeforeRecordTime && folderEnd > afterRecordTime
      }
    }

    def isFolderFullyDumped(folder: String, lastSegmentEnd: CantonTimestamp): Boolean = {
      storageConfig.getStartAndEndTimestampsForFolder(folder) match {
        case Left(err) =>
          throw io.grpc.Status.INTERNAL
            .withDescription(
              s"Cannot parse folder name $folder, error: $err"
            )
            .asRuntimeException()
        case Right((folderStart, folderEnd)) =>
          folderEnd <= lastSegmentEnd
      }
    }

    def paginationFilter(folder: String): Boolean = {
      nextPageTokenO match {
        case None => true
        case Some(token) => folder > token
      }
    }

    def getUpdateObjectsInFolder(folder: String): Future[Seq[String]] = s3Connection.listObjects(
      prefix = folder,
      _.matches(".*updates_\\d+\\.zstd"),
      HardLimit.tryCreate(Limit.DefaultMaxPageSize),
    )

    def folderFilter(storageCaughtUpTo: Option[UpdatesSegment])(folder: String): Boolean = {
      storageCaughtUpTo match {
        case None =>
          false
        case Some(segment) =>
          isFolderInRange(folder) && paginationFilter(folder) && isFolderFullyDumped(
            folder,
            segment.toTimestamp.timestamp,
          )
      }
    }

    // TODO(#3429): Make sure to properly document the case where the user asked for an end timestamp that is later than what we have in storage.
    // Specifically: we still return the last folder as a "next page token" in that case, and in the next page, we return an empty result.
    def getNextPageToken(
        objKeys: Seq[String],
        storageCaughtUpTo: Option[UpdatesSegment],
    ): Option[String] = {
      /* We return a next page token when:
         - we found some objects to return (i.e. objKeys is non-empty), and the end time in the last folder we
           listed is before the atOrBeforeRecordTime (i.e. there are more folders to list that are in range, but we stopped listing due to the limit. We then use the last folder as the next page token)
         - or -
         - we found no objects to return, but the requested end time is later than the latest dumped data, so we want to signal the user to try again later
       */
      objKeys.lastOption.fold(
        storageCaughtUpTo match {
          case None => nextPageTokenO
          case Some(segment) =>
            if (segment.toTimestamp.timestamp < atOrBeforeRecordTime) {
              // We have dumped data up to a segment that ends before the requested end time, so return the current nextPageToken again, to be retried later
              nextPageTokenO
            } else {
              // We have dumped data up to a segment that ends after the requested end time, so we do not return a next page token, as there is no more data to list for this request
              None
            }
        }
      )(lastObjKey => {
        val lastFolder = lastObjKey.substring(0, lastObjKey.lastIndexOf('/') + 1)
        storageConfig.getStartAndEndTimestampsForFolder(lastFolder) match {
          case Left(err) =>
            throw io.grpc.Status.INTERNAL
              .withDescription(
                s"Cannot parse last folder name for next page token: $lastFolder, error: $err"
              )
              .asRuntimeException()
          case Right((_, folderEnd)) =>
            if (folderEnd < atOrBeforeRecordTime) {
              Some(lastFolder)
            } else {
              None
            }
        }
      })
    }

    def getFolderUpdateObjectsUpToLimit(folders: Seq[String]): Future[Seq[String]] = {
      folders
        .foldLeft(Future.successful((Seq.empty[String], limit.limit))) {
          case (futFolderState, folder) =>
            futFolderState.flatMap { case (folderAcc, folderLimit) =>
              if (folderLimit <= 0) {
                Future.successful((folderAcc, folderLimit))
              } else {
                getUpdateObjectsInFolder(folder).map { folderObjs =>
                  if (folderObjs.size > folderLimit) {
                    // Folder would exceed the limit; omit it entirely (and stop adding more by making the limit 0)
                    if (folderAcc.isEmpty) {
                      throw io.grpc.Status.INVALID_ARGUMENT
                        .withDescription(
                          s"Limit of ${limit.limit} is too low to return any objects, even from a single folder of objects"
                        )
                        .asRuntimeException()
                    }
                    (folderAcc, 0)
                  } else {
                    (folderAcc ++ folderObjs, folderLimit - folderObjs.size)
                  }
                }
              }
            }
        }
        .map(_._1)
    }

    for {
      // We first read the storageCaughtUpTo value once here, and then use it for filtering folders and computing the next page token, to avoid races where the value moves in between those operations.
      storageCaughtUpTo <- updateHistoryBulkStorage.persistentProgress.readLatestProcessedSegment
      nextFolders <- s3Connection.listFolders(folderFilter(storageCaughtUpTo), limit)
      objKeys <- getFolderUpdateObjectsUpToLimit(nextFolders)
      objectsWithChecksums <- s3Connection.getChecksums(objKeys)
      nextPageTokenO = getNextPageToken(objKeys, storageCaughtUpTo)
    } yield {
      UpdateHistoryObjectsResponse(
        objectsWithChecksums,
        nextPageTokenO,
      )
    }
  }

}
