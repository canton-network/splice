package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.event.Event
import com.daml.ledger.api.v2.transaction_filter
import com.daml.ledger.javaapi.data.codegen.ValueDecoder
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.TransactionWrapper
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import java.time.Duration
import java.util.{Optional, UUID}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  allocationv1,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppKind
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_MiningRound_StartIssuing
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_GrantFeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.OpenMiningRoundSummary
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_GrantFeaturedAppRight
import org.lfdecentralizedtrust.splice.console.{ScanAppBackendReference, WalletAppClientReference}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingapp
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import definitions.GetRewardAccountingBatchResponse
import definitions.GetRewardAccountingActivityTotalsResponse
import definitions.GetRewardAccountingRootHashResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.{
  CalculateRewardsTrigger,
  CalculateRewardsDryRunTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredAmuletTransferInstructionTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigUtil,
  ChoiceContextWithDisclosures,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random

// Tests the TrafficSummary ingestion and AppActivityRecord creation for each
// event as described in CIP-104
//
// DvP settlement from TokenStandardTest is used here just to confirm distribution of rewards
abstract class TrafficBasedRewardsTimeBasedIntegrationTestBase
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with AmuletConfigUtil
    with ExternallySignedPartyTestUtil
    with TokenStandardTest {

  protected def rewardConfigMode: TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode

  private def dryRunEnabled: Boolean =
    rewardConfigMode == TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.DryRun

  private def mintingTrafficBased: Boolean =
    rewardConfigMode == TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
          splitwellValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardTestDarPath)
        }
      })
      .addConfigTransform((_, config) =>
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
            // Set to the CIP-104 defaults to exercise the daml plumbing end-to-end.
            extraFeaturedAssetActivityWeight = Some(BigDecimal("0.2")),
            maxExtraFeaturedAssetActivityWeightPerAsset = Some(BigDecimal("0.1")),
            dsoAppActivityWeight = Some(BigDecimal("0.1")),
          )
        )(config)
      )
      // Pause background wallet/validator automation so that we can test round with no activity,
      // and even do calcs comparison for known transactions in a round
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
            .withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
      // Pause SV reward collection so that it does not race against
      // advanceTimeAndWaitForRoundOpening in the activity block,
      // which would cause "Skipped N SV rewards" warnings
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
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

    clue(
      "Reward accounting: earliest available round is 0 after bootstrap but totals are not yet computed"
    ) {
      eventually() {
        sv1ScanBackend.getRewardAccountingEarliestAvailableRound() shouldBe Some(0L)
      }
      // Activity totals are still Undetermined at this point because no
      // CalculateRewardsV2 contract exists yet (no round has closed).
      sv1ScanBackend.getRewardAccountingActivityTotals(0L) shouldBe an[
        GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsUndetermined
      ]
    }

    // Settlements are performed with verdict ingestion paused to test
    // catch-up ingestion. The initial 3 bootstrap round advances are done
    // below with verdict ingestion active.
    // The pause of CalculateRewardsTrigger is necessary to confirm contracts
    // were created for each round.
    val calculateRewardsTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsTrigger])
    val calculateRewardsDryRunTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])

    // CalculateRewardsTrigger is paused for the entire test body so
    // that we can confirm CalculateRewardsV2 contracts were created
    // for each round before the triggers consume them.
    val (
      updateId0,
      updateId1,
      updateId3,
      updateId4,
      updateId5,
      updateId6,
      updateId7,
      aliceCreateId,
      svExpireId,
      round10BeginOffset,
    ) =
      setTriggersWithin(triggersToPauseAtStart =
        calculateRewardsTriggers ++ calculateRewardsDryRunTriggers
      ) {
        // 3 initial advances with verdict ingestion active, so that the
        // meta row is created and bootstrap rounds have activity data
        // available.
        for (round <- 1 to 3) {
          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(round.toLong)
        }

        clue("Bootstrap rounds have zero activity on firstSV (no featured apps yet)") {
          assertZeroTotals(sv1ScanBackend, 0L to 2L)
        }

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
        pauseScanVerdictIngestionWithin(sv1ScanBackend) {

          val id0 = settleTrade(aliceParty, bobParty, venueParty)
          grantFeaturedAppRight(splitwellWalletClient)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(4)

          val id1 = settleTrade(aliceParty, bobParty, venueParty)
          grantFeaturedAppRight(aliceWalletClient)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(5)

          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(6)

          val id3 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(7)

          val id4 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          // alice creates an AmuletTransferInstruction which is archived by an SV
          val (aliceCreateId, svExpireId) =
            aliceCreateAndSvExpireInstruction(aliceParty, bobParty)

          // Kind swap: cancel venue's and alice's FAK_Basic FARs, re-grant as
          // FAK_ASSETMOVING and FAK_ASSET respectively. Done here (not just
          // before round 10 opens) so the voting flow has multiple rounds to
          // complete — otherwise the new FARs are not visible at round 10's
          // open time (lookupFeaturedAppPartiesAsOf is as-of round open time).
          swapFeaturedAppRightKind(
            splitwellWalletClient,
            venueParty,
            FeaturedAppKind.FAK_ASSETMOVING,
          )
          swapFeaturedAppRightKind(aliceWalletClient, aliceParty, FeaturedAppKind.FAK_ASSET)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(8)

          // No activity for round 8

          advanceTimeAndWaitForRoundOpening
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

          // Snapshot the SV offset before round-10 opens; later used to walk
          // the AmuletRules_MiningRound_StartIssuing exercises emitted as
          // rounds close.
          val round10BeginOffset =
            sv1Backend.participantClientWithAdminToken.ledger_api.state.end()

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(10)

          // Do five in a round to check nested BatchOfBatches processing
          val id6 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(11)

          val id7 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

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
            // Test the archiveDryRunRewardAccountingContracts admin API
            // by archiving a few early rounds. The remaining rounds are
            // consumed by triggers naturally.
            if (dryRunEnabled) {
              clue("Archive dry-run CalculateRewardsV2 for rounds 0..2 via sv admin API") {
                sv1Backend.archiveDryRunRewardAccountingContracts((0L to 2L).toSeq)
              }
              clue("CalculateRewardsV2 contracts for rounds 0..2 are gone") {
                eventually() {
                  val remaining = sv1Backend.appState.dsoStore
                    .listCalculateRewardsV2()
                    .futureValue
                    .map(_.payload.round.number)
                    .toSet
                  (0L to 2L).foreach { round =>
                    remaining should not contain round withClue
                      s"CalculateRewardsV2 for round $round should be archived"
                  }
                }
              }
            }
          }

          (id0, id1, id3, id4, id5, id6, id7, aliceCreateId, svExpireId, round10BeginOffset)
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
      assertSubmittingAppProvider(event, "updateId0", None)
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
      // CIP-104 FA-submitted path: venue is the submitting app, so the whole
      // burn is attributed to venue alone — alice's FAK_Basic FAR as a
      // confirmer no longer adds her to appActivityRecords. (The
      // submittingAppProvider field is pinned once per FA party: here for
      // venue, below in the aliceCreateId clue for alice.)
      assertAppActivity(event, "updateId3", Set(venueParty), expectedRound = 6)
      assertSubmittingAppProvider(event, "updateId3", Some(venueParty))
    }

    clue("updateId4") {
      val event = fetchEvent(updateId4, "updateId4")
      assertTrafficSummary(event, "updateId4")
      // See updateId3.
      assertAppActivity(event, "updateId4", Set(venueParty), expectedRound = 7)
    }

    clue("Alice-submitted create TransferInstruction has app activity for alice") {
      val event = fetchEvent(aliceCreateId, "aliceCreateId")
      event.verdict shouldBe defined
      assertTrafficSummary(event, "aliceCreateId")
      assertAppActivity(
        event,
        "aliceCreateId",
        Set(aliceParty),
        expectedRound = 7,
      )
      assertSubmittingAppProvider(event, "aliceCreateId", Some(aliceParty))
    }

    clue("SV-submitted expire TransferInstruction creates no app activity for alice") {
      val event = fetchEvent(svExpireId, "svExpireId")
      event.verdict shouldBe defined
      assertTrafficSummary(event, "svExpireId")
      assertNoAppActivity(event, "svExpireId")
    }

    clue("updateId5") {
      val event = fetchEvent(updateId5, "updateId5")
      assertTrafficSummary(event, "updateId5")
      // See updateId3. updateId5 is the venue's settlement. Alice's own
      // allocation submissions are separate updates that this block
      // doesn't fetch: their app activity contributes to assertRewardCalcs,
      // and they generate no asset activity because Alice is FAK_Basic
      // through round 9.
      assertAppActivity(event, "updateId5", Set(venueParty), expectedRound = 9)
    }

    clue("updateId6") {
      val event = fetchEvent(updateId6, "updateId6")
      assertTrafficSummary(event, "updateId6")
      // See updateId3.
      assertAppActivity(event, "updateId6", Set(venueParty), expectedRound = 10)
      // Venue is now FAK_ASSETMOVING and alice is FAK_Asset. Asset-activity
      // records attribute (a) alice directly (as a FAK_Asset confirmer) and
      // (b) DSO's dsoAppActivityWeight contribution — but DSO's share is
      // credited to the submitting app (venue), not to DSO itself.
      assertAssetActivity(event, "updateId6", Set(aliceParty, venueParty))
    }

    clue("updateId7") {
      val event = fetchEvent(updateId7, "updateId7")
      assertTrafficSummary(event, "updateId7")
      // Round 11: venue is still FAK_ASSETMOVING (set at end of round 7);
      // see updateId3 for the FA-submitted attribution rule.
      assertAppActivity(event, "updateId7", Set(venueParty), expectedRound = 11)
      // See updateId6 for the asset-side attribution shape.
      assertAssetActivity(event, "updateId7", Set(aliceParty, venueParty))
    }

    assertRewardCalcs(aliceParty, venueParty)

    clue("Issuing rounds have the expected issuancePerFeaturedAppRewardCoupon") {
      eventually() {
        val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        issuingRounds should not be empty
        issuingRounds.foreach { issuingRound =>
          val round = issuingRound.contract.payload.round.number.toLong
          val issuanceRatio =
            BigDecimal(issuingRound.contract.payload.issuancePerFeaturedAppRewardCoupon)
          if (mintingTrafficBased) {
            val totals = inside(sv1ScanBackend.getRewardAccountingActivityTotals(round)) {
              case GetRewardAccountingActivityTotalsResponse.members
                    .RewardAccountingActivityTotalsOk(t) =>
                t
            }
            issuanceRatio shouldBe BigDecimal(totals.issuancePerFeaturedAppActivity) withClue
              s"Round $round"
          } else {
            issuanceRatio should be > BigDecimal(0) withClue s"Round $round"
          }
        }
      }
    }

    // Opt fields on OpenMiningRoundSummary are only populated under TBAR
    // minting; the dry-run variant intentionally leaves them None.
    if (mintingTrafficBased) {
      // Round 8 is the zero-activity round; round 10 is the main activity round.
      assertOpenMiningRoundSummariesOptFieldsPopulated(round10BeginOffset, Set(8L, 10L))
    } else {
      assertOpenMiningRoundSummariesOptFieldsEmpty(round10BeginOffset)
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
    val totalsByRound: Map[Long, definitions.RewardAccountingActivityTotalsOk] =
      clue("Rounds 0..10 activity totals and root hash are computed") {
        eventually() {
          (0L to 10L).map { round =>
            val totalsOk = inside(sv1ScanBackend.getRewardAccountingActivityTotals(round)) {
              case GetRewardAccountingActivityTotalsResponse.members
                    .RewardAccountingActivityTotalsOk(t) =>
                t
            } withClue s"Round $round should have totals"
            val rootHashOk = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
              case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
                h
            } withClue s"Round $round should have a root hash"
            rootHashOk.rootHash should have length 64 // hex-encoded SHA-256
            round -> totalsOk
          }.toMap
        }
      }

    // Rounds 0..4 have no activity records (totals are zero);
    // rounds 5..10 have activity. Round 11 has not closed yet.
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

      // Rounds 6, 7, 10: each settleTrade produces three submissions — alice
      // and bob allocate (alice earns via FA-submitted path for her own
      // allocation and as the FA confirmer on bob's), then venue settles
      // (venue earns via FA-submitted path).
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

    clue(
      "Round 8 scan totals: TBAR v1.1 reward-accounting fields are zero, except for the unclaimed budget"
    ) {
      val t = totalsByRound(8L)
      t.totalAssetActivityWeight shouldBe 0L
      BigDecimal(t.totalAppRewardFromAppActivity) shouldBe BigDecimal(0)
      BigDecimal(t.totalAppRewardFromAssetActivity) shouldBe BigDecimal(0)
      BigDecimal(t.issuancePerFeaturedAppActivity) shouldBe BigDecimal(0)
      BigDecimal(t.issuancePerFeaturedAssetActivity) shouldBe BigDecimal(0)
      BigDecimal(t.totalAppRewardUnclaimed) should be > BigDecimal(0)
    }

    clue("Round 10 scan totals: TBAR v1.1 reward-accounting fields are all non-zero") {
      val t = totalsByRound(10L)
      t.totalAssetActivityWeight should be > 0L
      BigDecimal(t.totalAppRewardFromAppActivity) should be > BigDecimal(0)
      BigDecimal(t.totalAppRewardFromAssetActivity) should be > BigDecimal(0)
      BigDecimal(t.issuancePerFeaturedAppActivity) should be > BigDecimal(0)
      BigDecimal(t.issuancePerFeaturedAssetActivity) should be > BigDecimal(0)
      // There still is some allowance left-over.
      BigDecimal(t.totalAppRewardUnclaimed) should be > BigDecimal(0)
    }

    // The remaining assertions cover the handling of V2 contracts created on ledger.
    def listProcessRewardsV2Rounds(): Seq[Long] =
      sv1Backend.appState.dsoStore
        .listProcessRewardsV2()
        .futureValue
        .map(_.payload.round.number)

    clue("CalculateRewards and ProcessRewards triggers consume contracts for rounds < 11") {
      eventually(40.seconds) {
        val remainingCalculate = sv1Backend.appState.dsoStore
          .listCalculateRewardsV2()
          .futureValue
          .filter(c => c.payload.round.number < 11L)
        remainingCalculate shouldBe empty withClue
          "CalculateRewardsV2 contracts for rounds < 11 should be consumed"
        val remainingProcess = listProcessRewardsV2Rounds()
          .filter(r => r < 11L)
        remainingProcess shouldBe empty withClue
          "ProcessRewardsV2 contracts for rounds < 11 should be consumed"
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

  /** Alice creates an AmuletTransferInstruction with a short deadline, then we
    * advance past the deadline and have the SV trigger archive it. Returns
    * (aliceCreateId, svExpireId).
    */
  private def aliceCreateAndSvExpireInstruction(
      aliceParty: PartyId,
      bobParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): (String, String) = {
    val participant = aliceValidatorBackend.participantClientWithAdminToken
    val beginOffsetExclusive = participant.ledger_api.state.end()

    val trackingId = s"alice-instr-${UUID.randomUUID()}"
    val expiry = Duration.ofMinutes(5)
    val createResult = aliceWalletClient.createTokenStandardTransfer(
      receiver = bobParty,
      amount = BigDecimal(1.0),
      description = "alice-to-bob transfer instruction",
      expiresAt = getLedgerTime.plus(expiry),
      trackingId = trackingId,
    )
    val instructionCid = inside(createResult.output) {
      case definitions.TransferInstructionResultOutput.members
            .TransferInstructionPending(value) =>
        value.transferInstructionCid
    }

    advanceTime(expiry.plusSeconds(60))

    aliceWalletClient.tap(1)

    // ExpiredAmuletTransferInstructionTrigger is paused by default in test config
    // so we explicitly resume it for the contract to be archived.
    setTriggersWithin(triggersToResumeAtStart =
      Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTransferInstructionTrigger])
    ) {
      eventually() {
        aliceWalletClient.listTokenStandardTransfers() shouldBe empty
      }
    }

    findCreateAndArchiveUpdateIds(instructionCid, beginOffsetExclusive, aliceParty)
  }

  /** Query alice's participant ledger for the create and expire `contractId`
    * between the `beginOffsetExclusive` and the current ledger end.
    */
  private def findCreateAndArchiveUpdateIds(
      contractId: String,
      beginOffsetExclusive: Long,
      party: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): (String, String) = {
    val participant = aliceValidatorBackend.participantClientWithAdminToken
    val ledgerEnd = participant.ledger_api.state.end()

    val txFormat = transaction_filter.TransactionFormat(
      eventFormat = Some(
        transaction_filter.EventFormat(
          filtersByParty = Map(party.toLf -> transaction_filter.Filters(Nil)),
          filtersForAnyParty = None,
          verbose = false,
        )
      ),
      transactionShape = transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
    )

    val updates = participant.ledger_api.updates.updates(
      updateFormat = transaction_filter.UpdateFormat(
        includeTransactions = Some(txFormat),
        includeReassignments = None,
        includeTopologyEvents = None,
      ),
      completeAfter = PositiveInt.MaxValue,
      beginOffsetExclusive = beginOffsetExclusive,
      endOffsetInclusive = Some(ledgerEnd),
    )

    val (createUid, archiveUid) =
      updates.foldLeft((Option.empty[String], Option.empty[String])) {
        case ((cu, au), TransactionWrapper(tx)) =>
          val hasCreate = tx.events.exists(_.event match {
            case Event.Event.Created(c) => c.contractId == contractId
            case _ => false
          })
          val hasArchive = tx.events.exists(_.event match {
            case Event.Event.Exercised(e) => e.contractId == contractId && e.consuming
            case _ => false
          })
          (
            if (cu.isEmpty && hasCreate) Some(tx.updateId) else cu,
            if (au.isEmpty && hasArchive) Some(tx.updateId) else au,
          )
        case (acc, _) => acc
      }

    withClue(s"create updateId for contract $contractId")(createUid shouldBe defined)
    withClue(s"archive updateId for contract $contractId")(archiveUid shouldBe defined)
    (createUid.value, archiveUid.value)
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
        weightSum should be >= (totalTrafficCost - numFeaturedAppParties)
        weightSum should be <= totalTrafficCost
      }
    }
  }

  private def assertSubmittingAppProvider(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
      expected: Option[PartyId],
  ): Unit =
    withClue(s"$cluePrefix submitting app provider") {
      event.submittingAppProvider.map(_.value) shouldBe expected.map(_.toProtoPrimitive)
    }

  private def assertAssetActivity(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
      expectedProviders: Set[PartyId],
  ): Unit = {
    val records = event.assetActivityRecords
      .map(_.value)
      .getOrElse(fail(s"$cluePrefix: event has no assetActivityRecords"))
    withClue(s"$cluePrefix asset activity provider parties") {
      records.map(_.party).toSet shouldBe expectedProviders.map(_.toProtoPrimitive)
    }
    withClue(s"$cluePrefix each asset activity weight should be positive") {
      records.foreach(_.weight should be > 0L)
    }
  }

  private def grantFeaturedAppRightWithKind(
      party: PartyId,
      kind: FeaturedAppKind,
      opsParties: Seq[PartyId] = Seq.empty,
      weight: BigDecimal = BigDecimal(1),
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val opsPartiesArg: java.util.Optional[java.util.List[String]] =
      Option
        .when(opsParties.nonEmpty)(opsParties.map(_.toProtoPrimitive).asJava)
        .toJava
    val action = new ARC_DsoRules(
      new SRARC_GrantFeaturedAppRight(
        new DsoRules_GrantFeaturedAppRight(
          party.toProtoPrimitive,
          java.util.Optional.of(weight.bigDecimal),
          java.util.Optional.of(kind),
          opsPartiesArg,
        )
      )
    )
    votingFlow(action, effectivity = None, accept = true, expiration = Duration.ofSeconds(60))
    clue(s"Wait for $party FA right to be ingested with kind=$kind weight=$weight") {
      eventually() {
        val right = sv1ScanBackend.lookupFeaturedAppRight(party).value
        right.payload.appKind.toScala shouldBe Some(kind)
        right.payload.activityWeight.toScala
          .map(BigDecimal(_))
          .getOrElse(BigDecimal(1)) shouldBe weight
      }
    }
  }

  private def swapFeaturedAppRightKind(
      client: WalletAppClientReference,
      party: PartyId,
      newKind: FeaturedAppKind,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    actAndCheck(
      s"Cancel $party's featured app right",
      retryCommandSubmission(client.cancelFeaturedAppRight()),
    )(
      "Wait for right cancellation to be ingested",
      _ => sv1ScanBackend.lookupFeaturedAppRight(party) shouldBe None,
    )
    grantFeaturedAppRightWithKind(party, newKind)
  }

  private def assertZeroTotals(
      scan: ScanAppBackendReference,
      rounds: Seq[Long],
      timeout: FiniteDuration = 20.seconds,
  ): Unit =
    rounds.foreach { round =>
      eventually(timeout) {
        inside(scan.getRewardAccountingActivityTotals(round)) {
          case GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(t) =>
            t.activityRecordsCount shouldBe 0L withClue
              s"Round $round should have no activity records on ${scan.name}"
            t.totalAppActivityWeight shouldBe 0L withClue
              s"Round $round should have no activity weight on ${scan.name}"
            BigDecimal(t.totalAppRewardMintingAllowance) shouldBe BigDecimal(0) withClue
              s"Round $round should have no minting allowance on ${scan.name}"
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
    val hash = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
      case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
        h.rootHash
    }
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
    waitForAllocationsOnParticipant(
      splitwellValidatorBackend.participantClientWithAdminToken,
      venueParty,
      aliceAllocationId,
      bobAllocationId,
    )

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
        inside(walletClient.listAmuletAllocations()) {
          case (allocationRequest: HttpWalletAppClient.TokenStandard.V1AmuletAllocation) +: Nil =>
            allocationRequest
        }
      },
    )
    new allocationv1.Allocation.ContractId(allocation.contract.contractId.contractId)
  }

  /** Assert every `AmuletRules_MiningRound_StartIssuing` exercise in the window populates its `OpenMiningRoundSummary` opt fields, with `pinnedRounds` also matched against scan totals. */
  private def assertOpenMiningRoundSummariesOptFieldsPopulated(
      beginOffset: Long,
      pinnedRounds: Set[Long],
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(
      "OpenMiningRoundSummary opt fields are populated on AmuletRules_MiningRound_StartIssuing exercises"
    ) {
      val exercises = eventually() {
        val exs = openMiningRoundExercisesSince(beginOffset)
        pinnedRounds.foreach { r =>
          withClue(
            s"expected AmuletRules_MiningRound_StartIssuing exercise for round $r in the window"
          ) {
            exs.exists(_.round == r) shouldBe true
          }
        }
        exs
      }

      withClue("at least one AmuletRules_MiningRound_StartIssuing exercise in the window") {
        exercises should not be empty
      }

      exercises.filterNot(ex => pinnedRounds(ex.round)).foreach { ex =>
        summaryOptFields.foreach { case (name, extractor, _) =>
          withClue(s"round ${ex.round}: $name populated") {
            extractor(ex.summary).toScala shouldBe defined
          }
        }
      }

      pinnedRounds.foreach(r => assertRoundMatchesScan(exercises, r))
    }
  }

  /** Assert `round`'s `OpenMiningRoundSummary` opt fields match scan totals. */
  private def assertRoundMatchesScan(
      exercises: Seq[OpenMiningRoundExercise],
      round: Long,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val summary = exercises
      .find(_.round == round)
      .map(_.summary)
      .getOrElse(
        fail(
          s"expected AmuletRules_MiningRound_StartIssuing exercise for round $round in the window"
        )
      )
    val totals = inside(sv1ScanBackend.getRewardAccountingActivityTotals(round)) {
      case GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsOk(t) =>
        t
    }
    summaryOptFields.foreach { case (name, extractor, scanField) =>
      withClue(s"round $round: $name matches scan total") {
        decimalMatches(extractor(summary), scanField(totals))
      }
    }
  }

  /** Assert every `AmuletRules_MiningRound_StartIssuing` exercise in the window leaves all `OpenMiningRoundSummary` opt fields unset — the dry-run variant's expected state. */
  private def assertOpenMiningRoundSummariesOptFieldsEmpty(
      beginOffset: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(
      "OpenMiningRoundSummary opt fields are unset on AmuletRules_MiningRound_StartIssuing exercises (dry-run)"
    ) {
      val exercises = eventually() {
        val exs = openMiningRoundExercisesSince(beginOffset)
        withClue("at least one AmuletRules_MiningRound_StartIssuing exercise in the window") {
          exs should not be empty
        }
        exs
      }

      exercises.foreach { ex =>
        summaryOptFields.foreach { case (name, extractor, _) =>
          withClue(s"round ${ex.round}: $name unset") {
            extractor(ex.summary).toScala shouldBe empty
          }
        }
      }
    }
  }

  private val summaryOptFields: Seq[
    (
        String,
        OpenMiningRoundSummary => Optional[java.math.BigDecimal],
        definitions.RewardAccountingActivityTotalsOk => String,
    )
  ] = Seq(
    (
      "optIssuancePerFeaturedAppRewardCoupon",
      _.optIssuancePerFeaturedAppRewardCoupon,
      _.issuancePerFeaturedAppActivity,
    ),
    (
      "optIssuancePerFeaturedAssetRewardCoupon",
      _.optIssuancePerFeaturedAssetRewardCoupon,
      _.issuancePerFeaturedAssetActivity,
    ),
    (
      "optTotalFeaturedAssetRewardCoupons",
      _.optTotalFeaturedAssetRewardCoupons,
      _.totalAppRewardFromAssetActivity,
    ),
    (
      "optUnclaimedAppRewards",
      _.optUnclaimedAppRewards,
      _.totalAppRewardUnclaimed,
    ),
  )

  /** A decoded `AmuletRules_MiningRound_StartIssuing` exercise, paired with the
    * round number of the `IssuingMiningRound` contract created in the same
    * transaction. The round number is not carried on `OpenMiningRoundSummary`
    * itself; it has to be lifted from the sibling create event.
    */
  private case class OpenMiningRoundExercise(round: Long, summary: OpenMiningRoundSummary)

  /** Decode every `AmuletRules_MiningRound_StartIssuing` exercise emitted on the
    * SV participant since `beginOffsetExclusive`. Each returned
    * [[OpenMiningRoundExercise]] pairs the exercise's `OpenMiningRoundSummary`
    * with the round number of the `IssuingMiningRound` contract created in the
    * same transaction.
    */
  private def openMiningRoundExercisesSince(
      beginOffsetExclusive: Long
  )(implicit env: SpliceTestConsoleEnvironment): Seq[OpenMiningRoundExercise] = {
    val dsoParty = sv1Backend.getDsoInfo().dsoParty
    val participant = sv1Backend.participantClientWithAdminToken
    val ledgerEnd = participant.ledger_api.state.end()

    val txFormat = transaction_filter.TransactionFormat(
      eventFormat = Some(
        transaction_filter.EventFormat(
          filtersByParty = Map(dsoParty.toLf -> transaction_filter.Filters(Nil)),
          filtersForAnyParty = None,
          verbose = true,
        )
      ),
      transactionShape = transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
    )

    val updates = participant.ledger_api.updates.updates(
      updateFormat = transaction_filter.UpdateFormat(
        includeTransactions = Some(txFormat),
        includeReassignments = None,
        includeTopologyEvents = None,
      ),
      completeAfter = PositiveInt.MaxValue,
      beginOffsetExclusive = beginOffsetExclusive,
      endOffsetInclusive = Some(ledgerEnd),
    )

    updates.toSeq.flatMap {
      case TransactionWrapper(tx) =>
        val summaries: Seq[OpenMiningRoundSummary] = tx.events
          .collect { case event if event.event.isExercised => event.event.exercised }
          .flatten
          .collect {
            case e
                if matchesTemplate(e.templateId, AmuletRules.TEMPLATE_ID) &&
                  e.choice == AmuletRules.CHOICE_AmuletRules_MiningRound_StartIssuing.name =>
              decodeScalapb(
                e.getChoiceArgument,
                AmuletRules_MiningRound_StartIssuing.valueDecoder(),
              ).summary
          }
        val issuingRounds: Seq[Long] = tx.events
          .collect { case event if event.event.isCreated => event.event.created }
          .flatten
          .collect {
            case c if matchesTemplate(c.templateId, IssuingMiningRound.TEMPLATE_ID) =>
              val javaCreated = com.daml.ledger.javaapi.data.CreatedEvent.fromProto(
                com.daml.ledger.api.v2.event.CreatedEvent.toJavaProto(c)
              )
              Contract
                .fromCreatedEvent(IssuingMiningRound.COMPANION)(javaCreated)
                .value
                .payload
                .round
                .number: Long
          }
        if (summaries.isEmpty) Seq.empty
        else {
          withClue(
            s"tx ${tx.updateId}: expected one IssuingMiningRound create per AmuletRules_MiningRound_StartIssuing exercise"
          ) {
            issuingRounds.size shouldBe summaries.size
          }
          summaries.zip(issuingRounds).map { case (s, r) => OpenMiningRoundExercise(r, s) }
        }
      case _ => Seq.empty
    }
  }

  /** Assert that an optional java BigDecimal is defined and numerically equal
    * to the given decimal string at daml Decimal precision (10 fractional
    * digits). Tolerates scale mismatches (e.g. `3.1` vs `3.10`).
    */
  private def decimalMatches(
      actual: Optional[java.math.BigDecimal],
      expected: String,
  ): Unit =
    withClue(s"expected $expected") {
      BigDecimal(actual.toScala.value) should beEqualUpTo(BigDecimal(expected), 10)
    }

  /** Decode a scalapb `Value` (from the ledger-api gRPC client) into a typed
    * Daml-codegen value, routing through the javaapi `Value` representation
    * the codegen's `ValueDecoder` expects.
    */
  private def decodeScalapb[T](
      v: com.daml.ledger.api.v2.value.Value,
      decoder: ValueDecoder[T],
  ): T =
    decoder.decode(
      com.daml.ledger.javaapi.data.Value.fromProto(
        com.daml.ledger.api.v2.value.Value.toJavaProto(v)
      )
    )

  /** True iff `templateId` names the template given by `expected`, ignoring
    * the package id. Compares module and entity names only, since the
    * package id varies between upgrade versions.
    */
  private def matchesTemplate(
      templateId: Option[com.daml.ledger.api.v2.value.Identifier],
      expected: com.daml.ledger.javaapi.data.Identifier,
  ): Boolean =
    templateId.exists(id =>
      id.moduleName == expected.getModuleName && id.entityName == expected.getEntityName
    )
}

object TrafficBasedRewardsTimeBasedIntegrationTestBase {

  sealed trait RewardConfigMode
  object RewardConfigMode {
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

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class TrafficBasedRewardsTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased
}

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class TrafficBasedRewardsDryRunTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.DryRun
}
