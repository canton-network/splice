// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.daml.metrics.api.testing.InMemoryMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.WallClock
import io.grpc.StatusRuntimeException
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanKeyValueStore}
import org.lfdecentralizedtrust.splice.store.{
  HasS3Mock,
  HistoryMetrics,
  StoreTestBase,
  TimestampWithMigrationId,
}

import scala.concurrent.Future
import scala.util.Using
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.slf4j.event.Level

import java.time.Instant

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

      val stagingConnection =
        new S3BucketConnectionForUnitTests(s3ConfigMock("staging"), loggerFactory)
      val committedConnection =
        new S3BucketConnectionForUnitTests(s3ConfigMock("committed"), loggerFactory)
      val metricsFactory = new InMemoryMetricsFactory
//      val historyMetrics = new HistoryMetrics(metricsFactory)(MetricsContext.Empty)
      val kvProvider = mkKvProvider.futureValue
      val retryProvider = {
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
      }
      val ts1 = CantonTimestamp.tryFromInstant(Instant.parse("2026-01-02T00:00:00Z"))
      val ts2 = CantonTimestamp.tryFromInstant(Instant.parse("2026-01-03T00:00:00Z"))
      val ts3 = CantonTimestamp.tryFromInstant(Instant.parse("2026-01-04T00:00:00Z"))

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
      val acsCommitted = new AcsSnapshotBulkStorage(
        "AcsSnapshotBulkStorageCommitted",
        acsCommittedWriter,
        acsCommittedProgress,
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
        // Create full dummy ACS snapshots for first two timestamps
        stagingConnection
          .createObject(
            s"${bulkStorageTestConfig.getSegmentFolder(ts1, None)}/ACS_0.zstd",
            "dummy acs snapshot 1".getBytes,
          )
          .futureValue
        acsStagingProgress
          .persistLatestProcessedSnapshotTimestamp(TimestampWithMigrationId(ts1, 0))
          .futureValue
        stagingConnection
          .createObject(
            s"${bulkStorageTestConfig.getSegmentFolder(ts2, None)}/ACS_0.zstd",
            "dummy acs snapshot 2 (object 1)".getBytes,
          )
          .futureValue
        stagingConnection
          .createObject(
            s"${bulkStorageTestConfig.getSegmentFolder(ts2, None)}/ACS_1.zstd",
            "dummy acs snapshot 2 (object 2)".getBytes,
          )
          .futureValue
        acsStagingProgress
          .persistLatestProcessedSnapshotTimestamp(TimestampWithMigrationId(ts2, 0))
          .futureValue
        // Create a third snapshot with two objects, but do not mark it as processed yet
        // (simulating that the writer from DB is still not done with it)
        stagingConnection
          .createObject(
            s"${bulkStorageTestConfig.getSegmentFolder(ts3, None)}/ACS_0.zstd",
            "dummy acs snapshot 3 (object 1)".getBytes,
          )
          .futureValue
        stagingConnection
          .createObject(
            s"${bulkStorageTestConfig.getSegmentFolder(ts3, None)}/ACS_1.zstd",
            "dummy acs snapshot 3 (object 2)".getBytes,
          )
          .futureValue

        eventually() {
          acsCommittedProgress.readLatestProcessedSnapshotTimestamp.futureValue.map(
            _.timestamp
          ) shouldBe Some(ts2)
        }
        reader
          .getCommittedObjectsForAcsSnapshotAtOrBefore(ts1)
          .futureValue
          .objects
          .map(_.key) should contain theSameElementsAs
          Seq(s"${bulkStorageTestConfig.getSegmentFolder(ts1, None)}/ACS_0.zstd")
        reader
          .getCommittedObjectsForAcsSnapshotAtOrBefore(ts2)
          .futureValue
          .objects
          .map(_.key) should contain theSameElementsAs
          Seq(
            s"${bulkStorageTestConfig.getSegmentFolder(ts2, None)}/ACS_0.zstd",
            s"${bulkStorageTestConfig.getSegmentFolder(ts2, None)}/ACS_1.zstd",
          )
        reader
          .getStagingObjectsForAcsSnapshotAt(ts1)
          .failed
          .futureValue
          .asInstanceOf[StatusRuntimeException]
          .getStatus
          .getCode shouldBe io.grpc.Status.Code.NOT_FOUND
        reader
          .getStagingObjectsForAcsSnapshotAt(ts2)
          .failed
          .futureValue
          .asInstanceOf[StatusRuntimeException]
          .getStatus
          .getCode shouldBe io.grpc.Status.Code.NOT_FOUND
        reader.getStagingObjectsForAcsSnapshotAt(ts3).futureValue.objects should not be empty

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.DEBUG))(
          {},
          logEntries => {
            forAtLeast(1, logEntries)(entry =>
              entry.message should include(
                s"Latest snapshot in staging bulk storage is at $ts2, which is not after the requested timestamp $ts2"
              )
            )
          },
        )

        acsStagingProgress
          .persistLatestProcessedSnapshotTimestamp(TimestampWithMigrationId(ts3, 0))
          .futureValue
        eventually() {
          acsCommittedProgress.readLatestProcessedSnapshotTimestamp.futureValue.map(
            _.timestamp
          ) shouldBe Some(ts3)
        }
        reader
          .getCommittedObjectsForAcsSnapshotAtOrBefore(ts1)
          .futureValue
          .objects
          .map(_.key) should contain theSameElementsAs
          Seq(
            s"${bulkStorageTestConfig.getSegmentFolder(ts3, None)}/ACS_0.zstd",
            s"${bulkStorageTestConfig.getSegmentFolder(ts3, None)}/ACS_1.zstd",
          )
        reader
          .getStagingObjectsForAcsSnapshotAt(ts3)
          .failed
          .futureValue
          .asInstanceOf[StatusRuntimeException]
          .getStatus
          .getCode shouldBe io.grpc.Status.Code.NOT_FOUND

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
