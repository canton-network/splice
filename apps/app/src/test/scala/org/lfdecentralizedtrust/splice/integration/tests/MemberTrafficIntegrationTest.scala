package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_RegisterSynchronizer
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_RegisterSynchronizer
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  SynchronizerFeesTestUtil,
  TokenStandardMetadata,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry.Http.BuyTrafficRequestStatus
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{Member, PartyId, SynchronizerId, UniqueIdentifier}
import org.scalatest.Tag
import org.slf4j.event.Level

import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters.*

class MemberTrafficIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with SynchronizerFeesTestUtil
    with WalletTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // NOTE: automatic top-ups should be explicitly disabled for this test as currently written
      .withTrafficTopupsDisabled
  }

  "SV automation" should {

    "handle contracts with an invalid member id" in { implicit env =>
      val now = env.environment.clock.now
      val decentralizedSynchronizerConfig =
        sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer
      val trafficAmount =
        Math.max(decentralizedSynchronizerConfig.fees.minTopupAmount.toLong, 1_000_000L)
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100)

      def purchaseTrafficForMember(memberId: String, trackingId: String): Unit = {
        clue(s"Purchase traffic for member ${memberId}") {
          createBuyTrafficRequest(
            aliceValidatorBackend,
            aliceParty,
            memberId,
            trafficAmount,
            trackingId,
          )
        }
        clue("Traffic request is ingested into wallet tx log") {
          // we use eventually succeeds to ensure error 404s are not logged
          eventuallySucceeds() {
            aliceWalletClient.getTrafficRequestStatus(trackingId)
          }
        }
        clue("Wallet automation completes the request") {
          eventually() {
            inside(aliceWalletClient.getTrafficRequestStatus(trackingId)) {
              case d0.GetBuyTrafficRequestStatusResponse.members.BuyTrafficRequestCompletedResponse(
                    d0.BuyTrafficRequestCompletedResponse(status, _)
                  ) =>
                status shouldBe BuyTrafficRequestStatus.Completed
            }
          }
        }
      }

      purchaseTrafficForMember("bad", "bad")

      // The "bad" contract should be ignored and not blow up the ACS ingestion pipeline or
      // the triggers that consume MemberTraffic contracts.
      // To test this, we try to create another MemberTraffic contract and see it succeed.

      purchaseTrafficForMember("PAR::validator::dummy", "good")

    }

    "merge duplicate member traffic contracts" in { implicit env =>
      val now = env.environment.clock.now
      val domainFeesConfig = sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer.fees
      val trafficAmount = Math.max(domainFeesConfig.minTopupAmount.toLong, 1_000_000L)
      val participantId = aliceValidatorBackend.participantClient.id
      val trafficContractsMergeThreshold =
        sv1Backend.getDsoInfo().dsoRules.payload.config.numMemberTrafficContractsThreshold

      clue("No member traffic contracts for aliceValidator exist initially") {
        listMemberTrafficContracts(participantId) isEmpty
      }

      actAndCheck(
        "Buy traffic for aliceValidator 5 times",
        (1L to trafficContractsMergeThreshold).foreach(_ =>
          buyMemberTraffic(aliceValidatorBackend, trafficAmount, now)
        ),
      )(
        "5 new MemberTraffic contracts get created",
        _ =>
          listMemberTrafficContracts(participantId).foreach(traffic => {
            traffic.data.memberId shouldBe participantId.toProtoPrimitive
            traffic.data.totalPurchased shouldBe trafficAmount
          }),
      )
      clue("Buying traffic once more triggers the merging of contracts") {
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          buyMemberTraffic(aliceValidatorBackend, trafficAmount, now),
          lines => {
            forExactly(1, lines)(
              _.message should include regex (s"Merged .* member traffic contracts for member $participantId")
            )
          },
        )
        eventually() {
          inside(listMemberTrafficContracts(participantId)) { case Seq(mergedTraffic) =>
            mergedTraffic.data.memberId shouldBe participantId.toProtoPrimitive
            mergedTraffic.data.totalPurchased shouldBe 6 * trafficAmount
          }
        }
      }

    }

    "serve a paymaster's registration in Scan and merge its traffic contracts" taggedAs Tag(
      "org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_32"
    ) in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val synchronizerId =
        SynchronizerId(UniqueIdentifier.tryCreate("dedicated", activeSynchronizerId.uid.namespace))
      val trafficContractsMergeThreshold =
        sv1Backend.getDsoInfo().dsoRules.payload.config.numMemberTrafficContractsThreshold
      val minimumTrafficAmount = Math.max(
        sv1ScanBackend
          .getAmuletConfigAsOf(env.environment.clock.now)
          .decentralizedSynchronizer
          .fees
          .minTopupAmount
          .toLong,
        1_000_000L,
      )
      val (_, minimumCostAmulet) = computeSynchronizerFees(minimumTrafficAmount)
      val spentAmuletAmount = minimumCostAmulet * 2
      val purchasedTrafficAmount = computeTrafficForAmuletAmount(spentAmuletAmount)
      // a paymaster's purchase names no member and is at migration id 0
      val memo = s"${TokenStandardMetadata.trafficPurchaseMemoPrefix}" +
        s"synchronizer-id=${synchronizerId.toProtoPrimitive}&migration-id=0"
      aliceWalletClient.tap(spentAmuletAmount * (trafficContractsMergeThreshold + 1) + 100)

      actAndCheck(
        "SV1 registers a dedicated synchronizer with Alice as its paymaster",
        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          new ARC_DsoRules(
            new SRARC_RegisterSynchronizer(
              new DsoRules_RegisterSynchronizer(
                synchronizerId.toProtoPrimitive,
                aliceParty.toProtoPrimitive,
                java.math.BigDecimal.ONE,
                java.math.BigDecimal.ONE,
              )
            )
          ),
          "url",
          "Register a dedicated synchronizer",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        ),
      )(
        "Scan serves the registration in Alice's transfer factory",
        // Scan's store ingests the registration asynchronously, and the purchases need it there.
        _ => transferFactoryHasRegistration(aliceParty, memo) shouldBe true,
      )

      clue("Scan serves the registration to any sender, as the Daml code checks the paymaster") {
        transferFactoryHasRegistration(
          aliceValidatorBackend.getValidatorPartyId(),
          memo,
        ) shouldBe true
      }

      def buyTrafficAsPaymaster(viaV2: Boolean): Unit = {
        val receiver = PartyId.tryFromProtoPrimitive(TokenStandardMetadata.trafficPurchaseReceiver)
        val expiresAt = CantonTimestamp.now().plusSeconds(3600L)
        val trackingId = UUID.randomUUID().toString
        val result =
          if (viaV2)
            aliceWalletClient.createTokenStandardTransferV2(
              receiver,
              spentAmuletAmount,
              memo,
              expiresAt,
              trackingId,
            )
          else
            aliceWalletClient.createTokenStandardTransfer(
              receiver,
              spentAmuletAmount,
              memo,
              expiresAt,
              trackingId,
            )
        inside(result.output) { case members.TransferInstructionCompleted(_) => () }
      }

      actAndCheck(
        s"Alice buys traffic for the dedicated synchronizer $trafficContractsMergeThreshold times, " +
          "alternating V1 and V2 transfers",
        (1L to trafficContractsMergeThreshold).foreach(n =>
          buyTrafficAsPaymaster(viaV2 = n % 2 == 0)
        ),
      )(
        s"$trafficContractsMergeThreshold paymaster MemberTraffic contracts get created",
        _ => {
          val traffics = listPaymasterTrafficContracts(synchronizerId)
          traffics should have size trafficContractsMergeThreshold
          forAll(traffics)(_.data.totalPurchased shouldBe purchasedTrafficAmount)
        },
      )
      clue("Buying traffic once more triggers the merging of the paymaster's contracts") {
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          buyTrafficAsPaymaster(viaV2 = true),
          lines => {
            forExactly(1, lines)(
              _.message should include regex (
                s"Merged .* member traffic contracts for the paymaster purchases for synchronizer $synchronizerId"
              )
            )
          },
        )
        eventually() {
          inside(listPaymasterTrafficContracts(synchronizerId)) { case Seq(mergedTraffic) =>
            mergedTraffic.data.memberId shouldBe ""
            mergedTraffic.data.migrationId shouldBe 0L
            mergedTraffic.data.totalPurchased shouldBe
              (trafficContractsMergeThreshold + 1) * purchasedTrafficAmount
          }
        }
      }
    }
  }

  "Scan" should {
    "serve a member's traffic status as reported by the sequencer" in { implicit env =>
      val memberId = aliceValidatorBackend.participantClient.id

      val actualTotalPurchasedAsPerDso =
        listMemberTrafficContracts(memberId).map(_.data.totalPurchased.toLong).sum

      val statusAsPerScan = eventually() {
        val actualStateAsPerSequencer =
          getTrafficState(aliceValidatorBackend, activeSynchronizerId)
        val statusAsPerScan =
          sv1ScanBackend.getMemberTrafficStatus(activeSynchronizerId, memberId)
        statusAsPerScan.actual.totalConsumed shouldBe actualStateAsPerSequencer.extraTrafficConsumed.value
        statusAsPerScan.actual.totalLimit shouldBe actualStateAsPerSequencer.extraTrafficPurchased.value
        statusAsPerScan
      }
      statusAsPerScan.target.totalPurchased shouldBe actualTotalPurchasedAsPerDso
    }
  }

  private def listMemberTrafficContracts(
      memberId: Member
  )(implicit env: SpliceTestConsoleEnvironment) = {
    sv1Backend.participantClient.ledger_api_extensions.acs.filterJava(MemberTraffic.COMPANION)(
      sv1Backend.getDsoInfo().dsoParty,
      _.data.memberId == memberId.toProtoPrimitive,
    )
  }

  /** Whether Scan's V1 transfer factory for a traffic purchase with the given memo includes a
    * registration in the choice context.
    */
  private def transferFactoryHasRegistration(sender: PartyId, memo: String)(implicit
      env: SpliceTestConsoleEnvironment
  ): Boolean = {
    val now = env.environment.clock.now.toInstant
    val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
      dsoParty.toProtoPrimitive,
      new transferinstructionv1.Transfer(
        sender.toProtoPrimitive,
        TokenStandardMetadata.trafficPurchaseReceiver,
        BigDecimal(10).bigDecimal,
        new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        now,
        now.plus(Duration.ofMinutes(10)),
        // The holdings are irrelevant for the choice context served by scan.
        Seq.empty[holdingv1.Holding.ContractId].asJava,
        new metadatav1.Metadata(Map(TokenStandardMetadata.reasonMetaKey -> memo).asJava),
      ),
      ChoiceContextWithDisclosures.emptyExtraArgs,
    )
    val (factory, _) = sv1ScanBackend.getTransferFactory(choiceArgs)
    factory.args.extraArgs.context.values.asScala
      .contains(TokenStandardMetadata.registeredSynchronizerContextKey)
  }

  private def listPaymasterTrafficContracts(
      synchronizerId: SynchronizerId
  )(implicit env: SpliceTestConsoleEnvironment) = {
    sv1Backend.participantClient.ledger_api_extensions.acs.filterJava(MemberTraffic.COMPANION)(
      sv1Backend.getDsoInfo().dsoParty,
      traffic =>
        traffic.data.memberId.isEmpty && traffic.data.synchronizerId == synchronizerId.toProtoPrimitive,
    )
  }
}
