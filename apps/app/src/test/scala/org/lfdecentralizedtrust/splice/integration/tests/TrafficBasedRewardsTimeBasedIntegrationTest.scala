package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import java.time.Duration
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  allocationv1,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingapp
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import definitions.GetRewardAccountingBatchResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.{
  CalculateRewardsTrigger,
  CalculateRewardsDryRunTrigger,
}
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

import scala.jdk.CollectionConverters.*
import scala.util.Random

// Tests the TrafficSummary ingestion and AppActivityRecord creation for each
// event as described in CIP-104
//
// DvP settlement from TokenStandardTest is used here just to confirm distribution of rewards
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
abstract class TrafficBasedRewardsTimeBasedIntegrationTestBase
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with ExternallySignedPartyTestUtil
    with TokenStandardTest {

  // We run this test in multiple modes to confirm that various triggers work
  // correctly based on the rewardConfig. Although this is somewhat redundant,
  // it allows us to re-use the same test, and because it is expected that in long
  // term only MintingTrafficBased would be necessary.
  //
  // The NoConfig is a special case; as it is simply testing that no regression
  // happen until the rewardConfig is set to 'Some' value. Once we set the
  // rewardConfig via vote, it will likely never get toggled back to None, and hence
  // this mode could be removed altogether.
  //
  // Similarly OnlyRewardConfig and DryRun could be removed once traffic-based
  // rewards is the default.
  protected def rewardConfigMode: TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode

  private def dryRunEnabled: Boolean =
    rewardConfigMode == TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.DryRun

  private def mintingTrafficBased: Boolean =
    rewardConfigMode == TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased

  private def producesV2Contracts: Boolean = dryRunEnabled || mintingTrafficBased

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          sv2ValidatorBackend,
          sv3ValidatorBackend,
          sv4ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
          splitwellValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardTestDarPath)
        }
      })
      .addConfigTransform((_, config) =>
        rewardConfigMode match {
          case TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.NoConfig => config
          case TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.DryRun |
              TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.OnlyRewardConfig |
              TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased =>
            ConfigTransforms.withRewardConfig(
              InitialRewardConfig(
                mintingVersion =
                  if (mintingTrafficBased)
                    TrafficBasedRewardsTimeBasedIntegrationTestBase.trafficBasedAppRewards
                  else TrafficBasedRewardsTimeBasedIntegrationTestBase.featuredAppMarkers,
                dryRunVersion = Option.when(dryRunEnabled)(
                  TrafficBasedRewardsTimeBasedIntegrationTestBase.trafficBasedAppRewards
                ),
                appRewardCouponThreshold =
                  TrafficBasedRewardsTimeBasedIntegrationTestBase.appRewardCouponThreshold,
              )
            )(config)
        }
      )
      // Pause background wallet/validator automation so that we can test round with no activity,
      // and even do calcs comparison for known transactions in a round
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
            .withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )

  "CIP-104 reward accounting pipeline works" in { implicit env =>
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val venuePartyHint = s"venue-party-${Random.nextInt()}"
    val venueParty = splitwellValidatorBackend.onboardUser(
      splitwellWalletClient.config.ledgerApiUser,
      Some(
        PartyId.tryFromProtoPrimitive(
          s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
        )
      ),
    )

    aliceWalletClient.tap(20000)
    bobWalletClient.tap(20000)

    assertOldestOpenRound(0)

    clue("Reward accounting endpoints return 404 before any data is available") {
      sv1ScanBackend.getRewardAccountingEarliestAvailableRound() shouldBe None
      sv1ScanBackend.getRewardAccountingActivityTotals(0L) shouldBe None
    }

    // Here we perform all settlements with verdict ingestion paused just to
    // confirm that activity record computations does happen properly even when
    // the ingestion is catching up, by reading the Tcs store data for the
    // archived rounds. I.e., pausing is not necessary, it merely improves test coverage.
    // The pause of CalculateRewardsTrigger is necessary to confirm contracts
    // were created for each round.
    val calculateRewardsTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsTrigger])
    val calculateRewardsDryRunTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])

    // Sequence of actions
    //   Open rounds | Action
    //   ------------+--------------------------------------
    //   3, 4        | settle id0, grant venue FAP
    //   4, 5        | settle id1, grant alice FAP
    //   5, 6        | settle id2, cancel venue FAP
    //   6, 7        | settle id3, (total 2 DvP trades)
    //   7, 8        | settle id4, (total 3 DvP trades)
    //   8, 9        | no-activity
    //   9, 10       | settle id5, 1 DvP + 3 direct trades
    //   10, 11      | settle id6, (total 5 DvP trades)
    //   11, 12      | settle id7, (round not closed)
    val (updateId0, updateId1, updateId3, updateId4, updateId5, updateId6, updateId7) =
      pauseScanVerdictIngestionWithin(sv1ScanBackend) {
        setTriggersWithin(triggersToPauseAtStart =
          calculateRewardsTriggers ++ calculateRewardsDryRunTriggers
        ) {

          // 3 initial advances to get open rounds with staggered opensAt
          for (round <- 1 to 3) {
            advanceRoundsToNextRoundOpening
            assertOldestOpenRound(round.toLong)
          }

          val id0 = settleTrade(aliceParty, bobParty, venueParty)
          grantFeaturedAppRight(splitwellWalletClient)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(4)

          val id1 = settleTrade(aliceParty, bobParty, venueParty)
          grantFeaturedAppRight(aliceWalletClient)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(5)

          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(6)

          val id3 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(7)

          val id4 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(8)

          // No activity for round 8

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(9)

          // Do only one DvP; this would not generate enough activity to reward the parties.
          val id5 = settleTrade(aliceParty, bobParty, venueParty)

          // But do additional txs by alice such that only alice receives the rewards
          (1 to 3).foreach { _ =>
            val offerCid = aliceWalletClient.createTransferOffer(
              bobParty,
              BigDecimal(10.0),
              "round-9-alice-only",
              CantonTimestamp.now().plus(Duration.ofMinutes(1)),
              s"round9-transfer-${scala.util.Random.nextInt()}",
            )
            bobWalletClient.acceptTransferOffer(offerCid)
          }

          actAndCheck(
            "Cancel venue's featured app right",
            retryCommandSubmission(splitwellWalletClient.cancelFeaturedAppRight()),
          )(
            "Wait for right cancellation to be ingested",
            _ => sv1ScanBackend.lookupFeaturedAppRight(venueParty) shouldBe None,
          )

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(10)

          // Do five in a round to check nested BatchOfBatches processing
          val id6 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(11)

          val id7 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          if (producesV2Contracts) {
            clue(
              "CalculateRewardsV2 contracts should exist for each round"
            ) {
              eventually() {
                val calculateRewardsRounds =
                  sv1Backend.appState.dsoStore
                    .listCalculateRewardsV2()
                    .futureValue
                    .map(_.payload.round.number)
                    .toSet
                (0L to 10L).foreach { round =>
                  calculateRewardsRounds should contain(round) withClue
                    s"CalculateRewardsV2 should exist for round $round"
                }
              }
            }
          }

          (id0, id1, id3, id4, id5, id6, id7)
        }
      }

    def fetchEvent(updateId: String, label: String): definitions.EventHistoryItem =
      clue(s"Fetch event $label") {
        eventually() {
          sv1ScanBackend
            .getEventById(updateId, Some(CompactJson))
            .getOrElse(fail(s"Expected event for updateId $updateId"))
        }
      }

    clue("updateId0") {
      val event = fetchEvent(updateId0, "updateId0")
      event.update shouldBe defined
      assertTrafficSummary(event, "updateId0")
      assertNoAppActivity(event, "updateId0")
    }

    // We don't see activity for updateId1, even though venue was granted FAP
    // before this event happened, because the oldest open round for updateId1
    // was 4 and the round 4 opened before venue was granted FAP.
    clue("updateId1") {
      val event = fetchEvent(updateId1, "updateId1")
      assertTrafficSummary(event, "updateId1")
      assertNoAppActivity(event, "updateId1")
    }

    clue("updateId3") {
      val event = fetchEvent(updateId3, "updateId3")
      assertTrafficSummary(event, "updateId3")
      assertAppActivity(event, "updateId3", Set(venueParty, aliceParty), expectedRound = 6)
    }

    clue("updateId4") {
      val event = fetchEvent(updateId4, "updateId4")
      assertTrafficSummary(event, "updateId4")
      assertAppActivity(event, "updateId4", Set(aliceParty, venueParty), expectedRound = 7)
    }

    clue("updateId5") {
      val event = fetchEvent(updateId5, "updateId5")
      assertTrafficSummary(event, "updateId5")
      // Round 9: one DvP — venue has activity but will be below the coupon threshold;
      // alice's additional transfers push her above it.
      assertAppActivity(event, "updateId5", Set(aliceParty, venueParty), expectedRound = 9)
    }

    clue("updateId6") {
      val event = fetchEvent(updateId6, "updateId6")
      assertTrafficSummary(event, "updateId6")
      assertAppActivity(event, "updateId6", Set(aliceParty, venueParty), expectedRound = 10)
    }

    clue("updateId7") {
      val event = fetchEvent(updateId7, "updateId7")
      assertTrafficSummary(event, "updateId7")
      // Round 11: venue's FAP was cancelled in round 9, so only alice is a
      // featured-app provider here.
      assertAppActivity(event, "updateId7", Set(aliceParty), expectedRound = 11)
    }

    if (
      rewardConfigMode != TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.NoConfig
    ) {
      assertRewardCalcs(aliceParty, venueParty)
    } else {
      clue("No CalculateRewardsV2 contracts are produced when rewardConfig is unset") {
        sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue shouldBe empty
      }
    }

    // Other misc API tests
    clue("404 for non-existent batch data") {
      sv1ScanBackend.getRewardAccountingBatch(6L, "0" * 64) shouldBe None
    }
  }

  private def assertRewardCalcs(
      aliceParty: PartyId,
      venueParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val allScanBackends =
      Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend)

    clue("Scan computes activity totals through round 10 on all SVs") {
      eventually() {
        allScanBackends.foreach(
          _.getRewardAccountingActivityTotals(10L) shouldBe defined
        )
      }
    }

    val totalsByRound: Map[Long, definitions.GetRewardAccountingActivityTotalsResponse] =
      clue("Rounds 6..10 activity totals and root hash are computed") {
        (6L to 10L).map { round =>
          val totals = sv1ScanBackend.getRewardAccountingActivityTotals(round)
          totals shouldBe defined withClue s"Round $round should have totals"
          totals.value.roundNumber shouldBe round
          val rootHash = sv1ScanBackend.getRewardAccountingRootHash(round)
          rootHash shouldBe defined withClue s"Round $round should have a root hash"
          rootHash.value.roundNumber shouldBe round
          rootHash.value.rootHash should have length 64 // hex-encoded SHA-256
          round -> totals.value
        }.toMap
      }

    // For rounds 0..5 scan cannot do totals calcs/produce a root hash, rounds
    // 0..4 had no activity records; round 5 is excluded as the earliest round
    // with records, and round 11 has not closed yet.
    clue(
      "Minting allowances: rounds 6, 7, 10 reward both parties; round 9 only alice"
    ) {
      def providersFor(round: Long): Set[String] =
        getMintingAllowancesForRound(round).map(_.provider).toSet

      // Log per-round, per-party minting so test failures surface concrete
      // values without needing to re-run with debug.
      (6L to 10L).foreach { round =>
        val amounts = getMintingAllowancesForRound(round)
          .map(a => s"${a.provider.split("::").head}=${a.amount}")
          .mkString(", ")
        logger.info(s"Round $round minting: $amounts")(TraceContext.empty)
      }

      // Rounds 6, 7, 10: both alice and venue did DvP trades.
      Seq(6L, 7L, 10L).foreach { round =>
        providersFor(round) shouldBe Seq(aliceParty, venueParty)
          .map(_.toProtoPrimitive)
          .toSet withClue
          s"Both parties should be rewarded in round $round"
      }

      // Round 8: no trades and background triggers are paused, so there is no
      // activity records at all.
      providersFor(8L) shouldBe Set() withClue
        "Round 8 has no activity so no minting allowances are produced"
      totalsByRound(8L).activityRecordsCount shouldBe 0L withClue
        "Round 8 has no activity records"
      totalsByRound(8L).totalAppActivityWeight shouldBe 0L withClue
        "Round 8 has no activity weight"

      // Round 9: one DvP + alice→bob transfers; venue is below the coupon threshold
      // so only alice receives minting allowances.
      providersFor(9L) shouldBe Set(aliceParty.toProtoPrimitive) withClue
        "Round 9: venue is below reward threshold so only alice should be rewarded"

    }

    // The remaining assertions cover the handling of V2 contracts created on ledger.
    if (producesV2Contracts) {
      def listProcessRewardsV2Rounds(): Seq[Long] =
        sv1Backend.appState.dsoStore
          .listProcessRewardsV2()
          .futureValue
          .map(_.payload.round.number)

      clue("CalculateRewards and ProcessRewards triggers consume middle-round (6..10) contracts") {
        // V2 contracts for rounds 6..10 should be processed by SVs
        eventually() {
          val remainingCalculate = sv1Backend.appState.dsoStore
            .listCalculateRewardsV2()
            .futureValue
            .filter(c => c.payload.round.number >= 6L && c.payload.round.number <= 10L)
          remainingCalculate shouldBe empty withClue
            "Middle-round CalculateRewardsV2 contracts (6..10) should be consumed"
          val remainingProcess = listProcessRewardsV2Rounds()
            .filter(r => r >= 6L && r <= 10L)
          remainingProcess shouldBe empty withClue
            "Middle-round ProcessRewardsV2 contracts (6..10) should be consumed"
        }
      }

      // V2 contracts for rounds 0..5 and 11 remain unprocessed by triggers
      // Which provides us opportunity to test the archive endpoint
      if (dryRunEnabled) {
        clue("archiveDryRunRewardAccountingContracts clears remaining rounds") {
          // Archive them via the sv admin API and confirm both
          // CalculateRewardsV2 and ProcessRewardsV2 are empty afterwards.
          val otherRounds = sv1Backend.appState.dsoStore
            .listCalculateRewardsV2()
            .futureValue
            .map(_.payload.round.number: Long)
            .filterNot(r => r >= 6L && r <= 10L)
            .distinct
          otherRounds should not be empty withClue
            "Expected CalculateRewardsV2 contracts for rounds outside [6..10] before archive"
          actAndCheck(
            "Archive dry-run reward accounting contracts for rounds outside [6..10]",
            sv1Backend.archiveDryRunRewardAccountingContracts(otherRounds),
          )(
            "All dry-run CalculateRewardsV2 and ProcessRewardsV2 contracts should be archived",
            _ => {
              sv1Backend.appState.dsoStore
                .listCalculateRewardsV2()
                .futureValue shouldBe empty withClue
                "All dry-run CalculateRewardsV2 contracts should be archived"
              listProcessRewardsV2Rounds() shouldBe empty withClue
                "All dry-run ProcessRewardsV2 contracts should be archived"
            },
          )
        }
      }
    } else {
      clue("No CalculateRewardsV2 contracts when V2 minting/dry-run is unset") {
        sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue shouldBe empty
      }
    }
  }

  private def assertTrafficSummary(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
  ): Unit = {
    withClue(s"$cluePrefix should have traffic summary") {
      event.trafficSummary shouldBe defined
    }
    event.trafficSummary.foreach { summary =>
      withClue(s"$cluePrefix traffic summary should have positive total cost") {
        summary.totalTrafficCost should be > 0L
      }
      withClue(s"$cluePrefix traffic summary should have envelope costs") {
        summary.envelopeTrafficSummaries should not be empty
        summary.envelopeTrafficSummaries.foreach { env =>
          env.trafficCost should be > 0L
        }
      }
    }
  }

  private def assertNoAppActivity(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
  ): Unit = {
    withClue(s"$cluePrefix should not have app activity") {
      event.appActivityRecords shouldBe None
    }
  }

  private def assertAppActivity(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
      expectedProviders: Set[PartyId],
      expectedRound: Long,
  ): Unit = {
    withClue(s"$cluePrefix should have app activity") {
      event.appActivityRecords shouldBe defined
    }
    val totalTrafficCost = event.trafficSummary.value.totalTrafficCost
    event.appActivityRecords.foreach { activity =>
      withClue(s"$cluePrefix app activity round number") {
        activity.roundNumber shouldBe expectedRound
      }
      withClue(s"$cluePrefix app activity provider parties") {
        activity.records.map(_.party).toSet shouldBe expectedProviders.map(_.toProtoPrimitive)
      }
      withClue(s"$cluePrefix each app activity weight should be positive") {
        activity.records.foreach { r =>
          r.weight should be > 0L
        }
      }
      val weightSum = activity.records.map(_.weight).sum
      val numFeaturedAppParties = expectedProviders.size.toLong
      withClue(
        s"$cluePrefix sum of weights should be within [totalTrafficCost - numFeaturedAppParties, totalTrafficCost]"
      ) {
        weightSum should be > (totalTrafficCost - numFeaturedAppParties)
        weightSum should be <= totalTrafficCost
      }
    }
  }

  private def assertOldestOpenRound(
      expectedOldestRound: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expectedOldestRound") {
      eventually() {
        val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val roundNumbers = openRounds.map(_.contract.payload.round.number.toLong).sorted
        roundNumbers should have size 3
        roundNumbers.head shouldBe expectedOldestRound
      }
    }
  }

  private def getMintingAllowancesForRound(
      round: Long
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[definitions.RewardAccountingMintingAllowance] = {
    val hash = sv1ScanBackend.getRewardAccountingRootHash(round).value.rootHash
    def walk(h: String): Seq[definitions.RewardAccountingMintingAllowance] =
      sv1ScanBackend.getRewardAccountingBatch(round, h).toList.flatMap {
        case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(b) =>
          b.childHashes.flatMap(walk)
        case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(b) =>
          b.mintingAllowances.toSeq
      }
    walk(hash)
  }

  private def settleTrade(
      aliceParty: PartyId,
      bobParty: PartyId,
      venueParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): String = {
    val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
    val aliceTransferAmount = walletUsdToAmulet(100.0)
    val bobTransferAmount = walletUsdToAmulet(20.0)
    val CreateAllocationRequestResult(trade, aliceRequest, bobRequest) =
      createAllocationRequestViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      )

    val aliceAllocationId = createAllocation(aliceWalletClient, aliceRequest, "leg0")
    val bobAllocationId = createAllocation(bobWalletClient, bobRequest, "leg1")

    clue("Wait for allocations to be ingested by SV1") {
      eventuallySucceeds() {
        sv1ScanBackend.getAllocationCancelContext(aliceAllocationId)
        sv1ScanBackend.getAllocationCancelContext(bobAllocationId)
      }
    }

    clue("Settlement venue settles the trade") {
      val aliceContext = sv1ScanBackend.getAllocationTransferContext(aliceAllocationId)
      val bobContext = sv1ScanBackend.getAllocationTransferContext(bobAllocationId)

      def mkExtraArg(context: ChoiceContextWithDisclosures) =
        new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)

      val settlementChoice = new tradingapp.OTCTrade_Settle(
        Map(
          "leg0" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
            aliceAllocationId,
            mkExtraArg(aliceContext),
          ),
          "leg1" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
            bobAllocationId,
            mkExtraArg(bobContext),
          ),
        ).asJava
      )

      val tx =
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(venueParty),
            commands = trade.id
              .exerciseOTCTrade_Settle(settlementChoice)
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = aliceContext.disclosedContracts ++ bobContext.disclosedContracts,
          )
      tx.getUpdateId
    }
  }

  private def createAllocation(
      walletClient: WalletAppClientReference,
      request: allocationrequestv1.AllocationRequestView,
      legId: String,
  ): allocationv1.Allocation.ContractId = {
    val transferLeg = request.transferLegs.get(legId)
    val senderParty = PartyId.tryFromProtoPrimitive(transferLeg.sender)
    import com.digitalasset.canton.util.ShowUtil.*
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    val (_, allocation) = actAndCheck(
      show"Create allocation for leg $legId with sender $senderParty", {
        walletClient.allocateAmulet(
          new allocationv1.AllocationSpecification(
            request.settlement,
            legId,
            transferLeg,
          )
        )
      },
    )(
      show"There exists an allocation from $senderParty",
      _ => {
        val allocations = walletClient.listAmuletAllocations()
        allocations should have size 1 withClue "AmuletAllocations"
        allocations.head
      },
    )
    new allocationv1.Allocation.ContractId(allocation.contractId.contractId)
  }
}

object TrafficBasedRewardsTimeBasedIntegrationTestBase {

  sealed trait RewardConfigMode
  object RewardConfigMode {
    // (AmuletConfig.rewardConfig = None).
    case object NoConfig extends RewardConfigMode
    // RewardConfig with dryRunVersion = None and mintingVersion = FeaturedAppMarkers
    case object OnlyRewardConfig extends RewardConfigMode
    // dryRunVersion = TrafficBased
    case object DryRun extends RewardConfigMode
    // mintingVersion = TrafficBased, dryRunVersion = None
    case object MintingTrafficBased extends RewardConfigMode
  }

  val trafficBasedAppRewards = "RewardVersion_TrafficBasedAppRewards"
  val featuredAppMarkers = "RewardVersion_FeaturedAppMarkers"

  // This threshold has been chosen to keep the venue's app activity below
  // threshold for the round 9. See the test for details.
  //
  // appRewardCouponThreshold is in USD and compared against the minting
  // allowance. For this test amuletPrice = 0.005, trafficPrice = 16.67 USD/MB,
  // and issuancePerCoupon is observed to be ~100, so 30 KB of activity ≈ 50 USD
  // (30/1000 MB × 16.67 × 100).
  val appRewardCouponThreshold = BigDecimal("50")
}

class TrafficBasedRewardsTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased
}

class TrafficBasedRewardsDryRunTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.DryRun
}

class TrafficBasedRewardsOnlyRewardConfigTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.OnlyRewardConfig
}

class TrafficBasedRewardsNoRewardConfigTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.NoConfig
}
