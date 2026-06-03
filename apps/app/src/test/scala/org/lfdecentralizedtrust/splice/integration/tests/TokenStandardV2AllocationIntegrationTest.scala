package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocationv2 as amuletallocationV2Codegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv1,
  allocationinstructionv2,
  allocationrequestv2,
  allocationv1,
  allocationv2,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingappv2
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.http.v0.definitions.AllocationInstructionResultOutput.members.AllocationInstructionResultCompleted
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardV2AllocationIntegrationTest.{
  AllocatedOtcTrade,
  CreateAllocationRequestResult,
}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
}
import org.lfdecentralizedtrust.splice.codegen.java.da

import scala.jdk.CollectionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class TokenStandardV2AllocationIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with TokenStandardV2TestUtil
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardV2TestDarPath)
        }
      })
  }

  val emptyExtraArgs = new metadatav1.ExtraArgs(
    emptyChoiceContext,
    emptyMetadata,
  )
  // although holding fees are not applied anymore, they are still in the checkBalance assertion
  // TODO (#4094): remove this
  val holdingFeesBound = (BigDecimal(0.0), BigDecimal(1.0))
  val tapAmount = walletUsdToAmulet(1000.0)
  val aliceTransferAmount = walletUsdToAmulet(100.0)
  val bobTransferAmount = walletUsdToAmulet(20.0)

  "Settle a DvP using allocations" in { implicit env =>
    val AllocatedOtcTrade(
      venueParty,
      aliceParty,
      bobParty,
      aliceAllocationRequest,
      aliceAllocationId,
      bobAllocationRequest,
      bobAllocationId,
      bobTradeAgreement,
      otcTrade,
    ) = setupAllocatedOtcTrade()
    val allocations = Seq(aliceAllocationId, bobAllocationId)
    // equivalent to mkOtcTradeSettlementInfo in Daml
    val settlementInfo = new allocationv2.SettlementInfo(
      java.util.List.of(venueParty.toProtoPrimitive),
      "OTCTrade",
      java.util.Optional.of(new metadatav1.AnyContract.ContractId(otcTrade.id.contractId)),
      emptyMetadata,
    )
    val settleBatch = new allocationv2.SettlementFactory_SettleBatch(
      settlementInfo,
      transferLegsFromTrade(otcTrade).asJava,
      allocations
        .map(cid =>
          new allocationv2.FinalizedAllocation(
            cid,
            java.util.List.of(),
            java.util.Optional.empty[java.util.Map[String, java.math.BigDecimal]](),
          )
        )
        .asJava,
      /*actors = */ java.util.List.of(venueParty.toProtoPrimitive),
      emptyExtraArgs,
    )
    val settlementFactoryWithDisclosures =
      sv1ScanBackend.getSettlementFactoryV2(
        settleBatch
      )

    val bobMissingAllocationArgs = new allocationinstructionv2.AllocationFactory_Allocate(
      bobAllocationRequest.payload.settlement,
      bobAllocationRequest.payload.allocations.asScala
        .map(allocation =>
          new allocationv2.AllocationSpecification(
            allocation.admin,
            basicAccount(bobParty),
            java.util.List.of(
              allocation.transferLegSides.asScala
                .find(_.side == allocationv2.TransferSide.RECEIVERSIDE)
                .valueOrFail("Failed to find side where bob is receiver")
            ),
            allocation.settlementDeadline,
            allocation.nextIterationFunding,
            allocation.committed,
            allocation.meta,
          )
        )
        .loneElement,
      bobAllocationRequest.payload.requestedAt,
      java.util.List.of(),
      emptyExtraArgs,
      java.util.List.of(bobParty.toProtoPrimitive),
    )
    val allocationFactory = sv1ScanBackend.getAllocationFactoryV2(bobMissingAllocationArgs)
    val otcTradeSettleArgs = new tradingappv2.OTCTrade_Settle(
      Map[String, tradingappv2.SettlementBatch](
        dsoParty.toProtoPrimitive -> new tradingappv2.settlementbatch.SettlementBatchV2(
          allocations.asJava,
          /*missingAllocations=*/ java.util.List.of(
            new tradingappv2.MissingAllocation(
              java.util.Optional.of(bobTradeAgreement),
              allocationFactory.factoryId,
              allocationFactory.args,
            )
          ),
          settlementFactoryWithDisclosures.factoryId,
          settlementFactoryWithDisclosures.args.extraArgs,
        )
      ).asJava,
      List(
        new tradingappv2.OTCTradeAllocationRequest.ContractId(
          aliceAllocationRequest.contractId.contractId
        ),
        new tradingappv2.OTCTradeAllocationRequest.ContractId(
          bobAllocationRequest.contractId.contractId
        ),
      ).asJava,
    )

    actAndCheck(
      "Venue settles the trade", {
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(venueParty),
            commands = otcTrade.id
              .exerciseOTCTrade_Settle(otcTradeSettleArgs)
              .commands()
              .asScala
              .toSeq,
            disclosedContracts =
              settlementFactoryWithDisclosures.disclosedContracts ++ allocationFactory.disclosedContracts,
          )
      },
    )(
      "Alice and Bob's balance reflect the trade",
      _ => {
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(tradingappv2.OTCTrade.COMPANION)(
            venueParty
          ) shouldBe empty withClue "OTCTrade"

        suppressFailedClues(loggerFactory) {
          clue("Check alice's balance") {
            checkBalance(
              aliceWalletClient,
              expectedRound = None,
              expectedUnlockedQtyRange = (
                tapAmount - aliceTransferAmount + bobTransferAmount,
                tapAmount - aliceTransferAmount + bobTransferAmount,
              ),
              expectedLockedQtyRange = (0.0, 0.0),
              expectedHoldingFeeRange = holdingFeesBound,
            )
            checkTxHistory(
              aliceWalletClient,
              Seq(
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(aliceParty.toProtoPrimitive, bobTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(bobParty.toProtoPrimitive, -bobTransferAmount)
                  )
                },
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(bobParty.toProtoPrimitive, aliceTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(aliceParty.toProtoPrimitive, -aliceTransferAmount)
                  )
                },
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                },
              ),
              ignore = {
                case transfer: TransferTxLogEntry =>
                  inside(transfer) { _ =>
                    // ignore merges
                    transfer.receivers.isEmpty && transfer.sender.value.party == aliceParty.toProtoPrimitive
                  }
                case _ => false
              },
            )
          }
          clue("Check bob's balance") {
            checkBalance(
              bobWalletClient,
              expectedRound = None,
              expectedUnlockedQtyRange = (
                tapAmount + aliceTransferAmount - bobTransferAmount,
                tapAmount + aliceTransferAmount - bobTransferAmount,
              ),
              expectedLockedQtyRange = (0.0, 0.0),
              expectedHoldingFeeRange = holdingFeesBound,
            )
            checkTxHistory(
              bobWalletClient,
              Seq(
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(aliceParty.toProtoPrimitive, bobTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(bobParty.toProtoPrimitive, -bobTransferAmount)
                  )
                },
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(bobParty.toProtoPrimitive, aliceTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(aliceParty.toProtoPrimitive, -aliceTransferAmount)
                  )
                },
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                },
              ),
              ignore = {
                case transfer: TransferTxLogEntry =>
                  inside(transfer) { _ =>
                    // ignore merges
                    transfer.receivers.isEmpty && transfer.sender.value.party == bobParty.toProtoPrimitive
                  }
                case _ => false
              },
            )
          }
        }
      },
    )
  }

  private def setupAllocatedOtcTrade()(implicit env: SpliceTestConsoleEnvironment) = {
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    // We use the same venue party for bob so we can setup the TradeSettlementAgreement
    val venueParty = bobValidatorBackend.getValidatorPartyId()

    // Setup funds for alice and bob
    actAndCheck("Setup funds for Alice", aliceWalletClient.tap(walletAmuletToUsd(tapAmount)))(
      "Alice's balance",
      _ =>
        checkBalance(
          aliceWalletClient,
          expectedRound = None,
          expectedUnlockedQtyRange = (tapAmount - 1.0, tapAmount + 1.0),
          expectedLockedQtyRange = (0.0, 0.0),
          expectedHoldingFeeRange = holdingFeesBound,
        ),
    )
    actAndCheck("Setup funds for Bob", bobWalletClient.tap(walletAmuletToUsd(tapAmount)))(
      "Bob's balance",
      _ =>
        checkBalance(
          bobWalletClient,
          expectedRound = None,
          expectedUnlockedQtyRange = (tapAmount - 1.0, tapAmount + 1.0),
          expectedLockedQtyRange = (0.0, 0.0),
          expectedHoldingFeeRange = holdingFeesBound,
        ),
    )

    val CreateAllocationRequestResult(
      otcTrade,
      aliceAllocationRequest,
      bobAllocationRequest,
    ) =
      createAllocationRequestV2ViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      )

    def allocateV2(
        walletClient: WalletAppClientReference,
        allocationRequestView: allocationrequestv2.AllocationRequestView,
    ) = {
      val allocateResponse = clue(s"${walletClient.name} accepts the Allocation Request") {
        val requestedAllocation = allocationRequestView.allocations.asScala.loneElement
        walletClient.allocateAmulet(
          allocationRequestView.settlement,
          requestedAllocation,
        )
      }
      allocateResponse.output match {
        case AllocationInstructionResultCompleted(completed) =>
          new allocationv2.Allocation.ContractId(completed.allocationCid)
        case _ =>
          fail(s"Allocation for ${walletClient.name} was not completed: $allocateResponse")
      }
    }

    def allocateV1(
        walletClient: WalletAppClientReference,
        venue: PartyId,
        allocationRequest: Contract[
          allocationrequestv2.AllocationRequest.ContractId,
          allocationrequestv2.AllocationRequestView,
        ],
    ) = {
      clue(s"${walletClient.name} accepts the Allocation Request") {
        val requestedAllocation = allocationRequest.payload.allocations.asScala.loneElement
        val sender = walletClient.userStatus().party
        val settlementDeadline =
          requestedAllocation.settlementDeadline.get() // guaranteed to be there
        val transferLegSide = requestedAllocation.transferLegSides.asScala
          .filter(side =>
            side.otherside.owner
              .get() != sender && side.side == allocationv2.TransferSide.SENDERSIDE
          )
          .loneElement

        val allocateResponse = walletClient.allocateAmulet(
          new allocationv1.AllocationSpecification(
            new allocationv1.SettlementInfo(
              venue.toProtoPrimitive,
              new allocationv1.Reference(
                allocationRequest.payload.settlement.id,
                allocationRequest.payload.settlement.cid,
              ),
              allocationRequest.payload.requestedAt,
              allocationRequest.payload.settleAt.orElse(settlementDeadline),
              settlementDeadline,
              allocationRequest.payload.meta,
            ),
            transferLegSide.transferLegId,
            new allocationv1.TransferLeg(
              sender,
              transferLegSide.otherside.owner.get(),
              transferLegSide.amount,
              new holdingv1.InstrumentId(requestedAllocation.admin, transferLegSide.instrumentId),
              transferLegSide.meta,
            ),
          )
        )

        allocateResponse.output match {
          case AllocationInstructionResultCompleted(completed) =>
            new allocationv2.Allocation.ContractId(completed.allocationCid)
          case _ =>
            fail(s"Allocation for ${walletClient.name} was not completed: $allocateResponse")
        }
      }
    }

    // Pretend that alice uses a v2 wallet and bob a v1 wallet. The trade should still go through.
    // For that to work, Bob needs to create a TradeSettlementAgreement
    val aliceAllocation = allocateV2(aliceWalletClient, aliceAllocationRequest.contract.payload)
    val bobTradeAgreement =
      bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = bobWalletClient.config.ledgerApiUser,
          actAs = Seq(venueParty, bobParty),
          readAs = Seq(bobParty),
          update = new tradingappv2.TradeSettlementAgreement(
            venueParty.toProtoPrimitive,
            bobParty.toProtoPrimitive,
          ).create(),
        )
    val bobAllocation =
      allocateV1(bobWalletClient, venueParty, bobAllocationRequest.contract)

    AllocatedOtcTrade(
      venueParty,
      aliceParty,
      bobParty,
      aliceAllocationRequest.contract,
      aliceAllocation,
      bobAllocationRequest.contract,
      bobAllocation,
      bobTradeAgreement.contractId,
      otcTrade,
    )
  }

  "Cancel a DvP and its allocations" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    actAndCheck(
      "Settlement venue cancels the trade", {
        val aliceContext = clue("Get choice context for alice's allocation") {
          val scanResponse =
            sv1ScanBackend.getAllocationV2CancelContext(allocatedOtcTrade.aliceAllocationId)
          aliceValidatorBackend.scanProxy.getAllocationV2CancelContext(
            allocatedOtcTrade.aliceAllocationId
          ) shouldBe scanResponse
          scanResponse
        }
        val bobContext = clue("Get choice context for bob's allocation") {
          sv1ScanBackend.getAllocationV2CancelContext(allocatedOtcTrade.bobAllocationId)
        }

        def mkExtraArg(context: ChoiceContextWithDisclosures) =
          new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)

        val cancelChoice = new tradingappv2.OTCTrade_Cancel(
          java.util.List.of(),
          java.util.List.of(
            new da.types.Tuple2(allocatedOtcTrade.aliceAllocationId, mkExtraArg(aliceContext)),
            new da.types.Tuple2(allocatedOtcTrade.bobAllocationId, mkExtraArg(bobContext)),
          ),
          List(
            allocatedOtcTrade.aliceAllocationRequest.contractId,
            allocatedOtcTrade.bobAllocationRequest.contractId,
          ).map(cid => new tradingappv2.OTCTradeAllocationRequest.ContractId(cid.contractId)).asJava,
        )

        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(allocatedOtcTrade.venueParty),
            commands = allocatedOtcTrade.otcTrade.id
              .exerciseOTCTrade_Cancel(
                cancelChoice
              )
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = aliceContext.disclosedContracts ++ bobContext.disclosedContracts,
          )
      },
    )(
      "Allocations are archived",
      _ => {
        aliceWalletClient
          .listAmuletAllocations() shouldBe empty withClue "Alice's AmuletAllocations"
        bobWalletClient.listAmuletAllocations() shouldBe empty withClue "Bob's AmuletAllocations"
        bobValidatorBackend.participantClient.ledger_api.state.acs.of_party(
          party = allocatedOtcTrade.venueParty,
          filterInterfaces = Seq(allocationv2.Allocation.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
        ) shouldBe empty withClue "Allocations"
      },
    )
  }

  "Withdraw an allocation" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    // sanity check
    aliceWalletClient.listAmuletAllocations() should have size (1) withClue "AmuletAllocations"
    actAndCheck(
      "Settlement venue withdraw the trade", {
        aliceWalletClient.withdrawAmuletAllocationV2(
          new amuletallocationV2Codegen.AmuletAllocationV2.ContractId(
            allocatedOtcTrade.aliceAllocationId.contractId
          )
        )
      },
    )(
      "Allocation is archived",
      _ => aliceWalletClient.listAmuletAllocations() shouldBe empty withClue "AmuletAllocations",
    )
  }

  "Reject an allocation request" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    // sanity checks
    aliceWalletClient
      .listAllocationRequests() should have size (1) withClue "alice AllocationRequests"

    actAndCheck(
      "Alice rejects the allocation request", {
        aliceWalletClient.rejectAllocationRequestV2(
          allocatedOtcTrade.aliceAllocationRequest.contractId
        )
      },
    )(
      "Allocation request is archived",
      _ => {
        val aliceRequests = aliceWalletClient.listAllocationRequests()
        aliceRequests shouldBe empty withClue "alice Requests"
      },
    )
  }
}

object TokenStandardV2AllocationIntegrationTest {
  final case class AllocatedOtcTrade(
      venueParty: PartyId,
      aliceParty: PartyId,
      bobParty: PartyId,
      aliceAllocationRequest: Contract[
        allocationrequestv2.AllocationRequest.ContractId,
        allocationrequestv2.AllocationRequestView,
      ],
      aliceAllocationId: allocationv2.Allocation.ContractId,
      bobAllocationRequest: Contract[
        allocationrequestv2.AllocationRequest.ContractId,
        allocationrequestv2.AllocationRequestView,
      ],
      bobAllocationId: allocationv2.Allocation.ContractId,
      bobTradeAgreement: tradingappv2.TradeSettlementAgreement.ContractId,
      otcTrade: tradingappv2.OTCTrade.Contract,
  )

  case class CreateAllocationRequestResult(
      trade: tradingappv2.OTCTrade.Contract,
      aliceRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest,
      bobRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest,
  )
}
