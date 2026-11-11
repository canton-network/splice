package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.LockedAmulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock as gl
import com.digitalasset.canton.BaseTest
import java.time.Instant
import java.util.Optional
import org.scalatest.wordspec.AnyWordSpec

class GovernanceLockUtilTest extends AnyWordSpec with BaseTest {
  private val glu = GovernanceLockUtil

  private val provider = "provider::1220aaaa"
  private val featuredApp = new gl.governancelockkind.GLK_FeaturedApp(provider)
  private val provisionalFeaturedApp =
    new gl.governancelockkind.GLK_ProvisionalFeaturedApp(provider)
  private val superValidator = new gl.governancelockkind.GLK_SuperValidatorRightsOwner("sv1")

  private def spec(
      kind: gl.GovernanceLockKind,
      vesting: Boolean,
  ): gl.GovernanceLockSpecification =
    if (vesting)
      new gl.governancelockspecification.GLS_VestingLock(
        new gl.VestingLockSpecification(kind, new RelTime(1_000_000), Instant.EPOCH)
      )
    else
      new gl.governancelockspecification.GLS_FullLock(new gl.FullLockSpecification(kind))

  "specificationTypeAndSubject" should {
    "encode every kind of full lock" in {
      glu.specificationTypeAndSubject(spec(featuredApp, false)) shouldBe
        (glu.StoreTypeAndKind(glu.StoreType.Full, glu.StoreKind.FeaturedApp), provider)
      glu.specificationTypeAndSubject(spec(provisionalFeaturedApp, false)) shouldBe
        (glu.StoreTypeAndKind(glu.StoreType.Full, glu.StoreKind.ProvisionalFeaturedApp), provider)
      glu.specificationTypeAndSubject(spec(superValidator, false)) shouldBe
        (glu.StoreTypeAndKind(glu.StoreType.Full, glu.StoreKind.SuperValidator), "sv1")
    }

    "encode every kind of vesting lock" in {
      glu.specificationTypeAndSubject(spec(featuredApp, true)) shouldBe
        (glu.StoreTypeAndKind(glu.StoreType.Vesting, glu.StoreKind.FeaturedApp), provider)
      glu.specificationTypeAndSubject(spec(provisionalFeaturedApp, true)) shouldBe
        (glu.StoreTypeAndKind(
          glu.StoreType.Vesting,
          glu.StoreKind.ProvisionalFeaturedApp,
        ), provider)
      glu.specificationTypeAndSubject(spec(superValidator, true)) shouldBe
        (glu.StoreTypeAndKind(glu.StoreType.Vesting, glu.StoreKind.SuperValidator), "sv1")
    }
  }

  private val dso = "dso::1220dddd"
  private val owner = "owner::1220bbbb"
  private val targetOwner = "target::1220cccc"

  private def lock(kind: gl.GovernanceLockKind, lockOwner: String = owner): gl.GovernanceLock =
    new gl.GovernanceLock(
      dso,
      lockOwner,
      new java.math.BigDecimal(10).setScale(10),
      new LockedAmulet.ContractId("cid"),
      spec(kind, vesting = false),
      Optional.empty(),
      Instant.EPOCH,
    )

  private def substitution(
      kind: gl.GovernanceLockKind,
      proposer: String = owner,
      lockOwner: String = targetOwner,
  ): gl.substitution.GovernanceLockSubstitution =
    new gl.substitution.GovernanceLockSubstitution(
      new gl.GovernanceLockKey(dso, lockOwner, spec(kind, vesting = false)),
      proposer,
      new java.math.BigDecimal(10).setScale(10),
      Optional.empty(),
      Instant.EPOCH,
      Instant.EPOCH,
      Optional.empty(),
    )

  "governanceLockInformees" should {
    "list the owner and the featured app provider" in {
      glu.governanceLockInformees(lock(featuredApp)) shouldBe
        Seq(owner, provider)
    }

    "list only the owner for a super validator lock" in {
      glu.governanceLockInformees(lock(superValidator)) shouldBe Seq(owner)
    }

    "not repeat an owner who is also the provider" in {
      glu.governanceLockInformees(
        lock(new gl.governancelockkind.GLK_FeaturedApp(owner))
      ) shouldBe Seq(owner)
    }
  }

  "substitutionInformees" should {
    "list the proposer, the target lock owner and the provider" in {
      glu.substitutionInformees(substitution(featuredApp)) shouldBe
        Seq(owner, targetOwner, provider)
    }

    "list only the proposer and target lock owner for a super validator lock" in {
      glu.substitutionInformees(substitution(superValidator)) shouldBe
        Seq(owner, targetOwner)
    }

    "not repeat a proposer who owns the target lock" in {
      glu.substitutionInformees(
        substitution(superValidator, proposer = targetOwner)
      ) shouldBe Seq(targetOwner)
    }
  }

  "specificationObservers" should {
    "list the provider of a featured app lock" in {
      glu.specificationObservers(
        spec(featuredApp, vesting = false)
      ) shouldBe Seq(provider)
      glu.specificationObservers(
        spec(provisionalFeaturedApp, vesting = false)
      ) shouldBe Seq(provider)
    }

    "list nobody for a super validator lock" in {
      glu.specificationObservers(spec(superValidator, vesting = false)) shouldBe
        empty
    }

    "not depend on whether the lock is vesting" in {
      glu.specificationObservers(
        spec(featuredApp, vesting = true)
      ) shouldBe Seq(provider)
      glu.specificationObservers(spec(superValidator, vesting = true)) shouldBe
        empty
    }
  }
}
