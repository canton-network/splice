package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.VettedPackage
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags, ParticipantId, PartyId}
import com.digitalasset.daml.lf.data.Ref.PackageId
import java.time.Duration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.rewardassignmentv1.{
  RewardBeneficiary,
  RewardCoupon,
  RewardCoupon_AssignBeneficiaries,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources, RetryFor}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  TimeTestUtil,
  TriggerTestUtil,
  UploadablePackage,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.AcceptedTransferOfferTrigger

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// Tests the following
// - ProcessRewardsTrigger handles providers with wrong vetting state
// - UnhideRewardCouponV2Trigger does unhide when vetting state is correct
// - ExpireRewardCouponV2Trigger does expiry correctly in both vetting state scenarios
// Also include a test for an additional scenario where the reward assignment is
// done while the beneficiary is offline (but has already vetted).
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil {

  private val v2AmuletVersion = DarResources.amulet_0_1_19.metadata.version

  private val previousAmuletPackageId =
    DarResources.amulet.others
      .filter(_.metadata.version < v2AmuletVersion)
      .maxBy(_.metadata.version)
      .packageId

  private val v2CapableAmuletPackageIds: Seq[String] =
    DarResources.amulet.all
      .filter(_.metadata.version >= v2AmuletVersion)
      .map(_.packageId)
      .distinct

  // Set of packages alice must not vet to have wrong vetting state for v2 coupons
  private val v2CapableDarsUnvettedOnAlice: Seq[DarResource] = {
    val v2CapableAmuletIds = v2CapableAmuletPackageIds.toSet
    Seq(
      DarResources.amulet,
      DarResources.amuletNameService,
      DarResources.dsoGovernance,
      DarResources.wallet,
      DarResources.walletPayments,
    ).flatMap(_.all)
      .filter(d =>
        v2CapableAmuletIds.contains(d.packageId) ||
          d.dependencyPackageIds.exists(v2CapableAmuletIds.contains)
      )
      .distinctBy(d => (d.metadata.name, d.metadata.version))
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withNoVettedPackages(implicit env => Seq(aliceValidatorBackend.participantClient))
      .addConfigTransforms((_, config) => {
        val aliceValidator = InstanceName.tryCreate("aliceValidator")
        config.copy(
          validatorApps = config.validatorApps +
            (aliceValidator -> config
              .validatorApps(aliceValidator)
              .copy(
                additionalPackagesToUnvet = v2CapableDarsUnvettedOnAlice
                  .groupBy(_.metadata.name)
                  .map { case (name, resources) =>
                    name -> resources.map(_.metadata.version).toSet
                  }
              ))
        )
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.withRewardConfig(
          InitialRewardConfig(
            mintingVersion = "RewardVersion_TrafficBasedAppRewards",
            dryRunVersion = None,
            appRewardCouponThreshold = BigDecimal("0"),
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[AcceptedTransferOfferTrigger]
        )(config)
      )
      .withoutAutomaticRewardsCollectionAndAmuletMerging

  "Unhide and expire of RewardCouponV2" in { implicit env =>
    val aliceParticipantId =
      aliceValidatorBackend.appState.participantAdminConnection.getParticipantId().futureValue
    assertAliceVettedBelowV2(aliceParticipantId)

    val (aliceParty, bobParty) = onboardAliceAndBobWithFeaturedRights()

    def doTransfer(): Unit = {
      val offerCid = bobWalletClient.createTransferOffer(
        aliceParty,
        BigDecimal(10.0),
        "activity",
        getLedgerTime.plus(Duration.ofMinutes(1)),
        s"transfer-${scala.util.Random.nextInt()}",
      )
      aliceWalletClient.acceptTransferOffer(offerCid)
    }

    def aliceCoupons =
      sv1Backend.appState.dsoStore
        .listRewardCouponsV2()
        .futureValue
        .filter(_.payload.provider == aliceParty.toProtoPrimitive)

    def bobUnassignedCoupons =
      sv1Backend.appState.dsoStore
        .listRewardCouponsV2()
        .futureValue
        .filter(c =>
          c.payload.provider == bobParty.toProtoPrimitive && c.payload.beneficiary.isEmpty
        )

    // TODO: (#5624) Fix the bootstrap of network such that processing of rounds
    // before the first activity by a featured-app is ingested, and use advanceRoundsToNextRoundOpening
    for (round <- 1 to 3) {
      advanceTimeAndWaitForRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    advanceTimeAndWaitForRoundOpening
    assertOldestOpenRound(4)
    // FA right now effective from round 4
    doTransfer()
    advanceRoundsToNextRoundOpening

    clue(
      "RewardCouponV2 can be expired both when provider is an observer and not an observer"
    ) {
      clue(
        "ProcessRewardsTrigger creates Alice's RewardCouponV2 hidden because she is unvetted, Bob is an observer"
      ) {
        eventually() {
          aliceCoupons.filterNot(_.payload.providerIsObserver) should not be empty
          aliceCoupons.filter(_.payload.providerIsObserver) shouldBe empty
          bobUnassignedCoupons.filter(_.payload.providerIsObserver) should not be empty
          bobUnassignedCoupons.filterNot(_.payload.providerIsObserver) shouldBe empty
        }
      }

      actAndCheck(
        "Advance past the coupon TTL while Alice is unvetted",
        advanceTime(Duration.ofHours(37)),
      )(
        "ExpireRewardCouponV2Trigger archives Alice's hidden and Bob's coupons while she is unvetted",
        _ => sv1Backend.appState.dsoStore.listRewardCouponsV2().futureValue shouldBe empty,
      )
    }

    clue(
      "coupons are unhid after vetting"
    ) {
      actAndCheck()(
        "Generate new Alice+Bob activity while Alice is still unvetted", {
          doTransfer()
          advanceRoundsToNextRoundOpening
        },
      )(
        "ProcessRewardsTrigger again creates Alice's RewardCouponV2 hidden, Bob's as observer",
        _ => {
          aliceCoupons.filterNot(_.payload.providerIsObserver) should not be empty
          bobUnassignedCoupons should not be empty
        },
      )

      uploadAndRevetV2DarsOnAlice(aliceParticipantId)

      clue("UnhideRewardCouponV2Trigger unhides Alice's coupons once she is re-vetted") {
        eventually() {
          val coupons = aliceCoupons
          coupons should not be empty
          coupons.filterNot(_.payload.providerIsObserver) shouldBe empty
        }
      }
    }

    clue(
      "RewardCouponV2 can be assigned after vetting, even when beneficiary is offline"
    ) {
      clue("Take Alice's validator and participant offline") {
        aliceValidatorBackend.stop()
        aliceValidatorBackend.participantClient.synchronizers.disconnect_all()
        aliceValidatorBackend.participantClient.synchronizers.is_connected(
          decentralizedSynchronizerId
        ) shouldBe false
      }

      actAndCheck(
        "Bob assigns half of his reward coupons to Alice", {
          val cids = bobUnassignedCoupons.map(_.contractId.toInterface(RewardCoupon.INTERFACE))
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              Seq(bobParty),
              commands = cids.head
                .exerciseRewardCoupon_AssignBeneficiaries(
                  new RewardCoupon_AssignBeneficiaries(
                    cids.tail.asJava,
                    Seq(
                      new RewardBeneficiary(
                        aliceParty.toProtoPrimitive,
                        new java.math.BigDecimal("0.5"),
                      ),
                      new RewardBeneficiary(
                        bobParty.toProtoPrimitive,
                        new java.math.BigDecimal("0.5"),
                      ),
                    ).asJava,
                    ChoiceContextWithDisclosures.emptyExtraArgs,
                  )
                )
                .commands()
                .asScala
                .toSeq,
            )
        },
      )(
        "Alice-beneficiary coupons with Bob as provider exist on the DSO store",
        _ => {
          val beneficiaryCoupons = sv1Backend.appState.dsoStore
            .listRewardCouponsV2()
            .futureValue
            .filter(_.payload.beneficiary.toScala.contains(aliceParty.toProtoPrimitive))
          beneficiaryCoupons should not be empty
          beneficiaryCoupons.foreach { c =>
            c.payload.provider shouldBe bobParty.toProtoPrimitive
            c.payload.providerIsObserver shouldBe true
          }
        },
      )

      clue("Reconnect Alice's participant") {
        aliceValidatorBackend.participantClient.synchronizers.reconnect_all()
      }
    }

    clue(
      "ExpireRewardCouponV2Trigger handles coupons with unvetted state and also expire assigned coupons"
    ) {
      val aliceObservedCoupons = sv1Backend.appState.dsoStore
        .listRewardCouponsV2()
        .futureValue
        .filter(c =>
          c.payload.provider == aliceParty.toProtoPrimitive ||
            c.payload.beneficiary.toScala.contains(aliceParty.toProtoPrimitive)
        )
        .map(_.contractId.contractId)
        .toSet
      aliceObservedCoupons should not be empty

      unvetV2AmuletOnAlice(aliceParticipantId)

      actAndCheck(
        "Advance past the coupon TTL",
        advanceTime(Duration.ofHours(37)),
      )(
        "ExpireRewardCouponV2Trigger ignores Alice coupons",
        _ => {
          val remaining = sv1Backend.appState.dsoStore
            .listRewardCouponsV2()
            .futureValue
            .map(_.contractId.contractId)
            .toSet
          aliceObservedCoupons.subsetOf(remaining) shouldBe true
        },
      )

      actAndCheck(
        "Re-vet Alice",
        revetV2AmuletOnAlice(aliceParticipantId),
      )(
        "ExpireRewardCouponV2Trigger archives once Alice is re-vetted",
        _ => sv1Backend.appState.dsoStore.listRewardCouponsV2().futureValue shouldBe empty,
      )
    }
  }

  private def aliceVettedPackagesOnSv1View(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Seq[String] =
    sv1ValidatorBackend.appState.participantAdminConnection
      .listVettedPackages(aliceParticipantId, decentralizedSynchronizerId, AuthorizedState)
      .futureValue
      .flatMap(_.mapping.packages.map(_.packageId))

  private def assertAliceVettedBelowV2(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    clue("Alice's validator vets the highest amulet below the V2 but none at/above it") {
      eventually() {
        val vetted = aliceVettedPackagesOnSv1View(aliceParticipantId)
        vetted should contain(previousAmuletPackageId)
        vetted.intersect(v2CapableAmuletPackageIds) shouldBe empty
      }
    }

  private def onboardAliceAndBobWithFeaturedRights()(implicit
      env: SpliceTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

    bobWalletClient.tap(100)
    grantFeaturedAppRight(bobWalletClient)

    // Alice can't self-grant while unvetted, so use dso directly
    actAndCheck(
      "DSO directly creates Alice's FeaturedAppRight",
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new FeaturedAppRight(
            dsoParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
          ).create,
        ),
    )(
      "Alice's featured app right is visible in scan",
      _ => sv1ScanBackend.lookupFeaturedAppRight(aliceParty) should not be empty,
    )

    (aliceParty, bobParty)
  }

  private def uploadAndRevetV2DarsOnAlice(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    actAndCheck(
      "Upload and re-vet the latest packages on Alice's participant", {
        val aliceAdminConnection = aliceValidatorBackend.appState.participantAdminConnection
        aliceAdminConnection
          .uploadDarFiles(
            v2CapableDarsUnvettedOnAlice.map(UploadablePackage.fromResource),
            RetryFor.Automation,
          )
          .futureValue
        aliceAdminConnection
          .vetDars(decentralizedSynchronizerId, v2CapableDarsUnvettedOnAlice, None, None)
          .futureValue
      },
    )(
      "sv1's participant observes Alice has the correct vetting state for RewardAccountingV2",
      _ =>
        aliceVettedPackagesOnSv1View(aliceParticipantId) should contain(
          DarResources.amulet.latest.packageId
        ),
    )

  private def unvetV2AmuletOnAlice(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    actAndCheck(
      "Unvet the V2-capable amulet versions on Alice",
      aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
        aliceParticipantId,
        removes = v2CapableAmuletPackageIds.map(PackageId.assertFromString),
        force = ForceFlags(ForceFlag.AllowUnvettedDependencies),
        store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
      ),
    )(
      "sv1's participant observes Alice is in the wrong vetting state",
      _ =>
        aliceVettedPackagesOnSv1View(aliceParticipantId)
          .intersect(v2CapableAmuletPackageIds) shouldBe empty,
    )

  private def revetV2AmuletOnAlice(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    actAndCheck(
      "Re-vet the V2-capable amulet versions on Alice",
      aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
        aliceParticipantId,
        adds = v2CapableAmuletPackageIds.map(id =>
          VettedPackage(PackageId.assertFromString(id), None, None)
        ),
        store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
      ),
    )(
      "sv1's participant observes Alice has the correct vetting state again",
      _ =>
        v2CapableAmuletPackageIds.toSet
          .subsetOf(aliceVettedPackagesOnSv1View(aliceParticipantId).toSet) shouldBe true,
    )

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

}
