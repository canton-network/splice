package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{allocationv2, metadatav1}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1.InstrumentId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import java.time.Instant
import java.util.{Optional, UUID}

class TokenStandardFetchFallbackIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil
    with TokenStandardV2TestUtil
    with HasActorSystem
    with HasExecutionContext {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "Token Standard" should {

    "TransferInstruction context can be fetched from Scan even if it's not yet ingested into the store" in {
      implicit env =>
        pauseScanUpdateIngestionWithin(sv1ScanBackend) {
          onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
          val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
          aliceWalletClient.tap(100)

          val response = actAndCheck(
            "Alice creates transfer offer",
            aliceWalletClient.createTokenStandardTransfer(
              bobUserParty,
              10,
              s"Transfer",
              CantonTimestamp.now().plusSeconds(3600L),
              UUID.randomUUID().toString,
            ),
          )(
            "Alice and Bob see it",
            _ => {
              Seq(aliceWalletClient, bobWalletClient).foreach(
                _.listTokenStandardTransfers() should have size 1 withClue "TokenStandardTransfers"
              )
            },
          )._1

          val cid = response.output match {
            case members.TransferInstructionPending(value) =>
              new TransferInstruction.ContractId(value.transferInstructionCid)
            case _ => fail("The transfers were expected to be pending.")
          }

          clue("SV-1's Scan sees it (still, even though ingestion is paused)") {
            eventuallySucceeds() {
              sv1ScanBackend.getTransferInstructionAcceptContext(cid)
            }
          }
        }
    }

    "AmuletAllocations context can be fetched from Scan even if it's not yet ingested into the store" in {
      implicit env =>
        pauseScanUpdateIngestionWithin(sv1ScanBackend) {
          val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
          val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
//        // Allocate venue on separate participant node, we still go through the validator API instead of parties.enable
//        // so we can use the standard wallet client APIs but give the party a more useful name than splitwell.
//        val venuePartyHint = s"venue-party-${Random.nextInt()}"
//        val venueParty = splitwellValidatorBackend.onboardUser(
//          splitwellWalletClient.config.ledgerApiUser,
//          Some(
//            PartyId.tryFromProtoPrimitive(
//              s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
//            )
//          ),
//        )

          aliceWalletClient.tap(100)
          val referenceId = UUID.randomUUID().toString

          val (_, allocation) = actAndCheck(
            "Alice creates an Allocation",
            aliceWalletClient.allocateAmulet(
              new AllocationSpecification(
                new SettlementInfo(
                  dsoParty.toProtoPrimitive,
                  new Reference(referenceId, Optional.empty),
                  Instant.now,
                  Instant.now.plusSeconds(3600L),
                  Instant.now.plusSeconds(2 * 3600L),
                  ChoiceContextWithDisclosures.emptyMetadata,
                ),
                UUID.randomUUID().toString,
                new TransferLeg(
                  aliceParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                  BigDecimal(10).bigDecimal,
                  new InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
                  ChoiceContextWithDisclosures.emptyMetadata,
                ),
              )
            ),
          )(
            "Alice sees the Allocation",
            _ => {
              val allocation = inside(aliceWalletClient.listAmuletAllocations()) {
                case (allocationRequest: HttpWalletAppClient.TokenStandard.V1AmuletAllocation) +: Nil =>
                  allocationRequest
              }
              allocation.contract.payload.allocation.settlement.settlementRef.id should be(
                referenceId
              )
              allocation
            },
          )

          clue("SV-1's Scan sees V1 allocation (still, even though ingestion is paused)") {
            eventuallySucceeds() {
              sv1ScanBackend.getAllocationTransferContext(
                allocation.contract.contractId.toInterface(Allocation.INTERFACE)
              )
            }
          }

          val referenceIdV2 = UUID.randomUUID().toString

          val (_, allocationV2) = actAndCheck(
            "Alice creates a V2 Allocation",
            aliceWalletClient.allocateAmulet(
              new allocationv2.AllocationSpecification(
                new allocationv2.SettlementInfo(
                  java.util.List.of(dsoParty.toProtoPrimitive),
                  new allocationv2.Reference(referenceIdV2, Optional.empty),
                  Optional.of(Instant.now.plusSeconds(2 * 3600L)),
                  new metadatav1.Metadata(java.util.Map.of()),
                ),
                dsoParty.toProtoPrimitive,
                basicAccount(aliceParty),
                java.util.List.of(
                  transferLegSideForAuthorizer(
                    aliceParty,
                    new allocationv2.TransferLeg(
                      UUID.randomUUID().toString,
                      basicAccount(aliceParty),
                      basicAccount(bobParty),
                      BigDecimal(10).bigDecimal,
                      amuletInstrumentIdName,
                      new metadatav1.Metadata(java.util.Map.of()),
                    ),
                  )
                ),
                java.util.Optional.empty[java.util.Map[String, java.math.BigDecimal]](),
                false,
                new metadatav1.Metadata(java.util.Map.of()),
              )
            ),
          )(
            "Alice sees the V2 Allocation",
            _ => {
              val alloc = inside(aliceWalletClient.listAmuletAllocations()) {
                case _ :+ (v2Alloc: HttpWalletAppClient.TokenStandard.V2AmuletAllocation) =>
                  v2Alloc
              }
              alloc.contract.payload.allocation.settlement.settlementRef.id should be(
                referenceIdV2
              )
              alloc
            },
          )

          clue(
            "SV-1's Scan sees V2 allocation cancel context (still, even though ingestion is paused)"
          ) {
            eventuallySucceeds() {
              sv1ScanBackend.getAllocationV2CancelContext(
                allocationV2.contract.contractId.toInterface(
                  allocationv2.Allocation.INTERFACE
                )
              )
            }
          }
        }
    }

  }

}
