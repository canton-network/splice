package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2
import com.daml.ledger.javaapi
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv1.AllocationRequestView
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationv1,
  holdingv1,
  holdingv2,
  metadatav1,
  transferinstructionv1,
  transferinstructionv2,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation as amuletallocationCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingapp
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  FactoryChoiceWithDisclosures,
  TokenStandardMetadata,
}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import java.time.temporal.ChronoUnit
import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait TokenStandardTest extends ExternallySignedPartyTestUtil {

  // We upload the current file w/o respecting the initial package config used for Daml compatibility tests,
  // as we don't want to check upgrade compatibility for splice-token-test-trading-app. This does not conflict
  // with checking upgrade compatibility for splice-amulet, as the splice-token-test-trading-app does
  // not statically link with splice-amulet.
  // Tests that use this will require the annotation
  // `org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0`
  protected val tokenStandardTestDarPath =
    "token-standard/examples/splice-token-test-trading-app/.daml/dist/splice-token-test-trading-app-current.dar"

  val emptyExtraArgs =
    org.lfdecentralizedtrust.splice.util.ChoiceContextWithDisclosures.emptyExtraArgs

  def executeTransferViaTokenStandardV1(
      participant: ParticipantClientReference,
      sender: RichPartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
      advanceTimeBeforeExecute: Option[Duration] = None,
      description: Option[String] = None,
      // Whether to wait for the input holdings to be consumed. Setting this to false is necessary
      // for transfers where the inputs are ignored, e.g. governance lock unlocks and withdrawals
      wait: Boolean = true,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val action =
      s"Instructing transfer of $amount amulets via token standard from $sender to $receiver"
    clue(action) {
      val (factoryChoice, senderHoldingCids) = transferViaTokenStandardCommandsV1(
        participant,
        sender.partyId,
        receiver,
        amount,
        expectedKind,
        timeToLife,
        description,
      )
      participant.ledger_api_extensions.commands
        .submitJavaExternalOrLocal(
          sender,
          commands = factoryChoice.factoryId
            .exerciseTransferFactory_Transfer(factoryChoice.args)
            .commands
            .asScala
            .toSeq,
          disclosedContracts = factoryChoice.disclosedContracts,
          expectedTimeBounds = expectedTimeBounds,
          advanceTimeBeforeExecute = advanceTimeBeforeExecute,
        )
      senderHoldingCids.headOption.filter(_ => wait).foreach { trackingHoldingCid =>
        // Prepared tx execution does not wait for the tx being committed.
        // We thus wait here, as otherwise multiple transfer commands will use the same input holdings.
        checkAction()(
          action,
          "Wait until we see at least one of the input holdings being consumed",
          participant.ledger_api.event_query
            .by_contract_id(trackingHoldingCid.contractId, requestingParties = Seq(sender.partyId))
            .archived should not be empty withClue "archived holding",
        )
      }
    }
  }

  def transferViaTokenStandardCommandsV1(
      participant: ParticipantClientReference,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      description: Option[String] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): (
      FactoryChoiceWithDisclosures[
        transferinstructionv1.TransferFactory.ContractId,
        transferinstructionv1.TransferFactory_Transfer,
      ],
      Seq[holdingv1.Holding.ContractId],
  ) = {
    val now = env.environment.clock.now.toInstant
    def unlocked(optLock: java.util.Optional[holdingv1.Lock]): Boolean =
      optLock.toScala.forall(lock => lock.expiresAt.toScala.exists(t => t.isBefore(now)))
    val senderHoldingCids = listHoldings(participant, sender)
      .collect {
        case (holdingCid, holding)
            if holding.owner == sender.toProtoPrimitive && unlocked(holding.lock) =>
          new holdingv1.Holding.ContractId(holdingCid.contractId)
      }
    val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
      dsoParty.toProtoPrimitive,
      new transferinstructionv1.Transfer(
        sender.toProtoPrimitive,
        receiver.toProtoPrimitive,
        amount.bigDecimal,
        new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        now,
        now.plus(timeToLife),
        senderHoldingCids.asJava,
        new metadatav1.Metadata(
          description.toList.map(TokenStandardMetadata.reasonMetaKey -> _).toMap.asJava
        ),
      ),
      emptyExtraArgs,
    )
    val scanResponse @ (factory, kind) = sv1ScanBackend.getTransferFactory(choiceArgs)
    aliceValidatorBackend.scanProxy.getTransferFactory(choiceArgs) shouldBe scanResponse
    kind shouldBe expectedKind
    (factory, senderHoldingCids)
  }

  def executeTransferViaTokenStandardV2(
      participant: ParticipantClientReference,
      sender: RichPartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v2.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
      advanceTimeBeforeExecute: Option[Duration] = None,
      description: Option[String] = None,
      wait: Boolean = true,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    actAndCheck(
      s"Instructing transfer of $amount amulets via token standard v2 from $sender to $receiver", {
        val (factoryChoice, senderHoldingCids) = transferViaTokenStandardCommandsV2(
          participant,
          sender.partyId,
          receiver,
          amount,
          expectedKind,
          timeToLife,
          description,
        )
        participant.ledger_api_extensions.commands
          .submitJavaExternalOrLocal(
            sender,
            commands = factoryChoice.factoryId
              .exerciseTransferFactory_Transfer(factoryChoice.args)
              .commands
              .asScala
              .toSeq,
            disclosedContracts = factoryChoice.disclosedContracts,
            expectedTimeBounds = expectedTimeBounds,
            advanceTimeBeforeExecute = advanceTimeBeforeExecute,
          )
        senderHoldingCids.headOption
      },
    )(
      // Prepared tx execution does not wait for the tx being committed.
      // We thus wait here, as otherwise multiple transfer commands will use the same input holdings.
      "Wait until we see at least one of the input holdings being consumed",
      _.foreach { trackingHoldingCid =>
        if (wait)
          participant.ledger_api.event_query
            .by_contract_id(trackingHoldingCid.contractId, requestingParties = Seq(sender.partyId))
            .archived should not be empty withClue "archived holding"
      },
    )
  }

  def transferViaTokenStandardCommandsV2(
      participant: ParticipantClientReference,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v2.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      description: Option[String] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): (
      FactoryChoiceWithDisclosures[
        transferinstructionv2.TransferFactory.ContractId,
        transferinstructionv2.TransferFactory_Transfer,
      ],
      Seq[holdingv2.Holding.ContractId],
  ) = {
    val now = env.environment.clock.now.toInstant
    def unlocked(optLock: java.util.Optional[holdingv1.Lock]): Boolean =
      optLock.toScala.forall(lock => lock.expiresAt.toScala.exists(t => t.isBefore(now)))
    def account(party: PartyId): holdingv2.Account =
      new holdingv2.Account(
        java.util.Optional.of(party.toProtoPrimitive),
        java.util.Optional.empty(),
        "",
      )
    val senderHoldingCids = listHoldings(participant, sender)
      .collect {
        case (holdingCid, holding)
            if holding.owner == sender.toProtoPrimitive && unlocked(holding.lock) =>
          new holdingv2.Holding.ContractId(holdingCid.contractId)
      }
    val choiceArgs = new transferinstructionv2.TransferFactory_Transfer(
      new transferinstructionv2.Transfer(
        account(sender),
        account(receiver),
        amount.bigDecimal,
        new holdingv2.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        now,
        now.plus(timeToLife),
        senderHoldingCids.asJava,
        new metadatav1.Metadata(
          description.toList.map(TokenStandardMetadata.reasonMetaKey -> _).toMap.asJava
        ),
      ),
      Seq(sender.toProtoPrimitive).asJava,
      emptyExtraArgs,
    )
    val (factory, kind) = sv1ScanBackend.getTransferFactoryV2(choiceArgs)
    kind shouldBe expectedKind
    (factory, senderHoldingCids)
  }

  def listHoldings(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        holdingv1.Holding.ContractId,
        holdingv1.HoldingView,
    )
  ] = {
    val holdings =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces = Seq(holdingv1.Holding.TEMPLATE_ID).map(templateId =>
          TemplateId(
            templateId.getPackageId,
            templateId.getModuleName,
            templateId.getEntityName,
          )
        ),
      )
    holdings.map(instr => {
      val instrViewRaw = (instr.event.interfaceViews.head.viewValue
        .getOrElse(throw new RuntimeException("expected an interface view to be present")))
      val instrView = holdingv1.HoldingView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new holdingv1.Holding.ContractId(instr.contractId), instrView)
    })
  }

  def listTransferInstructions(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        transferinstructionv1.TransferInstruction.ContractId,
        transferinstructionv1.TransferInstructionView,
    )
  ] = {
    val instructions =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces =
          Seq(transferinstructionv1.TransferInstruction.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
      )
    instructions.map(instr => {
      val instrViewRaw = (instr.event.interfaceViews.head.viewValue
        .getOrElse(throw new RuntimeException("expected an interface view to be present")))
      val instrView = transferinstructionv1.TransferInstructionView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new TransferInstruction.ContractId(instr.contractId), instrView)
    })
  }

  def listTransferInstructionsV2(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        transferinstructionv2.TransferInstruction.ContractId,
        transferinstructionv2.TransferInstructionView,
    )
  ] = {
    val instructions =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces =
          Seq(transferinstructionv2.TransferInstruction.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
      )
    instructions.map(instr => {
      val instrViewRaw = (instr.event.interfaceViews.head.viewValue
        .getOrElse(throw new RuntimeException("expected an interface view to be present")))
      val instrView = transferinstructionv2.TransferInstructionView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new transferinstructionv2.TransferInstruction.ContractId(instr.contractId), instrView)
    })
  }

  def acceptTransferInstructionV1(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionAcceptContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionAcceptContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Accept(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def acceptTransferInstructionV2(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv2.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionAcceptContextV2(instructionCid)
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Accept(
            new transferinstructionv2.TransferInstruction_Accept(
              List(receiver.partyId.toProtoPrimitive).asJava,
              choiceContext.toExtraArgs(),
            )
          )
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def rejectTransferInstructionV1(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionRejectContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionRejectContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Reject(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def rejectTransferInstructionV2(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv2.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionRejectContextV2(instructionCid)
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Reject(
            new transferinstructionv2.TransferInstruction_Reject(
              List(receiver.partyId.toProtoPrimitive).asJava,
              choiceContext.toExtraArgs(),
            )
          )
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def withdrawTransferInstructionV1(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionWithdrawContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionWithdrawContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Withdraw(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def withdrawTransferInstructionV2(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv2.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionWithdrawContextV2(instructionCid)
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Withdraw(
            new transferinstructionv2.TransferInstruction_Withdraw(
              List(receiver.partyId.toProtoPrimitive).asJava,
              choiceContext.toExtraArgs(),
            )
          )
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  // Creates an OTCTradeProposal (alice-submitted), has the counterparty accept it,
  // and has the venue initiate settlement. Returns the on-ledger OTCTrade plus
  // alice's and bob's AllocationRequestViews.
  //
  // Hardcoded assumptions (not currently parameterized):
  // - `aliceParty` must be hosted on `aliceValidatorBackend` (alice's proposal
  //   submission goes through that participant).
  // - `venueParty` must be hosted on `splitwellValidatorBackend` (the venue's
  //   InitiateSettlement submission and the venue-side proposal visibility awaits
  //   go through that participant).
  // - Both `aliceRequest` and `bobRequest` are queried via `aliceWalletClient`
  //   (both legs are in the same AllocationRequestView, so this works even for
  //   the "bob side" caller).
  //
  // `bobValidator` is the only counterparty-dependent participant that's been
  // parameterized — pass the validator that hosts `bobParty`.
  // 5-arg overload: defaults to the standard alice↔bob trade where the
  // counterparty is bob. Calls through to the 6-arg form.
  def createAllocationRequestViaOTCTrade(
      aliceParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobParty: PartyId,
      bobTransferAmount: BigDecimal,
      venueParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): CreateAllocationRequestResult =
    createAllocationRequestViaOTCTrade(
      aliceParty,
      aliceTransferAmount,
      bobParty,
      bobTransferAmount,
      venueParty,
      bobValidatorBackend,
    )

  def createAllocationRequestViaOTCTrade(
      aliceParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobParty: PartyId,
      bobTransferAmount: BigDecimal,
      venueParty: PartyId,
      bobValidator: ValidatorAppBackendReference,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): CreateAllocationRequestResult = {
    // Alice creates the TestTradeProposal
    val (_, aliceProposal) =
      actAndCheck(
        "Create test OTC Trade Proposal", {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(aliceParty),
              commands = mkTestTradeProposal(
                dsoParty,
                venueParty,
                aliceParty,
                aliceTransferAmount,
                bobParty,
                bobTransferAmount,
              )
                .create()
                .commands()
                .asScala
                .toSeq,
            )

        },
      )(
        "There exists a trade proposal visible to both the counterparty's and the venue's participants",
        _ => {
          bobValidator.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(tradingapp.OTCTradeProposal.COMPANION)(
              bobParty
            )
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(tradingapp.OTCTradeProposal.COMPANION)(
              bobParty
            )
        },
      )

    // Counterparty accepts
    val (_, acceptedProposal) =
      actAndCheck(
        "Counterparty accepts alice's trade proposal", {
          bobValidator.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(bobParty),
              commands = aliceProposal.id
                .exerciseOTCTradeProposal_Accept(
                  bobParty.toProtoPrimitive
                )
                .commands()
                .asScala
                .toSeq,
            )

        },
      )(
        "The new trade proposal exists and is visible to the venue's participant",
        _ => {
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(tradingapp.OTCTradeProposal.COMPANION)(
              venueParty,
              predicate = c => c.data.approvers.size == 2,
            )
        },
      )

    // Venue initiates settlement
    // Use the Canton clock (not wall clock) so this works correctly in simtime too.
    val now = env.environment.clock.now.toInstant
    val prepareUntil = now.plus(10, ChronoUnit.MINUTES)
    val settleUntil = prepareUntil.plus(10, ChronoUnit.MINUTES)

    val (_, (trade, aliceRequest, bobRequest)) =
      actAndCheck(
        "Venue initiates settlement", {
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(venueParty),
              commands = acceptedProposal.id
                .exerciseOTCTradeProposal_InitiateSettlement(
                  prepareUntil,
                  settleUntil,
                )
                .commands()
                .asScala
                .toSeq,
            )

        },
      )(
        "There exists an OTCTrade visible as an allocation request to Alice and Bob",
        _ =>
          suppressFailedClues(loggerFactory) {
            val trade =
              splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .awaitJava(tradingapp.OTCTrade.COMPANION)(
                  venueParty
                )
            val aliceRequest = clue("Alice sees the allocation request") {
              val requests = listAllocationRequests(aliceWalletClient)
              val request = requests.loneElement
              request.transferLegs.asScala should have size (2) withClue "transferLegs"
              request
            }
            val bobRequest = clue("Bob sees the allocation request") {
              val requests = listAllocationRequests(aliceWalletClient)
              val request = requests.loneElement
              request.transferLegs.asScala should have size (2) withClue "transferLegs"
              request
            }
            (trade, aliceRequest, bobRequest)
          },
      )

    CreateAllocationRequestResult(trade, aliceRequest, bobRequest)
  }

  def waitForAllocationsOnParticipant(
      participant: ParticipantClientReference,
      party: PartyId,
      allocationIds: allocationv1.Allocation.ContractId*
  ): Unit =
    allocationIds.foreach { allocationId =>
      clue(s"Wait for allocation $allocationId to be visible to $party on ${participant.id}") {
        participant.ledger_api_extensions.acs
          .awaitJava(amuletallocationCodegen.AmuletAllocation.COMPANION)(
            party,
            predicate = c => c.id.contractId == allocationId.contractId,
          )
      }
    }

  def listAllocationRequests(
      walletClient: WalletAppClientReference
  ): Seq[AllocationRequestView] = {
    clue(s"Retrieves allocation requests for ${walletClient.name}") {
      walletClient.listAllocationRequests().map {
        case allocationRequest: HttpWalletAppClient.TokenStandard.V1AllocationRequest =>
          allocationRequest.contract.payload
        case v2 =>
          fail(s"V2 Allocation Requests shouldn't be happening in a V1 test: $v2")
      }
    }
  }

  def mkTestTradeProposal(
      dso: PartyId,
      venue: PartyId,
      alice: PartyId,
      aliceTransferAmount: BigDecimal,
      bob: PartyId,
      bobTransferAmount: BigDecimal,
  ): tradingapp.OTCTradeProposal = {
    val aliceLeg = mkTransferLeg(dso, alice, bob, aliceTransferAmount)
    // TODO(#561): swap against a token from the token reference implementation
    val bobLeg = mkTransferLeg(dso, bob, alice, bobTransferAmount)
    new tradingapp.OTCTradeProposal(
      venue.toProtoPrimitive,
      None.toJava,
      Map("leg0" -> aliceLeg, "leg1" -> bobLeg).asJava,
      Seq(alice.toProtoPrimitive).asJava,
    )
  }

  def mkTransferLeg(
      dso: PartyId,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  ): allocationv1.TransferLeg =
    new allocationv1.TransferLeg(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      amount.bigDecimal,
      new holdingv1.InstrumentId(dso.toProtoPrimitive, "Amulet"),
      new metadatav1.Metadata(java.util.Map.of("some_leg_meta", UUID.randomUUID().toString)),
    )

  // Allocates `walletClient`'s amulet for `legId` of the pending OTC trade `request`.
  // Returns the resulting Allocation contract id for use in OTCTrade_Settle.
  def createAllocation(
      walletClient: WalletAppClientReference,
      request: AllocationRequestView,
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

  // Runs an end-to-end 2-leg OTC trade settlement: alice allocates her amulet,
  // the counterparty (bob) allocates theirs, both allocations propagate to the
  // venue's participant, then the venue submits OTCTrade_Settle. Returns the
  // settlement's updateId. Amounts are passed explicitly so this trait doesn't
  // need WalletTestUtil's walletUsdToAmulet; callers compute the amulet-side
  // value from USD themselves.
  //
  // Hardcoded assumptions (inherited from createAllocationRequestViaOTCTrade):
  // - `aliceParty` must be hosted on `aliceValidatorBackend`.
  // - `venueParty` must be hosted on `splitwellValidatorBackend` (used also for
  //   the final OTCTrade_Settle submission and the allocation-visibility await).
  //
  // `bobValidator` is the counterparty's participant host — pass
  // bobValidatorBackend for alice↔bob trades, or the counterparty's actual
  // validator for other pairs.
  // 7-arg overload: defaults to the standard alice↔bob trade where the
  // counterparty is bob. Calls through to the 8-arg form.
  def settleTrade(
      aliceWallet: WalletAppClientReference,
      aliceParty: PartyId,
      bobWallet: WalletAppClientReference,
      bobParty: PartyId,
      venueParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobTransferAmount: BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment): String =
    settleTrade(
      aliceWallet,
      aliceParty,
      bobWallet,
      bobParty,
      venueParty,
      aliceTransferAmount,
      bobTransferAmount,
      bobValidatorBackend,
    )

  def settleTrade(
      aliceWallet: WalletAppClientReference,
      aliceParty: PartyId,
      bobWallet: WalletAppClientReference,
      bobParty: PartyId,
      venueParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobTransferAmount: BigDecimal,
      bobValidator: ValidatorAppBackendReference,
  )(implicit env: SpliceTestConsoleEnvironment): String = {
    val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
    val CreateAllocationRequestResult(trade, aliceRequest, bobRequest) =
      createAllocationRequestViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
        bobValidator,
      )

    val aliceAllocationId = createAllocation(aliceWallet, aliceRequest, "leg0")
    val bobAllocationId = createAllocation(bobWallet, bobRequest, "leg1")

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
}

object TokenStandardTest {
  case class CreateAllocationRequestResult(
      trade: tradingapp.OTCTrade.Contract,
      aliceRequest: AllocationRequestView,
      bobRequest: AllocationRequestView,
  )
}
