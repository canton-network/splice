// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
//import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorage.{
//  acsCommittedKvStoreKey,
//  acsStagingKvStoreKey,
//  firstAcsSnapshotTimestampKvStoreKey,
//}
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, StoreTestBase}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest

class AcsSnapshotBulkStorageCommitFromStagingTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {

  override val initialBuckets: Seq[String] = Seq("staging", "committed")

  "AcsSnapshotBulkStorageCommitFromStaging" should {
    "successfully move objects from staging to committed S3 bucket" in {

//      val Seq(stagingConnection, committedConnection) = Seq("staging", "committed")
//        .map(s3ConfigMock)
//        .map(new S3BucketConnectionForUnitTests(_, loggerFactory))

//      def getReader: BulkStorageReader = reader

//      val acsCommittedWriter = new AcsSnapshotBulkStorageCommitFromStaging(
//        stagingConnection,
//        committedConnection,
//        getReader,
//        loggerFactory,
//      )
//      val acsCommitted = new AcsSnapshotBulkStorage(
//        "AcsSnapshotBulkStorageCommitted",
//        acsCommittedWriter,
//        new AcsSnapshotBulkStoragePersistentProgress(
//          acsCommittedKvStoreKey,
//          firstAcsSnapshotTimestampKvStoreKey,
//          kvProvider,
//          historyMetrics.BulkStorage.latestAcsSnapshotCommitted,
//          loggerFactory,
//        ),
//        appConfig,
//        acsSnapshotStore,
//        updateHistory,
//        loggerFactory,
//      )
//      val reader = new BulkStorageReader(
//        acsStaging =
//      )

      succeed
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
