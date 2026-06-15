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
    val acsSnapshotBulkStorage: AcsSnapshotBulkStorage,
    val updateHistoryBulkStorage: UpdateHistoryBulkStorage,
    storageConfig: ScanStorageConfig,
    s3Connection: S3BucketConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem)
    extends NamedLogging {

  def getAcsSnapshotAtOrBefore(
      atOrBeforeTimestamp: CantonTimestamp
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[AcsSnapshotObjects] = {
    for {
      snapshotTs <- acsSnapshotBulkStorage.persistentProgress.readLatestProcessedSnapshotTimestamp
        .map {
          case None =>
            throw Status.NOT_FOUND
              .withDescription("no snapshot in bulk storage yet")
              .asRuntimeException()
          case Some(ts) if ts.timestamp < atOrBeforeTimestamp =>
            logger.trace(
              s"Latest snapshot in bulk storage is at ${ts.timestamp}, which is before the requested timestamp ${atOrBeforeTimestamp}, returning that one"
            )
            ts.timestamp
          case Some(ts) => storageConfig.computeBulkSnapshotTimeAtOrBefore(atOrBeforeTimestamp)
        }
      prefix = storageConfig.findSegmentFolderPrefixByStartTimestamp(snapshotTs)
      objects <- s3Connection
        // A single object currently holds ~700K contracts, we apply a Limit just for safety,
        // but we don't expect to get anywhere near 1000 such objects in the foreseeable future
        // (hence the HardLimit, just as a safety precaution).
        .listObjects(
          prefix,
          _.matches(".*ACS_\\d+\\.zstd"),
          HardLimit.tryCreate(Limit.DefaultMaxPageSize),
        )
      objectsWithChecksums <- s3Connection.getChecksums(objects)
    } yield {
      if (objects.isEmpty) {
        throw Status.NOT_FOUND
          .withDescription(
            s"No snapshot objects found in bulk storage at expected timestamp at or before $atOrBeforeTimestamp, this may be because the timestamp is before network genesis"
          )
          .asRuntimeException()
      }
      logger.trace(
        s"Found snapshot in bulk storage at timestamp $snapshotTs, with objects: ${objects.mkString(", ")}"
      )
      AcsSnapshotObjects(snapshotTs, objectsWithChecksums)
    }
  }

  def getUpdatesBetweenDates(
      afterRecordTime: CantonTimestamp,
      atOrBeforeRecordTime: CantonTimestamp,
      limit: PageLimit,
      nextPageTokenO: Option[String],
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
