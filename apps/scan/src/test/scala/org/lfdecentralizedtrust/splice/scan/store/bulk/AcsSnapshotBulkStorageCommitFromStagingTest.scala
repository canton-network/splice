// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.daml.metrics.api.testing.InMemoryMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.WallClock
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanKeyValueStore}
import org.lfdecentralizedtrust.splice.store.HistoryMetrics

import scala.concurrent.Future
import scala.util.Using

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

  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24,
    bulkDbReadChunkSize = 1000,
    bulkZstdFrameSize = 10000L,
    bulkMaxFileSize = 50000L,
    zstdCompressionLevel = 3,
  )
  val appConfig = BulkStorageConfig(
    snapshotPollingInterval = NonNegativeFiniteDuration.ofSeconds(5)
  )

  // FIXME: quite a lot of duplication with AcsSnapshotBulkStorageWriterFromDbTest, consider consolidating

  override val initialBuckets: Seq[String] = Seq("staging", "committed")

  "AcsSnapshotBulkStorageCommitFromStaging" should {
    "successfully move ACS snapshot objects from staging to committed S3 bucket" in {

      val stagingConnection = new S3BucketConnectionForUnitTests(s3ConfigMock("staging"), loggerFactory)
      val committedConnection = new S3BucketConnectionForUnitTests(s3ConfigMock("committed"), loggerFactory)
      val metricsFactory = new InMemoryMetricsFactory
      val historyMetrics = new HistoryMetrics(metricsFactory)(MetricsContext.Empty)
      val kvProvider = mkKvProvider.futureValue
      val retryProvider = {
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
      }

      val acsStagingProgress = new AcsSnapshotBulkStoragePersistentProgress(
        BulkStorage.acsStagingKvStoreKey,
        BulkStorage.firstAcsSnapshotTimestampKvStoreKey,
        kvProvider,
        HistoryMetrics(metricsFactory, 0L).BulkStorage.latestAcsSnapshotStaging,
        loggerFactory,
      )
      val acsCommittedProgress = new AcsSnapshotBulkStoragePersistentProgress(
        BulkStorage.acsCommittedKvStoreKey,
        BulkStorage.firstAcsSnapshotTimestampKvStoreKey,
        kvProvider,
        HistoryMetrics(metricsFactory, 0L).BulkStorage.latestAcsSnapshotCommitted,
        loggerFactory,
      )
      val reader = new BulkStorageReader(
        acsStagingProgress,
        acsCommittedProgress,
        null, // no ACS snapshots in this test
        null, // no ACS snapshots in this test
        bulkStorageTestConfig,
        stagingConnection,
        committedConnection,
        loggerFactory,
      )



      val acsCommittedWriter = new AcsSnapshotBulkStorageCommitFromStaging(
        stagingConnection,
        committedConnection,
        reader,
        loggerFactory,
      )
      val progress = new AcsSnapshotBulkStoragePersistentProgress(
        "latest_acs_snapshot_committed",
        "first_acs_snapshot_in_bulk_storage",
        kvProvider,
        historyMetrics.BulkStorage.latestAcsSnapshotCommitted,
        loggerFactory,
      )
      val acsCommitted = new AcsSnapshotBulkStorage(
        "AcsSnapshotBulkStorageCommitted",
        acsCommittedWriter,
        progress,
        appConfig,
        Source.single(true).mapMaterializedValue(_ => Cancellable.alreadyCancelled),
        loggerFactory,
      )
      val svc = acsCommitted.asRetryableService(
        AutomationConfig(pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)), // Fast retries
        new WallClock(timeouts, loggerFactory),
        retryProvider,
      )

      Using.resources(svc, retryProvider) { (_, _) =>
        succeed
      }
    }
  }

  def mkKvProvider: Future[ScanKeyValueProvider] = {
    ScanKeyValueStore(
      dsoParty = dsoParty,
      participantId = mkParticipantId("participant"),
      storage,
      loggerFactory,
    ).map(new ScanKeyValueProvider(_, loggerFactory))
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
