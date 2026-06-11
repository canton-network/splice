package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.metrics.MetricValue
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import java.time.Duration
import java.util.Optional
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.cryptohash.Hash
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  RewardConfig,
  RewardVersion,
  USD,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics.MetricsPrefix
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.GetRewardAccountingBatchResponse
import definitions.GetRewardAccountingRootHashResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.{
  CalculateRewardsDryRunTrigger,
  CalculateRewardsTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ProcessRewardsTrigger
import org.lfdecentralizedtrust.splice.scan.automation.RewardComputationTrigger
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}

import scala.concurrent.duration.DurationInt
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

// This test focuses on the SV app side triggers testing
// - Turning on/off of dry-run and minting-version in rewardConfig
//   And confirming that rewards processing works.
//
// Later this test would be extended to cover unhide, expire, etc
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class TrafficBasedRewardsSvAppTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with AmuletConfigUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        ConfigTransforms.withRewardConfig(
          InitialRewardConfig(
            dryRunVersion = None,
            appRewardCouponThreshold = BigDecimal("0"),
          )
        )(config)
      )
      // Prevent wallets from minting RewardCouponV2 before the test
      // can observe them on the DSO store.
      .withoutAutomaticRewardsCollectionAndAmuletMerging

  "Enable, disable of dryRunVersion/mintingVersion take effect at round closure" in {
    implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      aliceWalletClient.tap(20000)

      grantFeaturedAppRight(aliceWalletClient)
      grantFeaturedAppRight(bobWalletClient)

      for (round <- 1 to 3) {
        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(round.toLong)
      }

      // oldest=3: rounds 3,4,5 open.
      // Next round to open is R6, it will have dryRun enabled
      clue("vote to enable dryRunVersion") {
        changeRewardConfig(enableDryRun = true)
      }

      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(4)
      doTransfer(bobParty)

      // oldest=4: rounds 4,5,6 open.
      // R7 will have the disabled config.
      clue("vote to disable dryRunVersion") {
        changeRewardConfig(enableDryRun = false)
      }

      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(5)

      // oldest=5: rounds 5,6,7 open. R8 will have
      // both dryRunVersion and mintingVersion set.
      clue("vote to enable dryRunVersion + mintingVersion") {
        changeRewardConfig(enableDryRun = true, enableMinting = true)
      }

      val svBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
      val calculateRewardsDryRunTriggers =
        svBackends.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])
      val calculateRewardsTriggers =
        svBackends.map(_.dsoAutomation.trigger[CalculateRewardsTrigger])

      // Create activity for 6, 7, and 8 and confirm creation of CalculateRewardsV2
      setTriggersWithin(
        triggersToPauseAtStart = calculateRewardsDryRunTriggers ++ calculateRewardsTriggers
      ) {
        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(6)
        doTransfer(bobParty)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(7)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(8)
        doTransfer(bobParty)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(9)

        clue("CalculateRewardsV2 are created for rounds, 6 and 8") {
          eventually() {
            val v2s = sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue
            v2s.map(_.payload.round.number) should contain(6L)
            v2s.map(_.payload.round.number) should not contain 7L
            v2s
              .filter(_.payload.round.number == 8L)
              .map(_.payload.dryRun)
              .toSet shouldBe Set(true, false)
          }
        }
      }

      clue("Alice and Bob have minting allowances for R6") {
        eventually() {
          val hash = inside(sv1ScanBackend.getRewardAccountingRootHash(6L)) {
            case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
              h.rootHash
          }
          val providers = walkBatch(6L, hash).map(_.provider)
          providers should contain(aliceParty.toProtoPrimitive)
          providers should contain(bobParty.toProtoPrimitive)
        }
      }

      clue("Alice and Bob have minting allowances for R8") {
        eventually() {
          val hash = inside(sv1ScanBackend.getRewardAccountingRootHash(8L)) {
            case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
              h.rootHash
          }
          val providers = walkBatch(8L, hash).map(_.provider)
          providers should contain(aliceParty.toProtoPrimitive)
          providers should contain(bobParty.toProtoPrimitive)
        }
      }

      clue("All CalculateRewardsV2 and ProcessRewardsV2 contracts consumed") {
        eventually() {
          sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue shouldBe empty
          sv1Backend.appState.dsoStore.listProcessRewardsV2().futureValue shouldBe empty
        }
      }

      clue("Alice and Bob received RewardCouponV2 for R8") {
        eventually() {
          val coupons = sv1Backend.appState.dsoStore.listRewardCouponsV2().futureValue
          coupons.filter(c =>
            c.payload.round.number == 8L && c.payload.provider == aliceParty.toProtoPrimitive
          ) should not be empty
          coupons.filter(c =>
            c.payload.round.number == 8L && c.payload.provider == bobParty.toProtoPrimitive
          ) should not be empty
        }
      }

      confirmBftRead(bobParty)
  }

  // Here we confirm that sv2 can do BFT read of root-hash and batch from sv1 and sv4 only
  // And the rewards processing works even when sv3 is offline.
  private def confirmBftRead(
      bobParty: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue("Stop sv3 to confirm we can perform bft read from sv1, sv4 only") {
      sv3Backend.stop()
      sv3ScanBackend.stop()
    }

    // sv2 will process all ProcessRewardsV2
    val otherProcessRewardsTriggers =
      Seq(sv1Backend, sv4Backend).map(_.dsoDelegateBasedAutomation.trigger[ProcessRewardsTrigger])

    otherProcessRewardsTriggers.foreach(_.pause().futureValue)

    try {
      val sv2CalculateRewards = sv2Backend.dsoAutomation.trigger[CalculateRewardsTrigger]

      // Pausing this ensures that the root-hash is not calculated while we advance round
      val sv2RewardComputation = sv2ScanBackend.automation.trigger[RewardComputationTrigger]

      // Here we ensure that SV2 has done ingestion of app-activity for the round just closed
      // But then its AppActivityRecordMetaT is bumped so that it cannot compute the
      // root-hash for the round.
      val (calculateRewardsCid, round) = setTriggersWithin(
        triggersToPauseAtStart = Seq(sv2CalculateRewards, sv2RewardComputation)
      ) {
        val round = oldestOpenRound
        doTransfer(bobParty)
        // Need to advance by two rounds, see note below about last_archived_round
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening

        val (calculateRewardsCid, rootHash) =
          clue(
            s"Round $round just closed: its CalculateRewardsV2 exists and sv1 serves root-hash"
          ) {
            eventually() {
              val calc = sv1Backend.appState.dsoStore
                .listCalculateRewardsV2()
                .futureValue
                .filterNot(_.payload.dryRun)
                .find(_.payload.round.number == round)
                .value
              val rootHash = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
                case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
                  h.rootHash
              }
              (calc.contractId, rootHash)
            }
          }

        clue(s"Only sv1 and sv4 confirm round $round, so it is not yet processed") {
          eventually() {
            val startProcessingAction = new ARC_AmuletRules(
              new CRARC_StartProcessingRewardsV2(
                new AmuletRules_StartProcessingRewardsV2(calculateRewardsCid, new Hash(rootHash))
              )
            )
            sv1Backend.appState.dsoStore
              .listConfirmations(startProcessingAction)
              .futureValue should have size 2
          }
        }

        // This is trying to simulate AppActivityRecordMetaT's userVersion bump
        // albeit in a direct way, to avoid restart of scan app, etc.
        actAndCheck(
          s"Reset sv2's earliest-ingested round to $round", {
            val sv2Db = sv2ScanBackend.appState.storage match {
              case db: DbStorage => db
              case other => fail(s"Expected DbStorage")
            }
            implicit val closeContext: CloseContext = CloseContext(sv2Db)
            // Here the last_archived_round must reach earliest_ingested_round + 1 for the scan
            // to confirm that it CannotProvide for a round.
            // In practice it would mean that SV2 would wait for its scan to
            // ingest verdicts for one full round after the version bump,
            // and only then get to know that its own scan does not have the data.
            sv2Db
              .update_(
                sqlu"""update app_activity_record_meta
                       set earliest_ingested_round = $round,
                           last_archived_round = ${round + 1}""",
                "test.increaseAppActivityMeta_EarliestIngestedRound",
              )
              .futureValueUS
          },
        )(
          s"sv2's own scan now answers CannotProvide for round $round",
          _ =>
            sv2ScanBackend.getRewardAccountingRootHash(round) shouldBe
              a[GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashCannotProvide],
        )

        (calculateRewardsCid, round)
      }

      // setTriggersWithin has resumed sv2's CalculateRewardsTrigger. sv3 is stopped and sv2's own
      // scan CannotProvide, so the deciding 3rd confirmation can only come from sv2 via bft read.
      clue(s"sv2's own scan still answers CannotProvide for round $round") {
        sv2ScanBackend.getRewardAccountingRootHash(round) shouldBe
          a[GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashCannotProvide]
      }

      clue(
        s"sv2 reads round $round from the sv1 and sv4, and supplies the 3rd confirmation vote"
      ) {
        eventually() {
          sv1Backend.appState.dsoStore
            .listCalculateRewardsV2()
            .futureValue
            .map(_.contractId) should not contain calculateRewardsCid
        }
      }

      clue(
        s"sv2 does processing of ProcessRewardsV2"
      ) {
        eventually() {
          sv1Backend.appState.dsoStore
            .listRewardCouponsV2()
            .futureValue
            .map(_.payload.round.number) should contain(round)
          sv1Backend.appState.dsoStore
            .listProcessRewardsV2()
            .futureValue
            .map(_.payload.round.number) should not contain round
        }
      }

      clue(
        "sv2's BFT-read counters incremented, as it obtained both root-hash and batch via BFT read"
      ) {
        // metrics.get can throw before the meter is first marked, so retry.
        eventually(retryOnTestFailuresOnly = false) {
          def bftReads(name: String): Long =
            sv2Backend.metrics
              .get(s"$MetricsPrefix.$name", Map("dryRun" -> "false"))
              .select[MetricValue.LongPoint]
              .value
              .value

          bftReads("calculate_rewards_v2.root_hash_bft_reads") should be >= 1L
          bftReads("process_rewards_v2.batch_bft_reads") should be >= 1L
        }
      }
    } finally {
      otherProcessRewardsTriggers.foreach(_.resume())
      clue("Restart sv3") {
        sv3ScanBackend.start()
        sv3Backend.start()
        sv3Backend.waitForInitialization(
          timeout = NonNegativeDuration.tryFromDuration(120.seconds)
        )
        sv3ScanBackend.waitForInitialization(
          timeout = NonNegativeDuration.tryFromDuration(120.seconds)
        )
      }
    }
  }

  private def doTransfer(
      bobParty: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val offerCid = aliceWalletClient.createTransferOffer(
      bobParty,
      BigDecimal(10.0),
      "activity",
      CantonTimestamp.now().plus(Duration.ofMinutes(1)),
      s"transfer-${scala.util.Random.nextInt()}",
    )
    bobWalletClient.acceptTransferOffer(offerCid)
  }

  private def walkBatch(
      round: Long,
      hash: String,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[definitions.RewardAccountingMintingAllowance] =
    sv1ScanBackend.getRewardAccountingBatch(round, hash).toList.flatMap {
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(b) =>
        b.childHashes.flatMap(h => walkBatch(round, h))
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(b) =>
        b.mintingAllowances.toSeq
    }

  private def oldestOpenRound(implicit env: SpliceTestConsoleEnvironment): Long = {
    val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
    openRounds.map(_.contract.payload.round.number.toLong).min
  }

  private def assertOldestOpenRound(
      expected: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expected") {
      eventually() {
        val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val roundNumbers = openRounds.map(_.contract.payload.round.number.toLong).sorted
        roundNumbers should have size 3
        roundNumbers.head shouldBe expected
      }
    }
  }

  private def changeRewardConfig(
      enableDryRun: Boolean,
      enableMinting: Boolean = false,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existing = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
    val rc = existing.rewardConfig.get()
    val newRc = new RewardConfig(
      if (enableMinting) RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS
      else rc.mintingVersion,
      if (enableDryRun) Optional.of(RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS)
      else Optional.empty[RewardVersion](),
      rc.batchSize,
      rc.rewardCouponTimeToLive,
      rc.appRewardCouponThreshold,
    )
    val newConfig = new AmuletConfig[USD](
      existing.transferConfig,
      existing.issuanceCurve,
      existing.decentralizedSynchronizer,
      existing.tickDuration,
      existing.packageConfig,
      existing.transferPreapprovalFee,
      existing.featuredAppActivityMarkerAmount,
      existing.optDevelopmentFundManager,
      existing.externalPartyConfigStateTickDuration,
      Optional.of(newRc),
    )
    setAmuletConfig(Seq((None, newConfig, existing)))
    eventually() {
      sv1Backend.listVoteRequests() shouldBe empty
    }
  }

}
