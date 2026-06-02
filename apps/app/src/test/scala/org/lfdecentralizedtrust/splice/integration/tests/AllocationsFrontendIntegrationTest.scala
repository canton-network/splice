package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1.TransferLeg as TransferLegV1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv2
import allocationv2.TransferLeg as TransferLegV2
import com.digitalasset.canton.admin.api.client.data.TemplateId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.Metadata
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SpliceUtil,
  TokenStandardAccount,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient.TokenStandard

import java.time.Instant
import java.util.Optional
import scala.util.Random
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class AllocationsFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TokenStandardTest
    with TokenStandardV2TestUtil {

  private val amuletPrice = 2
  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.toDouble)
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
          splitwellValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardTestDarPath)
          backend.participantClient.upload_dar_unless_exists(tokenStandardV2TestDarPath)
        }
      })

  private def createAllocationV2ViaFrontendForm(sender: PartyId)(implicit
      ev: SpliceTestConsoleEnvironment,
      webDriver: WebDriverType,
  ) = {
    val validatorPartyId = aliceValidatorBackend.getValidatorPartyId()
    val wantedTransferLegs = Seq(
      new TransferLegV2(
        "oneway",
        basicAccount(sender),
        basicAccount(validatorPartyId),
        BigDecimal(12).bigDecimal.setScale(10),
        amuletInstrumentIdName,
        new Metadata(java.util.Map.of("k3", "v3")),
      ),
      new TransferLegV2(
        "waybackbutless",
        basicAccount(validatorPartyId),
        basicAccount(sender),
        BigDecimal(6).bigDecimal.setScale(10),
        amuletInstrumentIdName,
        new Metadata(java.util.Map.of("k3", "v3")),
      ),
    )

    val wantedSettlement =
      new allocationv2.SettlementInfo(
        java.util.List.of(validatorPartyId.toProtoPrimitive),
        "some_reference",
        Optional.empty,
        new Metadata(java.util.Map.of("k1", "v1", "k2", "v2")),
      )

    browseToAllocationsPage()

    actAndCheck(
      "create allocation", {
        textField("create-allocation-settlement-ref-id").underlying
          .sendKeys(wantedSettlement.id)
        eventuallyClickOn(id(s"create-allocation-settlement-executor-0"))
        setAnsField(
          textField(s"create-allocation-settlement-executor-0"),
          validatorPartyId.toProtoPrimitive,
          validatorPartyId.toProtoPrimitive,
        )
        wantedTransferLegs.foreach { _ =>
          eventuallyClickOn(id("add-transfer-leg"))
        }
        wantedTransferLegs.zipWithIndex.foreach { case (transferLeg, index) =>
          textField(s"create-allocation-transfer-leg-id-$index").underlying
            .sendKeys(transferLeg.transferLegId)
          eventuallyClickOn(id(s"create-allocation-transfer-leg-sender-$index"))
          setAnsField(
            textField(s"create-allocation-transfer-leg-sender-$index"),
            TokenStandardAccount.tryGetRegularAccountOwner(transferLeg.sender),
            TokenStandardAccount.tryGetRegularAccountOwner(transferLeg.sender),
          )
          eventuallyClickOn(id(s"create-allocation-transfer-leg-receiver-$index"))
          setAnsField(
            textField(s"create-allocation-transfer-leg-receiver-$index"),
            TokenStandardAccount.tryGetRegularAccountOwner(transferLeg.receiver),
            TokenStandardAccount.tryGetRegularAccountOwner(transferLeg.receiver),
          )
          eventuallyClickOn(id("create-allocation-0-amulet-amount"))
          numberField(s"create-allocation-$index-amulet-amount").value = ""
          numberField(s"create-allocation-$index-amulet-amount").underlying.sendKeys(
            transferLeg.amount.toString
          )
        }

        eventuallyClickOn(id("create-allocation-submit-button"))
      },
    )(
      "the allocation is created",
      _ => {
        val allocation = findAll(className("allocation")).toSeq.loneElement

        checkSettlementInfo(
          allocation,
          wantedSettlement.id,
          wantedSettlement.cid.map(_.contractId).toScala,
          wantedSettlement.executors.asScala.toSeq,
        )

        checkTransferLegsV2(
          allocation,
          wantedTransferLegs,
        )
      },
    )
  }

  "A wallet UI" should {

    "see, accept and withdraw allocation requests v2" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceTransferAmount = BigDecimal(5)

      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobTransferAmount = BigDecimal(6)

      val venuePartyHint = s"venue-party-${Random.nextInt()}"
      val venueParty = splitwellValidatorBackend.onboardUser(
        splitwellWalletClient.config.ledgerApiUser,
        Some(
          PartyId.tryFromProtoPrimitive(
            s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
          )
        ),
      )

      aliceWalletClient.tap(1000)
      bobWalletClient.tap(1000)

      val otcTrade = createAllocationRequestV2ViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      ).trade

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        browseToAllocationsPage()

        clue("check that the allocation request is shown") {
          eventually() {
            val allocationRequest = findAll(className("allocation-request")).toSeq.loneElement

            checkSettlementInfo(
              allocationRequest,
              "OTCTradeProposal", // hardcoded in daml
              Some(otcTrade.id.contractId),
              Seq(venueParty.toProtoPrimitive),
            )

            checkTransferLegsV2(allocationRequest, otcTrade.data.tradeLegs.asScala.map(_.leg).toSeq)

            allocationRequest
          }
        }

        clue("sanity check: alice has no allocations yet") {
          aliceWalletClient
            .listAmuletAllocations() shouldBe empty withClue "alice AmuletAllocations"
        }

        actAndCheck(
          "click on accepting the allocation request", {
            eventuallyClickOn(
              className(s"allocation-request-accept")
            )
          },
        )(
          "the allocation is shown",
          { _ =>
            val allocation = findAll(className("allocation")).toSeq.loneElement

            checkSettlementInfo(
              allocation,
              "OTCTradeProposal", // hardcoded in daml
              Some(otcTrade.id.contractId),
              Seq(venueParty.toProtoPrimitive),
            )

            checkTransferLegsV2(allocation, otcTrade.data.tradeLegs.asScala.map(_.leg).toSeq)

            allocation
          },
        )

        val allocationRequestElement = clue("find the allocation request element") {
          eventually() {
            findAll(className("allocation-request")).toSeq.loneElement
          }
        }

        actAndCheck(
          "click on withdrawing the allocation", {
            val allocationElement = findAll(className("allocation")).toSeq.loneElement
            click on allocationElement
              .findChildElement(className("allocation-withdraw"))
              .valueOrFail("Could not find withdraw button for allocation")
          },
        )(
          "the allocation is not shown anymore",
          _ => {
            findAll(className("allocation")).toSeq shouldBe empty withClue "Allocation Cards"
          },
        )

        actAndCheck(
          "click on rejecting the allocation request", {
            click on allocationRequestElement
              .findChildElement(className("allocation-request-reject"))
              .valueOrFail("Could not find reject button for allocation request")
          },
        )(
          "the allocation request is not shown anymore",
          _ => {
            findAll(
              className("allocation-request")
            ).toSeq shouldBe empty withClue "Allocation Request Cards"
          },
        )
      }
    }

    "see, accept and withdraw allocation requests v1" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceTransferAmount = BigDecimal(5)

      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobTransferAmount = BigDecimal(6)

      val venuePartyHint = s"venue-party-${Random.nextInt()}"
      val venueParty = splitwellValidatorBackend.onboardUser(
        splitwellWalletClient.config.ledgerApiUser,
        Some(
          PartyId.tryFromProtoPrimitive(
            s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
          )
        ),
      )

      aliceWalletClient.tap(1000)
      bobWalletClient.tap(1000)

      val otcTrade = createAllocationRequestViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        browseToAllocationsPage()

        val allocationRequestElement = clue("check that the allocation request is shown") {
          eventually() {
            val allocationRequest = findAll(className("allocation-request")).toSeq.loneElement

            checkSettlementInfo(
              allocationRequest,
              "OTCTradeProposal", // hardcoded in daml
              Some(otcTrade.trade.data.tradeCid.contractId),
              Seq(venueParty.toProtoPrimitive),
            )

            checkTransferLegs(allocationRequest, otcTrade.trade.data.transferLegs.asScala.toMap)

            allocationRequest
          }
        }

        clue("sanity check: alice has no allocations yet") {
          aliceWalletClient
            .listAmuletAllocations() shouldBe empty withClue "alice AmuletAllocations"
        }

        val (_, allocationElement) = actAndCheck(
          "click on accepting the allocation request", {
            val (aliceTransferLegId, _) =
              otcTrade.aliceRequest.transferLegs.asScala
                .find(_._2.sender == aliceParty.toProtoPrimitive)
                .valueOrFail("Couldn't find alice's transfer leg")
            eventuallyClickOn(
              id(s"transfer-leg-${otcTrade.trade.id.contractId}-$aliceTransferLegId-accept")
            )
          },
        )(
          "the allocation is shown",
          { _ =>
            val allocation = findAll(className("allocation")).toSeq.loneElement

            checkSettlementInfo(
              allocation,
              "OTCTradeProposal", // hardcoded in daml
              Some(otcTrade.trade.data.tradeCid.contractId),
              Seq(venueParty.toProtoPrimitive),
            )

            checkTransferLegs(allocation, otcTrade.trade.data.transferLegs.asScala.toMap)

            allocation
          },
        )

        actAndCheck(
          "click on withdrawing the allocation", {
            click on allocationElement
              .findChildElement(className("allocation-withdraw"))
              .valueOrFail("Could not find withdraw button for allocation")
          },
        )(
          "the allocation is not shown anymore",
          _ => {
            findAll(className("allocation")).toSeq shouldBe empty withClue "Allocation Cards"
          },
        )

        actAndCheck(
          "click on rejecting the allocation request", {
            click on allocationRequestElement
              .findChildElement(className("allocation-request-reject"))
              .valueOrFail("Could not find reject button for allocation request")
          },
        )(
          "the allocation request is not shown anymore",
          _ => {
            findAll(
              className("allocation-request")
            ).toSeq shouldBe empty withClue "Allocation Request Cards"
          },
        )
      }
    }

    "create a token standard allocation manually" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(1000)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        createAllocationV2ViaFrontendForm(aliceUserParty)
      }
    }

    // analogous to test_locked_funds
    "create an iterated allocation" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val providerPartyHint = s"provider-party-${Random.nextInt()}"
      val providerParty = splitwellValidatorBackend.onboardUser(
        splitwellWalletClient.config.ledgerApiUser,
        Some(
          PartyId.tryFromProtoPrimitive(
            s"$providerPartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
          )
        ),
      )
      val wantedSettlement = new allocationv2.SettlementInfo(
        java.util.List.of(providerParty.toProtoPrimitive),
        "billing/prefunded-test",
        java.util.Optional.empty(),
        emptyMetadata,
      )

      aliceWalletClient.tap(1000)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        browseToAllocationsPage()

        val nextIterationAmount = "100"

        actAndCheck(
          "create prefunded allocation", {
            textField("create-allocation-settlement-ref-id").underlying
              .sendKeys(wantedSettlement.id)
            eventuallyClickOn(id("create-allocation-settlement-executor-0"))
            setAnsField(
              textField("create-allocation-settlement-executor-0"),
              providerParty.toProtoPrimitive,
              providerParty.toProtoPrimitive,
            )

            // No transfer legs

            // Check the committed checkbox
            inside(find(id("create-allocation-committed"))) { case Some(element) =>
              element.underlying.click()
            }

            // Allow iterated settlement and set funding amount.
            inside(find(id("create-allocation-allow-iterated-settlement"))) { case Some(element) =>
              element.underlying.click()
            }
            textField("create-allocation-next-iteration-funding-amount").underlying.clear()
            textField("create-allocation-next-iteration-funding-amount").underlying
              .sendKeys(nextIterationAmount)

            eventuallyClickOn(id("create-allocation-submit-button"))
          },
        )(
          "the committed allocation is shown with next iteration funding",
          _ => {
            val allocation = findAll(className("allocation")).toSeq.loneElement

            checkSettlementInfo(
              allocation,
              wantedSettlement.id,
              None,
              Seq(providerParty.toProtoPrimitive),
            )

            // Verify committed and next iteration funding are displayed
            seleniumText(
              allocation.childElement(className("allocation-committed"))
            ) should include("yes")

            seleniumText(
              allocation.childElement(className("allocation-next-iteration-funding"))
            ) should include(nextIterationAmount)
          },
        )
      }

      val aliceAllocation = aliceWalletClient.listAmuletAllocations().loneElement

      val billingLeg = new TransferLegV2(
        "billing-leg",
        /*sender=*/ basicAccount(aliceParty),
        /*receiver=*/ basicAccount(providerParty),
        BigDecimal(0.5).bigDecimal,
        amuletInstrumentIdName,
        new Metadata(
          java.util.Map.of("splice.lfdecentralizedtrust.org/reason", "daily license fee")
        ),
      )
      val billingLegSide = new allocationv2.TransferLegSide(
        billingLeg.transferLegId,
        allocationv2.TransferSide.SENDERSIDE,
        billingLeg.receiver,
        billingLeg.amount,
        billingLeg.instrumentId,
        billingLeg.meta,
      )

      val (_, providerAllocation) = actAndCheck(
        "provider creates allocation to accept license fee payment", {
          val choice = new allocationinstructionv2.AllocationFactory_Allocate(
            wantedSettlement,
            new allocationv2.AllocationSpecification(
              dsoParty.toProtoPrimitive,
              basicAccount(providerParty),
              java.util.List.of(
                new allocationv2.TransferLegSide(
                  billingLeg.transferLegId,
                  allocationv2.TransferSide.RECEIVERSIDE,
                  basicAccount(aliceParty),
                  billingLeg.amount,
                  billingLeg.instrumentId,
                  billingLeg.meta,
                )
              ),
              java.util.Optional.empty(),
              java.util.Optional.empty(),
              false,
              emptyMetadata,
            ),
            Instant.now(),
            java.util.List.of(),
            emptyExtraArgs,
            java.util.List.of(providerParty.toProtoPrimitive),
          )
          val enrichedChoice = sv1ScanBackend.getAllocationFactoryV2(choice)
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(providerParty),
              commands = enrichedChoice.factoryId
                .exerciseAllocationFactory_Allocate(enrichedChoice.args)
                .commands()
                .asScala
                .toSeq,
              disclosedContracts = enrichedChoice.disclosedContracts,
            )
        },
      )(
        "the provider sees the allocation",
        _ => {
          val providerAllocations =
            splitwellValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
              .of_party(
                party = providerParty,
                filterInterfaces = Seq(allocationv2.Allocation.TEMPLATE_ID).map(templateId =>
                  TemplateId(
                    templateId.getPackageId,
                    templateId.getModuleName,
                    templateId.getEntityName,
                  )
                ),
              )
              .filter(_.contractId != aliceAllocation.contract.contractId.contractId)

          providerAllocations.loneElement
        },
      )

      val nextIteration =
        Map(amuletInstrumentIdName -> BigDecimal(99).bigDecimal.setScale(10)).asJava

      val (_, aliceAllocationAfter) = actAndCheck(
        "provider settles billing against prefunded allocation", {
          val enrichedChoice = sv1ScanBackend.getSettlementFactoryV2(
            new allocationv2.SettlementFactory_SettleBatch(
              wantedSettlement,
              java.util.List.of(
                billingLeg
              ),
              java.util.List.of(
                new allocationv2.FinalizedAllocation(
                  new allocationv2.Allocation.ContractId(providerAllocation.contractId),
                  java.util.List.of(),
                  java.util.Optional.empty(),
                ),
                new allocationv2.FinalizedAllocation(
                  new allocationv2.Allocation.ContractId(
                    aliceAllocation.contract.contractId.contractId
                  ),
                  java.util.List.of(billingLegSide),
                  java.util.Optional.of(nextIteration),
                ),
              ),
              java.util.List.of(providerParty.toProtoPrimitive),
              emptyExtraArgs,
            )
          )
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(providerParty),
              commands = enrichedChoice.factoryId
                .exerciseSettlementFactory_SettleBatch(enrichedChoice.args)
                .commands()
                .asScala
                .toSeq,
              disclosedContracts = enrichedChoice.disclosedContracts,
            )
        },
      )(
        "only the next iteration allocation is left",
        _ => {
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
            .of_party(
              party = providerParty,
              filterInterfaces = Seq(allocationv2.Allocation.TEMPLATE_ID).map(templateId =>
                TemplateId(
                  templateId.getPackageId,
                  templateId.getModuleName,
                  templateId.getEntityName,
                )
              ),
            ) should have size 1 withClue "Provider Allocations after settlement"

          aliceWalletClient.listAmuletAllocations().loneElement
        },
      )

      aliceAllocationAfter match {
        case TokenStandard.V1AmuletAllocation(_) => fail("Expected a V2 allocation")
        case TokenStandard.V2AmuletAllocation(contract) =>
          contract.payload.numIterations should be(1)
          contract.payload.allocation.nextIterationFunding.toScala
            .valueOrFail("Missing nextIterationFunding") should be(nextIteration)
      }
    }

  }

  private def browseToAllocationsPage()(implicit driver: WebDriverType) = {
    actAndCheck(
      "go to allocations page", {
        eventuallyClickOn(id("navlink-allocations"))
      },
    )(
      "allocations page and create form is shown",
      _ => {
        currentUrl should endWith("/allocations")
        find(id("create-allocation-settlement-ref-id")).valueOrFail(
          "Could not find create allocation form"
        )
      },
    )
  }

  private def checkSettlementInfo(
      parent: Element,
      id: String,
      cid: Option[String],
      executors: Seq[String],
  ): Unit = {
    seleniumText(
      parent.childElement(className("settlement-id"))
    ) should be(
      s"Settlement id: $id"
    )
    cid.foreach(cid =>
      seleniumText(
        parent.childElement(className("settlement-cid"))
      ) should be(s"Settlement cid: $cid")
    )
    val executorElements = parent.findAllChildElements(className("settlement-executor")).toSeq
    executorElements.map(seleniumText).zip(executors).foreach { case (actual, expected) =>
      actual should matchText(expected)
    }
  }

  private def checkTransferLegs(
      parent: Element,
      transferLegs: Map[String, TransferLegV1],
  ) = {
    val rows =
      parent.findAllChildElements(className("allocation-row")).toSeq
    rows.zip(transferLegs.toSeq.sortBy(_._1)).foreach { case (row, (legId, transferLeg)) =>
      checkTransferLeg(
        row = row,
        legId = legId,
        instrumentId = transferLeg.instrumentId.id,
        amount = transferLeg.amount,
        sender = transferLeg.sender,
        receiver = transferLeg.receiver,
      )
    }
  }

  private def checkTransferLegsV2(parent: Element, transferLegs: Seq[TransferLegV2]) = {
    val rows =
      parent.findAllChildElements(className("allocation-row")).toSeq
    rows.zip(transferLegs).foreach { case (row, transferLeg) =>
      checkTransferLeg(
        row = row,
        legId = transferLeg.transferLegId,
        instrumentId = transferLeg.instrumentId,
        amount = transferLeg.amount,
        sender = TokenStandardAccount.tryGetRegularAccountOwner(transferLeg.sender),
        receiver = TokenStandardAccount.tryGetRegularAccountOwner(transferLeg.receiver),
      )
    }
  }

  private def checkTransferLeg(
      row: Element,
      legId: String,
      instrumentId: String,
      amount: BigDecimal,
      sender: String,
      receiver: String,
  ) = {
    seleniumText(
      row.childElement(className("allocation-legid"))
    ) should matchText(legId)
    seleniumText(
      row.childElement(className("allocation-amount-instrument"))
    ) should matchText(
      s"${amount.intValue} ${instrumentId}"
    )
    seleniumText(
      row.childElement(className("allocation-sender"))
    ) should matchText(sender)
    seleniumText(
      row.childElement(className("allocation-receiver"))
    ) should matchText(receiver)
  }
}
