// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock.VestingLock
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpireVestingLockTrigger
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, TriggerTestUtil, WalletTestUtil}

import java.time.Duration

/** Covers `ExpireVestingLockTrigger`: batched archival of fully vested
  * `VestingLock`s.
  *
  * The trigger is a
  * [[org.lfdecentralizedtrust.splice.automation.BatchedMultiDomainExpiredContractTrigger]],
  * and `runOnce()` on a polling-parallel trigger processes exactly the head
  * task. One task in one batch in case of `ExpiredLockedAmuletTrigger`.  With
  * `delegatelessAutomationExpiredVestingLockBatchSize = 2` and three expired
  * locks that makes the batching observable and deterministic: 2, then 1, then
  * nothing left to do.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_24
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_30
class ExpireVestingLockTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with TokenStandardTest {

  private val batchSize = 2
  private val numLocks = 3

  // Sim-time budget, all of it inside one 10-minute round tick
  // (`SpliceUtil.defaultInitialTickDuration`):
  //   t0            fixture built
  //   t0 + 1 min    unlockAt (`TransferInstruction_Withdraw` picks the first
  //                 `requestedAt + n * granularity` point strictly after the
  //                 ledger time; granularity = unlockDelay, see the config below)
  //   t0 + 6 min    endTime  (= unlockAt + vestingDuration, taken from the config below)
  //   t0 + 7 min    after advanceTime (strictly past endTime)
  // Advances spanning many round ticks make the round automation work through a backlog that
  // everything afterwards then races; see canton-network/splice#7223.
  private val unlockDelay = Duration.ofMinutes(1)
  private val vestingDuration = Duration.ofMinutes(5)
  private val totalAdvance = Duration.ofMinutes(7)

  // Same as `defaultGovernanceLockMinimumLockAmount` in Daml.
  private val governanceLockMinimumLockAmount = BigDecimal(10000.0)

  private val lockAmount = governanceLockMinimumLockAmount
  private val tapAmount = numLocks * lockAmount

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ExpireVestingLockTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(delegatelessAutomationExpiredVestingLockBatchSize = batchSize)
        )(config)
      )
      // Makes the vesting period test-sized. Without this it would be the SV default of 365 days.
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            initialGovernanceLockSuperValidatorLockVestingDuration =
              Some(NonNegativeFiniteDuration.ofMillis(vestingDuration.toMillis))
          )
        )(config)
      )

  "ExpireVestingLockTrigger archives fully vested VestingLocks in batches" in { implicit env =>
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val owner = RichPartyId.local(sv1Party)
    val participant = sv1Backend.participantClientWithAdminToken

    val unlockAt = getLedgerTime.plus(unlockDelay)
    val endTime = unlockAt.toInstant.plus(vestingDuration)

    // `createGovernanceLockViaTokenStandard` feeds all of the owner's unlocked
    // holdings into the `TransferFactory_Transfer` choice. Thus, the tap has to
    // have landed before the first lock is submitted.
    actAndCheck(
      s"Tap $tapAmount CC for sv1",
      sv1WalletClient.tap(walletAmuletToUsd(tapAmount)),
    )(
      "the tapped amulet is visible",
      _ => listHoldings(participant, sv1Party).filter(_._2.lock.isEmpty) should have length 1,
    )

    actAndCheck(
      s"Lock and unlock $numLocks times, vesting until $endTime", {
        (1 to numLocks).map { _ =>
          val governanceLock = createGovernanceLockViaTokenStandard(
            participant,
            owner,
            superValidatorLockMagicParty,
            lockSubject = sv1Name,
            amount = lockAmount,
          )
          unlockGovernanceLockViaTokenStandard(
            participant,
            owner,
            governanceLock,
            meta = Map(governanceLockUnlockAtMetaKey -> unlockAt.toMicros.toString),
          )
        }
      },
    )(
      "SvDsoStore ingests all VestingLocks",
      _ => listVestingLocks should have length numLocks.toLong,
    )

    clue("The locks are not yet expired, so the trigger has no work") {
      expireVestingLockTrigger.runOnce().futureValue shouldBe false
      listVestingLocks should have length numLocks.toLong
    }

    // `listExpiredFromPayloadExpiry` uses a strict `expires_at < now`, so we step strictly past
    // `endTime` rather than exactly onto it.
    advanceTime(totalAdvance)

    clue("The locks stay put while the trigger is paused") {
      listVestingLocks should have length numLocks.toLong
    }

    clue(s"First run archives one full batch of $batchSize") {
      expireVestingLockTrigger.runOnce().futureValue shouldBe true
      listVestingLocks should have length (numLocks - batchSize).toLong
    }

    clue("Second run archives the remaining partial batch") {
      expireVestingLockTrigger.runOnce().futureValue shouldBe true
      listVestingLocks shouldBe empty
    }
  }

  private def sv1Name(implicit env: SpliceTestConsoleEnvironment): String = {
    val info = sv1Backend.getDsoInfo()
    info.dsoRules.payload.svs.get(info.svParty.toProtoPrimitive).name
  }

  private def expireVestingLockTrigger(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.dsoDelegateBasedAutomation.trigger[ExpireVestingLockTrigger]

  private def listVestingLocks(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.appState.dsoStore.multiDomainAcsStore
      .listContracts(VestingLock.COMPANION)
      .futureValue
}
