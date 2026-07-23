package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.BaseTest
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationinstructionv2,
  allocationv2,
  holdingv2,
  metadatav1,
}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class TokenStandardConfigTest extends AnyWordSpec with BaseTest {

  private val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
  private val emptyExtraArgs = new metadatav1.ExtraArgs(
    new metadatav1.ChoiceContext(java.util.Map.of()),
    emptyMetadata,
  )

  "SettlementConfig" should {
    "validateSettleBatch" should {
      "accept settle batches within max legs and parties" in {
        val config = TokenStandardConfig.SettlementConfig(maxLegs = 2, maxParties = 3)

        val settleBatch = mkSettleBatch(
          Seq(
            mkTransferLeg("leg-1", "alice", "bob"),
            mkTransferLeg("leg-2", "charlie", "alice"),
          )
        )

        config.validateSettleBatch(settleBatch)
      }

      "reject settle batches with too many allocations" in {
        val config =
          TokenStandardConfig.SettlementConfig(maxLegs = 10, maxParties = 10, maxAllocations = 1)

        val ex = the[StatusRuntimeException] thrownBy {
          config.validateSettleBatch(
            mkSettleBatch(
              transferLegs = Seq.empty,
              allocations = Seq(
                mkFinalizedAllocation("alloc-1"),
                mkFinalizedAllocation("alloc-2"),
              ),
            )
          )
        }

        ex.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
        ex.getStatus.getDescription shouldBe
          "Too many allocations in the settle batch: 2. Maximum allowed: 1"
      }

      "reject settle batches with too many transfer legs" in {
        val config = TokenStandardConfig.SettlementConfig(maxLegs = 1, maxParties = 10)

        val ex = the[StatusRuntimeException] thrownBy {
          config.validateSettleBatch(
            mkSettleBatch(
              Seq(
                mkTransferLeg("leg-1", "alice", "bob"),
                mkTransferLeg("leg-2", "charlie", "dave"),
              )
            )
          )
        }

        ex.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
        ex.getStatus.getDescription shouldBe
          "Too many transfer legs in the settle batch: 2. Maximum allowed: 1"
      }

      "reject settle batches with too many parties" in {
        val config = TokenStandardConfig.SettlementConfig(maxLegs = 10, maxParties = 2)

        val ex = the[StatusRuntimeException] thrownBy {
          config.validateSettleBatch(
            mkSettleBatch(
              Seq(
                mkTransferLeg("leg-1", "alice", "bob"),
                mkTransferLeg("leg-2", "charlie", "dave"),
              )
            )
          )
        }

        ex.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
        ex.getStatus.getDescription shouldBe
          "Too many parties in the settle batch: 4. Maximum allowed: 2"
      }
    }

    "validateAllocate" should {
      "accept allocate choices within max legs and parties" in {
        val config = TokenStandardConfig.SettlementConfig(maxLegs = 2, maxParties = 2)

        config.validateAllocate(
          mkAllocate(Seq("bob", "charlie"))
        )
      }

      "reject allocate choices with too many transfer legs" in {
        val config = TokenStandardConfig.SettlementConfig(maxLegs = 1, maxParties = 10)

        val ex = the[StatusRuntimeException] thrownBy {
          config.validateAllocate(mkAllocate(Seq("bob", "charlie")))
        }

        ex.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
        ex.getStatus.getDescription shouldBe
          "Too many transfer legs in the settle batch: 2. Maximum allowed: 1"
      }

      "reject allocate choices with too many parties" in {
        val config = TokenStandardConfig.SettlementConfig(maxLegs = 10, maxParties = 2)

        val ex = the[StatusRuntimeException] thrownBy {
          config.validateAllocate(mkAllocate(Seq("bob", "charlie", "dave")))
        }

        ex.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
        ex.getStatus.getDescription shouldBe
          "Too many parties in the settle batch: 3. Maximum allowed: 2"
      }
    }
  }

  private def mkSettleBatch(
      transferLegs: Seq[allocationv2.TransferLeg],
      allocations: Seq[allocationv2.FinalizedAllocation] = Seq.empty,
  ): allocationv2.SettlementFactory_SettleBatch =
    new allocationv2.SettlementFactory_SettleBatch(
      mkSettlementInfo(),
      transferLegs.asJava,
      allocations.asJava,
      java.util.List.of("venue"),
      emptyExtraArgs,
    )

  private def mkAllocate(
      otherSideOwners: Seq[String]
  ): allocationinstructionv2.AllocationFactory_Allocate = {
    val transferLegSides = otherSideOwners.zipWithIndex.map { case (owner, index) =>
      new allocationv2.TransferLegSide(
        s"leg-${index + 1}",
        allocationv2.TransferSide.SENDERSIDE,
        mkAccount(Some(owner)),
        BigDecimal(1).bigDecimal,
        "Amulet",
        emptyMetadata,
      )
    }

    new allocationinstructionv2.AllocationFactory_Allocate(
      mkSettlementInfo(),
      new allocationv2.AllocationSpecification(
        "admin",
        mkAccount(Some("authorizer")),
        transferLegSides.asJava,
        java.util.Optional.empty[Instant](),
        java.util.Optional.empty[java.util.Map[String, java.math.BigDecimal]](),
        false,
        emptyMetadata,
      ),
      Instant.EPOCH,
      java.util.List.of(),
      emptyExtraArgs,
      java.util.List.of("authorizer"),
    )
  }

  private def mkFinalizedAllocation(cid: String): allocationv2.FinalizedAllocation =
    new allocationv2.FinalizedAllocation(
      new allocationv2.Allocation.ContractId(cid),
      java.util.List.of(),
      java.util.Optional.empty(),
    )

  private def mkTransferLeg(
      transferLegId: String,
      sender: String,
      receiver: String,
  ): allocationv2.TransferLeg =
    new allocationv2.TransferLeg(
      transferLegId,
      mkAccount(Some(sender)),
      mkAccount(Some(receiver)),
      BigDecimal(1).bigDecimal,
      "Amulet",
      emptyMetadata,
    )

  private def mkSettlementInfo(): allocationv2.SettlementInfo =
    new allocationv2.SettlementInfo(
      java.util.List.of("venue"),
      "settlement-ref",
      java.util.Optional.empty(),
      emptyMetadata,
    )

  private def mkAccount(owner: Option[String]): holdingv2.Account =
    new holdingv2.Account(
      owner.toJava,
      java.util.Optional.empty(),
      "",
    )
}
