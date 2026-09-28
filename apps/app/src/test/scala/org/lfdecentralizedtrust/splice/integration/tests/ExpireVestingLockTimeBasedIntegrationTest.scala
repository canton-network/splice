// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, LockedAmulet}
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock.VestingLock
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock.governancelockkind.GLK_SuperValidatorRightsOwner
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpireVestingLockTrigger
import org.lfdecentralizedtrust.splice.util.{
  GovernanceLockTestUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}

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
    with GovernanceLockTestUtil {

  // The token-standard CLI knows nothing about the governance-lock choices:
  // `txparse` labels an event by the nearest token-standard choice above it,
  // and neither `ExternalPartyAmuletRules_LockForGovernance` nor
  // `GovernanceLock_Unlock` is one, so the `Amulet`/`LockedAmulet` events they
  // produce come out as `"parentChoice": "none (root node)"` and `--strict`
  // rejects them.
  //
  // TODO(canton-network/splice-sv-fa-locking#80): Remove this "sanity check
  // ignore" when `GovernanceLockTestUtil` is rewritten to use TSv1 choices to
  // control the locks for tests.
  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    Amulet.TEMPLATE_ID_WITH_PACKAGE_ID,
    LockedAmulet.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  private val batchSize = 2
  private val numLocks = 3

  // Sim-time budget, all of it inside one 10-minute round tick
  // (`SpliceUtil.defaultInitialTickDuration`):
  //   t0            fixture built
  //   t0 + 1 min    unlockAt (must be strictly in the future)
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
    val sv1UserId = sv1Backend.config.ledgerApiUser
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val participant = sv1Backend.participantClientWithAdminToken

    val unlockAt = getLedgerTime.toInstant.plus(unlockDelay)
    val endTime = unlockAt.plus(vestingDuration)

    // `lockForGovernance` calls `listUnlockedHoldingCids` and feeds them into
    // the `ExternalPartyAmuletRules_LockForGovernance` choice. Thus, the tap
    // has to have landed before the first lock is submitted.
    actAndCheck(
      s"Tap $tapAmount CC for sv1",
      sv1WalletClient.tap(walletAmuletToUsd(tapAmount)),
    )(
      "the tapped amulet is visible",
      _ => listUnlockedHoldingCids(participant, sv1Party) should have length 1,
    )

    // Similar problem to what is described in `sanityChecksIgnoredRootCreates`
    // comment above but originating from the `UserWalletTxLogParser`.
    // `sv1`'s SV party is also a wallet party, so a `UserWalletService` gets
    // instantiated for this party with `DbUserWalletStore`
    // with `UserWalletTxLogParser`. And the parser runs over the transactions
    // created by this test.
    //
    // Commit 6ec1ff60499512b3e612b70d9071e7f9b07ae546 updated
    // `UserWalletTxLogParser` to handle `TransferInstruction_Withdraw`
    // implemented by `GovernanceLock` and `VestingLock` templates. But
    // `GovernanceLockTestUtil` uses
    // `ExternalPartyAmuletRules_LockForGovernance` and `GovernanceLock_Unlock`.
    //
    // TODO(canton-network/splice-sv-fa-locking#80): Remove this log supression
    // when `GovernanceLockTestUtil` is rewritten to use TSv1 choices to control
    // the locks for tests.
    val (vestingLocks, _) = loggerFactory.assertEventuallyLogsSeq(
      SuppressionRule.LevelAndAbove(Level.ERROR)
    )(
      actAndCheck(
        s"Lock and unlock $numLocks times, vesting until $endTime", {
          (1 to numLocks).map { _ =>
            val governanceLock = lockForGovernance(
              participant,
              sv1UserId,
              sv1Party,
              lockAmount,
              ownerControlledSpecification(sv1Party, new GLK_SuperValidatorRightsOwner(sv1Name)),
            )
            unlockGovernanceLock(
              participant,
              sv1UserId,
              governanceLock,
              unlockAmount = None,
              unlockAt = unlockAt,
              actors = Seq(sv1Party),
            )._1
          }
        },
      )(
        "SvDsoStore ingests all VestingLocks",
        _ => listVestingLocks should have length numLocks.toLong,
      ),
      logs => {
        logs should have length (2 * numLocks).toLong
        forAll(logs) { line =>
          line.errorMessage should include("Unexpected amulet archive event")
          line.loggerName should include("DbMultiDomainAcsStore")
        }
      },
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
