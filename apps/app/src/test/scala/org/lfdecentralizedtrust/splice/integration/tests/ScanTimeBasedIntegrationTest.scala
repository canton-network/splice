package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  AppRewardCoupon,
  ValidatorRewardCoupon,
}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsEntry
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfigs.scanStorageConfigV1
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, S3BucketConnectionForTests}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultAnsConfig

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

class ScanTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with AmuletConfigUtil
    with WalletTestUtil
    with TimeTestUtil
    with HasExecutionContext
    with HasS3Mock
    with HasActorSystem {

  val initialRound = 4815L

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges amulets, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialRound = initialRound)
        )(config)
      )
      .withAdditionalSetup { implicit env =>
        // start at a point where the reward trigers can run so that we avoid warnings about missed rewards
        advanceTimeForRewardAutomationToRunForCurrentRound
      }
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs((_, scanConfig) =>
          scanConfig.copy(
            bulkStorage = BulkStorageConfig(
              snapshotPollingInterval = NonNegativeFiniteDuration.ofSeconds(5),
              updatesPollingInterval = NonNegativeFiniteDuration.ofSeconds(5),
              s3 = Some(s3ConfigMock),
            ),
            publicUrl = Some(Uri("http://foo.bar.com")),
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.copy(
            // By default, the acs snapshot trigger processes 30sec of history per invocation,
            // which is too slow for this test which advances time by hours or days.
            acsSnapshotTriggerPollingInterval = Some(NonNegativeFiniteDuration.ofHours(1))
          )
        )(config)
      )

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  def firstRound(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Long =
    sv1ScanBackend.getDsoInfo().initialRound match {
      case None => 0L
      case Some(round) => round.toLong
    }

  "report correct reference data" in { implicit env =>
    def roundNum() =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number
    roundNum() shouldBe firstRound + 1

    advanceRoundsToNextRoundOpening
    roundNum() shouldBe firstRound + 2
  }

  "return correct amulet configs" in { implicit env =>
    // TODO(DACH-NY/canton-network-node#2930) test also with changing amulet prices.
    // TODO(DACH-NY/canton-network-node#2930) Currently we are not guaranteed that the first three rounds are correctly
    // captured in the tx log, so for now we first advance a round, and query only on that
    // round and beyond. Once that is fixed, we should make sure that querying for round 0 is reliable as well.

    advanceRoundsToNextRoundOpening

    clue(s"Get config for round ${firstRound + 3}") {
      val cfg = eventuallySucceeds() {
        sv1ScanBackend.getAmuletConfigForRound(firstRound + 3)
      }
      cfg.amuletCreateFee.bigDecimal.setScale(10) should be(BigDecimal(0).bigDecimal.setScale(10))
      cfg.holdingFee.bigDecimal.setScale(10) should be(
        SpliceUtil.defaultHoldingFee.rate divide walletAmuletPrice setScale 10
      )
      cfg.lockHolderFee.bigDecimal.setScale(10) should be(BigDecimal(0).bigDecimal.setScale(10))
      cfg.transferFee.initial.bigDecimal.setScale(10) should be(
        SpliceUtil.zeroTransferFee.initialRate.setScale(10)
      )
      cfg.transferFee.steps shouldBe empty
    }

    clue(s"Try to get config for round ${firstRound + 4} which does not yet exist") {
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getAmuletConfigForRound(firstRound + 4),
        _.errorMessage should include(s"Round ${firstRound + 4} not found"),
      )
    }

    val newHoldingFee = BigDecimal(0.1)
    clue("schedule a config change, and advance time for it to take effect") {
      val amuletRules = sv1ScanBackend.getAmuletRules().contract
      val configs = Seq(
        (
          Some(Duration.ofSeconds(10)),
          mkUpdatedAmuletConfig(amuletRules, defaultTickDuration, holdingFee = newHoldingFee),
          amuletRules.payload.configSchedule.initialValue,
        )
      )

      setAmuletConfig(configs)
      advanceTime(Duration.ofSeconds(20))
      eventually() {
        BigDecimal(
          sv1ScanBackend
            .getAmuletRules()
            .payload
            .configSchedule
            .initialValue
            .transferConfig
            .holdingFee
            .rate
        ) should be(newHoldingFee)
      }
      advanceRoundsToNextRoundOpening
    }
    clue(s"Round ${firstRound + 4} should now be open, and have the new configuration") {
      eventuallySucceeds() {
        sv1ScanBackend.getAmuletConfigForRound(firstRound + 4).holdingFee should be(
          walletUsdToAmulet(newHoldingFee)
        )
      }
    }
  }

  "return AnsRules contract and config" in { implicit env =>
    val ansRules = sv1ScanBackend.getAnsRules()
    ansRules.payload.config shouldBe defaultAnsConfig()
  }

  def compareAcsV0V1(
      v0: Vector[definitions.CreatedEvent],
      v1: Vector[definitions.ActiveContract],
  ): Unit = {
    v0.zip(v1).foreach { case (c0, c1) =>
      c0.contractId shouldBe c1.contractId
      c0.templateId shouldBe c1.templateId
      c0.packageName shouldBe c1.packageName
      c0.createArguments shouldBe c1.createArguments
      c0.createdAt shouldBe c1.createdAt
      c0.signatories should contain theSameElementsInOrderAs c1.signatories
      c0.observers should contain theSameElementsInOrderAs c1.observers
    }
  }

  "snapshotting" in { implicit env =>
    val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
    val migrationId = sv1ScanBackend.config.domainMigrationId

    clue(
      "Wait for backfilling to complete, as the ACS snapshot trigger is paused until then"
    ) {
      eventually() {
        sv1ScanBackend.automation.updateHistory
          .getBackfillingState()
          .futureValue should be(BackfillingState.Complete)
        advanceTime(sv1ScanBackend.config.automation.pollingInterval.asJava)
      }
    }

    val startTime = getLedgerTime

    advanceTime(
      java.time.Duration
        .ofHours(scanStorageConfigV1.dbAcsSnapshotPeriodHours.toLong)
        .plusSeconds(1L)
    )

    val snapshot1 = eventually() {
      val snapshot1 = sv1ScanBackend.getDateOfMostRecentSnapshotBefore(
        getLedgerTime,
        migrationId,
      )
      snapshot1 should not be None
      snapshot1.value.toInstant shouldBe >(startTime.toInstant)
      snapshot1
    }

    createAnsEntry(
      aliceAnsExternalClient,
      perTestCaseName("snapshot"),
      aliceWalletClient,
      tapAmount = 5000,
    )

    advanceTime(
      java.time.Duration
        .ofHours(scanStorageConfigV1.dbAcsSnapshotPeriodHours.toLong)
        .plusSeconds(1L)
    )

    val snapshotAfter = eventually() {
      val snapshotAfter = sv1ScanBackend.getDateOfMostRecentSnapshotBefore(
        getLedgerTime,
        migrationId,
      )
      snapshot1 should not(be(snapshotAfter))
      snapshotAfter
    }

    sv1ScanBackend.getDateOfFirstSnapshotAfter(startTime, 0).value shouldBe snapshot1.value
    sv1ScanBackend
      .getDateOfFirstSnapshotAfter(CantonTimestamp.tryFromInstant(snapshot1.value.toInstant), 0)
      .value shouldBe snapshotAfter.value

    val snapshotAfterData = sv1ScanBackend.getAcsSnapshotAtV1(
      CantonTimestamp.assertFromInstant(snapshotAfter.value.toInstant),
      migrationId,
      templates = Some(
        Vector(
          PackageQualifiedName.fromJavaCodegenCompanion(Amulet.COMPANION),
          PackageQualifiedName.fromJavaCodegenCompanion(AnsEntry.COMPANION),
        )
      ),
      partyIds = Some(Vector(aliceUserParty)),
    )

    advanceTime(java.time.Duration.ofMinutes(10))

    val atOrBefore = getLedgerTime

    // afOrBefore should return the same ACS snapshot as the exact time given by snapshotAfter
    val snapshotAtOrBeforeAfterData = sv1ScanBackend.getAcsSnapshotAtV1(
      CantonTimestamp.assertFromInstant(atOrBefore.toInstant),
      migrationId,
      recordTimeMatch = Some(definitions.AcsRequest.RecordTimeMatch.AtOrBefore),
      templates = Some(
        Vector(
          PackageQualifiedName.fromJavaCodegenCompanion(Amulet.COMPANION),
          PackageQualifiedName.fromJavaCodegenCompanion(AnsEntry.COMPANION),
        )
      ),
      partyIds = Some(Vector(aliceUserParty)),
    )

    snapshotAfterData shouldBe snapshotAtOrBeforeAfterData
    snapshotAtOrBeforeAfterData.value.recordTime shouldBe snapshotAfter.value

    sv1ScanBackend.getAcsSnapshotAtV1(
      CantonTimestamp.assertFromInstant(atOrBefore.toInstant),
      migrationId,
      recordTimeMatch = Some(definitions.AcsRequest.RecordTimeMatch.Exact),
      templates = Some(
        Vector(
          PackageQualifiedName.fromJavaCodegenCompanion(Amulet.COMPANION),
          PackageQualifiedName.fromJavaCodegenCompanion(AnsEntry.COMPANION),
        )
      ),
      partyIds = Some(Vector(aliceUserParty)),
    ) shouldBe None

    inside(snapshotAfterData) { case Some(data) =>
      val (entries, coins) =
        data.createdEvents.partition(
          _.templateId.contains(QualifiedName(AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID).toString)
        )
      val entry = AnsEntry
        .jsonDecoder()
        .decode(new JsonLfReader(entries.loneElement.createArguments.noSpaces))
      entry.name shouldBe perTestCaseName("snapshot")
      forAll(coins) { createdEvent =>
        Amulet
          .jsonDecoder()
          .decode(new JsonLfReader(createdEvent.createArguments.noSpaces))
          .owner should be(aliceUserParty.toProtoPrimitive)
      }
      val snapshotAfterCts = CantonTimestamp.assertFromInstant(snapshotAfter.value.toInstant)
      val holdingsStateV0 = sv1ScanBackend.getHoldingsStateAt(
        snapshotAfterCts,
        migrationId,
        partyIds = Vector(aliceUserParty),
      )
      val holdingsStateV1 = sv1ScanBackend.getHoldingsStateAtV1(
        snapshotAfterCts,
        migrationId,
        partyIds = Vector(aliceUserParty),
      )
      compareAcsV0V1(holdingsStateV0.value.createdEvents, holdingsStateV1.value.createdEvents)
      inside(holdingsStateV1) { case Some(holdings) =>
        holdings.createdEvents should be(coins)
      }

      val holdingsSummary = sv1ScanBackend.getHoldingsSummaryAt(
        snapshotAfterCts,
        migrationId,
        ownerPartyIds = Vector(aliceUserParty),
      )

      inside(holdingsSummary) { case Some(res) =>
        res.migrationId should be(migrationId)
        res.recordTime should be(snapshotAfter.value)
        res.summaries.map(_.partyId).distinct shouldBe (Vector(aliceUserParty.toProtoPrimitive))
      }

      val holdingsSummaryV1 = sv1ScanBackend.getHoldingsSummaryAtV1(
        snapshotAfterCts,
        migrationId,
        ownerPartyIds = Vector(aliceUserParty),
      )

      inside(holdingsSummaryV1) { case Some(res) =>
        res.migrationId should be(migrationId)
        res.recordTime should be(snapshotAfter.value)
        res.summaries.map(_.partyId).distinct shouldBe (Vector(aliceUserParty.toProtoPrimitive))
        forAll(res.summaries) { summary =>
          // V1 response should contain non-zero coin totals
          BigDecimal(summary.totalCoinHoldings) should be > BigDecimal(0)
          BigDecimal(summary.totalCoinHoldings) shouldBe BigDecimal(
            summary.totalUnlockedCoin
          ) + BigDecimal(summary.totalLockedCoin)
        }
      }

      // V1 coin totals should match V0 coin totals
      inside((holdingsSummary, holdingsSummaryV1)) { case (Some(v0Res), Some(v1Res)) =>
        v0Res.summaries.zip(v1Res.summaries).foreach { case (v0s, v1s) =>
          v0s.totalUnlockedCoin shouldBe v1s.totalUnlockedCoin
          v0s.totalLockedCoin shouldBe v1s.totalLockedCoin
          v0s.totalCoinHoldings shouldBe v1s.totalCoinHoldings
        }
      }

      // V1 multi-party query: querying with both alice and bob exercises
      // multi-party support at the HTTP endpoint level
      val holdingsSummaryV1MultiParty = sv1ScanBackend.getHoldingsSummaryAtV1(
        snapshotAfterCts,
        migrationId,
        ownerPartyIds = Vector(aliceUserParty, bobUserParty),
      )
      inside(holdingsSummaryV1MultiParty) { case Some(res) =>
        // Alice should appear in the multi-party result with the same values
        val aliceSummaries = res.summaries.filter(_.partyId == aliceUserParty.toProtoPrimitive)
        aliceSummaries should not be empty
        inside(holdingsSummaryV1) { case Some(singleRes) =>
          aliceSummaries shouldBe singleRes.summaries
        }
      }

      // afOrBefore should return the same holdingsState and holdingsSummary as the exact time given by snapshotAfter
      advanceTime(java.time.Duration.ofMinutes(10))
      val atOrBefore = getLedgerTime
      val atOrBeforeCts = CantonTimestamp.assertFromInstant(atOrBefore.toInstant)
      val holdingsStateAtOrBefore = sv1ScanBackend.getHoldingsStateAtV1(
        atOrBeforeCts,
        migrationId,
        partyIds = Vector(aliceUserParty),
        recordTimeMatch = Some(definitions.HoldingsStateRequest.RecordTimeMatch.AtOrBefore),
      )
      inside(holdingsStateAtOrBefore) { case Some(holdings) =>
        holdings.createdEvents should be(coins)
        holdings.recordTime shouldBe snapshotAfter.value
      }

      sv1ScanBackend.getHoldingsStateAtV1(
        atOrBeforeCts,
        migrationId,
        partyIds = Vector(aliceUserParty),
        recordTimeMatch = Some(definitions.HoldingsStateRequest.RecordTimeMatch.Exact),
      ) shouldBe None

      val holdingsSummaryAtOrBefore = sv1ScanBackend.getHoldingsSummaryAt(
        atOrBeforeCts,
        migrationId,
        ownerPartyIds = Vector(aliceUserParty),
        recordTimeMatch = Some(definitions.HoldingsSummaryRequest.RecordTimeMatch.AtOrBefore),
      )
      holdingsSummaryAtOrBefore shouldBe holdingsSummary

      sv1ScanBackend.getHoldingsSummaryAt(
        atOrBeforeCts,
        migrationId,
        ownerPartyIds = Vector(aliceUserParty),
        recordTimeMatch = Some(definitions.HoldingsSummaryRequest.RecordTimeMatch.Exact),
      ) shouldBe None

      val holdingsSummaryV1AtOrBefore = sv1ScanBackend.getHoldingsSummaryAtV1(
        atOrBeforeCts,
        migrationId,
        ownerPartyIds = Vector(aliceUserParty),
        recordTimeMatch = Some(definitions.HoldingsSummaryRequestV1.RecordTimeMatch.AtOrBefore),
      )
      holdingsSummaryV1AtOrBefore shouldBe holdingsSummaryV1

      sv1ScanBackend.getHoldingsSummaryAtV1(
        atOrBeforeCts,
        migrationId,
        ownerPartyIds = Vector(aliceUserParty),
        recordTimeMatch = Some(definitions.HoldingsSummaryRequestV1.RecordTimeMatch.Exact),
      ) shouldBe None
    }

    advanceTime(java.time.Duration.ofHours(24))
    val endTime = getLedgerTime
    val lastMidnight = endTime.toInstant.truncatedTo(ChronoUnit.DAYS);
    val nextMidnight = lastMidnight.plus(1, ChronoUnit.DAYS)
    val expectedAcsSnapshotKey = s"$lastMidnight~$nextMidnight/ACS_0.zstd"

    val bucketConnection = new S3BucketConnectionForTests(s3ConfigMock, loggerFactory)
    eventually() {

      // wait for latest ACS snapshots to be created
      sv1ScanBackend
        .getDateOfMostRecentSnapshotBefore(endTime, 0)
        .value
        .toInstant shouldBe >=(lastMidnight)

      val getSnapshotResponse = eventuallySucceeds() {
        val getSnapshotResponse = sv1ScanBackend
          .getBulkAcsSnapshot(CantonTimestamp.assertFromInstant(lastMidnight))
        getSnapshotResponse.recordTime should be(lastMidnight.atOffset(java.time.ZoneOffset.UTC))
        getSnapshotResponse
      }
      val allS3Objs = bucketConnection.listObjects.futureValue.contents().asScala

      // Wait for bulk storage objects to be created
      allS3Objs.map(_.key()) should contain(expectedAcsSnapshotKey)

      // Depending on how the days are split exactly (based on the exact simtime when the test was started),
      // the updates may be in one or two segments, so we only assert that there exists a segment that ends
      // at last midnight
      allS3Objs
        .map(_.key())
        .filter(_.endsWith(s"~$lastMidnight/updates_0.zstd")) should not be empty

      // Compare bulk storage data to hot storage data from scan
      // TODO(#4788): for now, bulk storage still uses v0, so we use that here as well
      val acsAtMidnightFromScan = sv1ScanBackend
        .getAcsSnapshotAtV1(CantonTimestamp.assertFromInstant(lastMidnight), 0)
        .value
        .createdEvents
      val acsObjUrl = getSnapshotResponse.objectRefs.head.url
      acsObjUrl should startWith("http://foo.bar.com/api/scan/v0/history/bulk/download/")
      val acsObjKey = URLDecoder.decode(
        acsObjUrl.stripPrefix("http://foo.bar.com/api/scan/v0/history/bulk/download/"),
        StandardCharsets.UTF_8,
      )
      val out = new ByteArrayOutputStream()
      sv1ScanBackend.bulkStorageDownload(acsObjKey, out).futureValue
      val acsAtMidnightFromS3 = uncompressAndDecode(
        ByteString(out.toByteArray),
        io.circe.parser.decode[definitions.ActiveContract],
      )
      acsAtMidnightFromScan should contain theSameElementsInOrderAs acsAtMidnightFromS3

      def isInTimeRange(u: definitions.UpdateHistoryItemV2) = {
        val recordTime = CompactJsonScanHttpEncodings()
          .httpToLapiUpdate(u)
          .update
          .update
          .recordTime
        recordTime <= CantonTimestamp.assertFromInstant(lastMidnight)
      }

      val updateObjsResponse = sv1ScanBackend.getBulkUpdateHistory(
        startTime,
        CantonTimestamp.assertFromInstant(lastMidnight),
        None,
        100,
      )
      val updateObjsUrls = updateObjsResponse.objectRefs.map(_.url)
      val updateObjsKeys = updateObjsUrls.map(url =>
        URLDecoder.decode(
          url.stripPrefix("http://foo.bar.com/api/scan/v0/history/bulk/download/"),
          StandardCharsets.UTF_8,
        )
      )
      val updatesFromS3 = updateObjsKeys
        .flatMap { key =>
          val out = new ByteArrayOutputStream()
          sv1ScanBackend.bulkStorageDownload(key, out).futureValue
          uncompressAndDecode(
            ByteString(out.toByteArray),
            io.circe.parser.decode[definitions.UpdateHistoryItemV2],
          )
        }
      val updatesFromScan = sv1ScanBackend
        .getUpdateHistory(1000, None, CompactJson)
        .filter(isInTimeRange)
      updatesFromScan should contain theSameElementsAs updatesFromS3

      // Compare acs v0 and v1 endpoints
      val acsV0AtMidnightFromScan = sv1ScanBackend
        .getAcsSnapshotAt(CantonTimestamp.assertFromInstant(lastMidnight), 0)
        .value
        .createdEvents

      compareAcsV0V1(acsV0AtMidnightFromScan, acsAtMidnightFromScan)
    }
  }
}
