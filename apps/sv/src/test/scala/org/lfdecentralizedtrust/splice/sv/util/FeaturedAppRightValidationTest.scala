package org.lfdecentralizedtrust.splice.sv.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.lfdecentralizedtrust.splice.util.Contract

class FeaturedAppRightValidationTest extends StoreTestBase {

  private def fakeFeaturedAppRight(
      providerIdx: Int,
      ops: Seq[String],
      cid: String = nextCid(),
  ): Contract[FeaturedAppRight.ContractId, FeaturedAppRight] =
    featuredAppRight(providerParty(providerIdx), contractId = cid, opsParties = Some(ops))

  private def fakeProvider(idx: Int): String = providerParty(idx).toProtoPrimitive

  "validateGrant" should {

    "accept a clean grant with no existing rights" in {
      FeaturedAppRightValidation.validateGrant(
        fakeProvider(1),
        Some(Seq(fakeProvider(2))),
        Seq.empty,
      ) shouldBe Right(())
    }

    "accept a grant when opsParties are absent" in {
      FeaturedAppRightValidation.validateGrant(
        fakeProvider(1),
        None,
        Seq(fakeFeaturedAppRight(2, ops = Seq(fakeProvider(3)))),
      ) shouldBe Right(())
    }

    "reject opsParties already used by an existing right" in {
      FeaturedAppRightValidation.validateGrant(
        fakeProvider(1),
        Some(Seq(fakeProvider(3))),
        Seq(fakeFeaturedAppRight(2, ops = Seq(fakeProvider(3)))),
      ) shouldBe Left(s"opsParties already used: ${fakeProvider(3)}")
    }

    "reject a provider listing itself as an opsParty" in {
      FeaturedAppRightValidation.validateGrant(
        fakeProvider(1),
        Some(Seq(fakeProvider(1))),
        Seq.empty,
      ) shouldBe Left("provider cannot be its own opsParty")
    }
  }

  "validateUpdate" should {

    "reject opsParties already used by another right" in {
      val other = fakeFeaturedAppRight(2, ops = Seq(fakeProvider(3)))
      val self = fakeFeaturedAppRight(1, ops = Seq.empty)
      FeaturedAppRightValidation.validateUpdate(
        self.contractId,
        Some(Seq(fakeProvider(3))),
        Seq(other, self),
      ) shouldBe Left(s"opsParties already used: ${fakeProvider(3)}")
    }

    "allow reusing opsParties owned by the right being updated" in {
      val self = fakeFeaturedAppRight(1, ops = Seq(fakeProvider(3)))
      FeaturedAppRightValidation.validateUpdate(
        self.contractId,
        Some(Seq(fakeProvider(3))),
        Seq(self),
      ) shouldBe Right(())
    }

    "accept an update with no overlap" in {
      val self = fakeFeaturedAppRight(1, ops = Seq.empty)
      val other = fakeFeaturedAppRight(2, ops = Seq(fakeProvider(3)))
      FeaturedAppRightValidation.validateUpdate(
        self.contractId,
        Some(Seq(fakeProvider(4))),
        Seq(self, other),
      ) shouldBe Right(())
    }

    "accept an update with absent opsParties" in {
      val self = fakeFeaturedAppRight(1, ops = Seq.empty)
      val other = fakeFeaturedAppRight(2, ops = Seq(fakeProvider(3)))
      FeaturedAppRightValidation.validateUpdate(
        self.contractId,
        None,
        Seq(self, other),
      ) shouldBe Right(())
    }
  }
}
