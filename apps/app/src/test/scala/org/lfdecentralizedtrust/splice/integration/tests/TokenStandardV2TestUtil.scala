package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationv2,
  holdingv2,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingappv2
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardV2AllocationIntegrationTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

trait TokenStandardV2TestUtil extends TestCommon {

  protected val amuletInstrumentIdName = "Amulet"

  protected val tokenStandardV2TestDarPath =
    "token-standard/examples/splice-token-test-trading-app-v2/.daml/dist/splice-token-test-trading-app-v2-current.dar"

  def basicAccount(party: PartyId): holdingv2.Account =
    new holdingv2.Account(
      party.toProtoPrimitive,
      java.util.Optional.empty(),
      "",
    )

  def createAllocationRequestV2ViaOTCTrade(
      aliceParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobParty: PartyId,
      bobTransferAmount: BigDecimal,
      venueParty: PartyId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): CreateAllocationRequestResult = {
    val (_, otcTrade) = actAndCheck(
      "Venue creates OTC Trade", {
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(venueParty),
            commands = mkTestTrade(
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
      "There exists a trade visible to the venue's participant",
      _ =>
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(tradingappv2.OTCTrade.COMPANION)(
            venueParty
          ),
    )

    val (_, (bobAllocationRequest, aliceAllocationRequest)) = actAndCheck(
      "Venue creates allocation requests", {
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(venueParty),
            commands = otcTrade.id
              .exerciseOTCTrade_RequestAllocations()
              .commands()
              .asScala
              .toSeq,
          )
      },
    )(
      "Sender and receiver see the allocation requests",
      _ => {
        val bobAllocationRequest = inside(
          bobWalletClient.listAllocationRequests()
        ) {
          case (allocationRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest) +: Nil =>
            allocationRequest
        }
        val aliceAllocationRequest = inside(
          aliceWalletClient.listAllocationRequests()
        ) {
          case (allocationRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest) +: Nil =>
            allocationRequest
        }

        (bobAllocationRequest, aliceAllocationRequest)
      },
    )

    CreateAllocationRequestResult(otcTrade, aliceAllocationRequest, bobAllocationRequest)
  }

  def mkTestTrade(
      dso: PartyId,
      venue: PartyId,
      alice: PartyId,
      aliceTransferAmount: BigDecimal,
      bob: PartyId,
      bobTransferAmount: BigDecimal,
  ): tradingappv2.OTCTrade = {
    val aliceLeg = new tradingappv2.TradeLeg(
      dso.toProtoPrimitive,
      mkTransferLeg("leg0", alice, bob, aliceTransferAmount),
    )
    // TODO(#561): swap against a token from the token reference implementation
    val bobLeg = new tradingappv2.TradeLeg(
      dso.toProtoPrimitive,
      mkTransferLeg("leg1", bob, alice, bobTransferAmount),
    )
    new tradingappv2.OTCTrade(
      venue.toProtoPrimitive,
      Seq(aliceLeg, bobLeg).asJava,
      Instant.now(),
      // settleAt:
      // - Allocations should be made before this time.
      // Settlement happens at any point after this time.
      Instant.now().plusSeconds(30L),
      java.util.Optional.empty,
    )
  }

  def mkTransferLeg(
      legId: String,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  ): allocationv2.TransferLeg =
    new allocationv2.TransferLeg(
      legId,
      basicAccount(sender),
      basicAccount(receiver),
      amount.bigDecimal,
      amuletInstrumentIdName,
      new metadatav1.Metadata(java.util.Map.of("some_leg_meta", UUID.randomUUID().toString)),
    )

  def transferLegSideForAuthorizer(
      authorizer: PartyId,
      transferLeg: allocationv2.TransferLeg,
  ): allocationv2.TransferLegSide = {
    val (side, otherside) =
      if (transferLeg.sender.owner == authorizer.toProtoPrimitive) {
        allocationv2.TransferSide.SENDERSIDE -> transferLeg.receiver
      } else if (transferLeg.receiver.owner == authorizer.toProtoPrimitive) {
        allocationv2.TransferSide.RECEIVERSIDE -> transferLeg.sender
      } else {
        throw new IllegalArgumentException(
          s"Transfer leg `${transferLeg.transferLegId}` does not involve authorizer `${authorizer.toProtoPrimitive}`"
        )
      }

    new allocationv2.TransferLegSide(
      transferLeg.transferLegId,
      side,
      otherside,
      transferLeg.amount,
      transferLeg.instrumentId,
      transferLeg.meta,
    )
  }

  def transferLegsFromTrade(
      otcTrade: tradingappv2.OTCTrade.Contract
  ): Seq[allocationv2.TransferLeg] =
    otcTrade.data.tradeLegs.asScala.map(_.leg).toSeq

}
