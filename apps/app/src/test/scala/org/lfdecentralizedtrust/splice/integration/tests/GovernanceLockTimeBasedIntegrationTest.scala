// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet as amuletCodegen, governancelock}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ExpireGovernanceLockSubstitutionTrigger,
  ExpireGovernanceLockTransferTrigger,
  ExpireVestingLockTrigger,
}
import org.lfdecentralizedtrust.splice.sv.config.InitialGovernanceLockConfig
import org.lfdecentralizedtrust.splice.util.{
  GovernanceLockUtil,
  TimeTestUtil,
  TokenStandardMetadata,
  WalletTestUtil,
}
import org.scalatest.Assertion

import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class GovernanceLockTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with GovernanceLockTest {

  private val triggerBatchSize = 2
  private val vestingDuration = Duration.ofMinutes(2)

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            // Shorten the SV vesting period so we can test partial and full withdrawal while only
            // advancing the clock a couple minutes. We do this so the test logic can run within a
            // single round, avoiding potential flakes caused by test logic racing against round
            // automation working through its backlog. See relevant issues: #7223, #7206
            initialGovernanceLockConfig = Some(
              InitialGovernanceLockConfig(
                superValidatorLockVestingDuration =
                  Some(NonNegativeFiniteDuration.tryFromJavaDuration(vestingDuration))
              )
            )
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            delegatelessAutomationExpiredVestingLockBatchSize = triggerBatchSize,
            delegatelessAutomationExpiredGovernanceLockTransferBatchSize = triggerBatchSize,
            delegatelessAutomationExpiredGovernanceLockSubstitutionBatchSize = triggerBatchSize,
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
          _.withPausedTrigger[ExpireGovernanceLockTransferTrigger]
            .withPausedTrigger[ExpireGovernanceLockSubstitutionTrigger]
            .withPausedTrigger[ExpireVestingLockTrigger]
        )(config)
      )

  private val lockAmount = BigDecimal(10000)
  private val expectedRemainingLockAmount = lockAmount / 4 * 3
  private val lockKind = SuperValidatorLock
  private val lockSubject = "sv1"

  private def checkLockKind(kind: governancelock.GovernanceLockKind): Assertion =
    kind shouldBe a[governancelock.governancelockkind.GLK_SuperValidatorRightsOwner]

  private def testTokenStandardCompat[CId <: ContractId[?], View](
      ops: TokenStandardOps[CId, View]
  ) =
    s"GovernanceLock TSv${ops.version} compatibility" in { implicit env =>
      // Setup alice as the lock owner
      val ownerParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val owner = RichPartyId.local(ownerParty)

      aliceWalletClient.tap(lockAmount)

      // Create the governance lock via a transfer to the magic party
      val governanceLockCid = ops.createGovernanceLock(
        aliceValidatorBackend.participantClientWithAdminToken,
        owner,
        lockKind,
        lockSubject,
        amount = lockAmount,
      )

      clue("the GovernanceLock has the correct kind") {
        val governanceLock =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(governancelock.GovernanceLock.COMPANION)(
              ownerParty,
              predicate = _.id.contractId == governanceLockCid.contractId,
            )
            .loneElement
            .data
        checkLockKind(GovernanceLockUtil.getGovernanceLockKind(governanceLock))
      }

      clue("the GovernanceLock is as a pending TransferInstruction to the magic party") {
        val (cid, view) = ops
          .listTransfers(
            aliceValidatorBackend.participantClientWithAdminToken,
            ownerParty,
          )
          .loneElement
        cid.contractId shouldBe governanceLockCid.contractId
        ops.transferValues.sender(view) shouldBe ownerParty.toProtoPrimitive
        ops.transferValues.receiver(view) shouldBe lockManagerParty.toProtoPrimitive
        ops.transferValues.amount(view) shouldBe lockAmount
        compareLockMemo(
          ops.transferValues.meta(view).values.get(TokenStandardMetadata.reasonMetaKey),
          makeOutputLockMemo(lockKind, lockSubject, Locked),
        )
      }

      clue("Scan serves a withdraw choice context for the GovernanceLock") {
        ops
          .getWithdrawContext(governanceLockCid)
          .disclosedContracts should not be empty
      }

      // Withdraw the governance lock; it's relocked as a vesting GovernanceLock and funds remain locked
      val vestingStartTime = truncateInstant(getLedgerTime.plusSeconds(1).toInstant)
      val (vestingLockCid, vestingLockView) = ops
        .unlockGovernanceLock(
          aliceValidatorBackend.participantClientWithAdminToken,
          owner,
          lockKind,
          lockSubject,
          unlockAmount = lockAmount,
          vestingStartTime = vestingStartTime,
        )
        .value
      val vestingLock =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(governancelock.GovernanceLock.COMPANION)(
            ownerParty,
            predicate = _.id.contractId == vestingLockCid.contractId,
          )
          .loneElement
          .data
      vestingLockCid.contractId should not be governanceLockCid.contractId
      ops.transferValues.sender(vestingLockView) shouldBe ownerParty.toProtoPrimitive
      ops.transferValues.receiver(vestingLockView) shouldBe lockManagerParty.toProtoPrimitive
      ops.transferValues.amount(vestingLockView) shouldBe lockAmount
      checkLockKind(GovernanceLockUtil.getGovernanceLockKind(vestingLock))
      val vestingSpec = vestingLockSpecification(vestingLock)
      vestingSpec.endTime.minus(vestingSpec.vestingPeriod.microseconds, ChronoUnit.MICROS) shouldBe
        vestingStartTime

      clue("Scan serves a withdraw choice context for the vesting lock") {
        ops
          .getWithdrawContext(vestingLockCid)
          .disclosedContracts should not be empty
      }

      clue("the amulet remains locked while vesting") {
        aliceWalletClient.balance().lockedQty shouldBe lockAmount
      }

      // Advance part of the way into the 2 minute vesting period and withdraw with an explicit
      // withdraw time of 30 seconds into the vesting period
      advanceTime(Duration.ofSeconds(31))

      val (remainingVestingLockCid, remainingVestingLockView) = ops
        .withdrawVestingLock(
          aliceValidatorBackend.participantClientWithAdminToken,
          owner,
          lockKind,
          lockSubject,
          vestingLock,
          vestedUntilTime = vestingStartTime.plusSeconds(30),
        )
        .value
      val remainingVestingLock =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(governancelock.GovernanceLock.COMPANION)(
            ownerParty,
            predicate = _.id.contractId == remainingVestingLockCid.contractId,
          )
          .loneElement
          .data
      remainingVestingLockCid.contractId should not be vestingLockCid.contractId
      ops.transferValues.sender(remainingVestingLockView) shouldBe ownerParty.toProtoPrimitive
      ops.transferValues.receiver(
        remainingVestingLockView
      ) shouldBe lockManagerParty.toProtoPrimitive
      ops.transferValues.amount(remainingVestingLockView) shouldBe expectedRemainingLockAmount
      checkLockKind(GovernanceLockUtil.getGovernanceLockKind(remainingVestingLock))
      val remainingVestingAmount = BigDecimal(remainingVestingLock.amount)
      remainingVestingAmount shouldBe expectedRemainingLockAmount
      aliceWalletClient.balance().lockedQty shouldBe expectedRemainingLockAmount

      // Advance past the end of the vesting period and withdraw with an explicit withdraw time of
      // the exact end of the vesting period
      advanceTime(Duration.ofSeconds(90))

      ops.withdrawVestingLock(
        aliceValidatorBackend.participantClientWithAdminToken,
        owner,
        lockKind,
        lockSubject,
        remainingVestingLock,
        vestedUntilTime = vestingStartTime.plusSeconds(120),
      )
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(governancelock.GovernanceLock.COMPANION)(
          ownerParty,
          c => isVestingLock(c.data),
        ) shouldBe empty

      clue("the only LockedAmulet of the owner is the expired LockedAmulet of the vesting lock") {
        val now = getLedgerTime.toInstant
        val remainingLock =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.LockedAmulet.COMPANION)(ownerParty, _ => true)
            .loneElement
            .data
        remainingLock.lock.expiresAt.isAfter(now) shouldBe false
        BigDecimal(remainingLock.amulet.amount.initialAmount) shouldBe remainingVestingAmount
      }
    }

  testTokenStandardCompat(tsv1Operations)
  testTokenStandardCompat(tsv2Operations)

  "ExpireVestingLockTrigger archives fully vested VestingLocks in batches" in { implicit env =>
    val numLocks = 3
    val trigger = sv1Backend.dsoDelegateBasedAutomation.trigger[ExpireVestingLockTrigger]

    val sv1Party = sv1Backend.getDsoInfo().svParty
    val owner = RichPartyId.local(sv1Party)
    val participant = sv1Backend.participantClientWithAdminToken

    val vestingStartTime = truncateInstant(getLedgerTime.plusSeconds(1).toInstant)
    val endTime = vestingStartTime.plus(vestingDuration)

    // `tsv1Operations.createGovernanceLock` feeds all of the owner's unlocked
    // holdings into the `TransferFactory_Transfer` choice. Thus, the tap has to
    // have landed before the first lock is submitted.
    actAndCheck(
      s"Tap ${numLocks * minimumLockAmount} CC for sv1",
      sv1WalletClient.tap(walletAmuletToUsd(numLocks * minimumLockAmount)),
    )(
      "the tapped amulet is visible",
      _ => listHoldings(participant, sv1Party).filter(_._2.lock.isEmpty) should have length 1,
    )

    actAndCheck(
      s"Lock and unlock $numLocks times, vesting until $endTime", {
        (1 to numLocks).map { _ =>
          val cid = tsv1Operations.createGovernanceLock(
            participant,
            owner,
            lockKind,
            lockSubject = lockSubject,
            amount = lockAmount,
          )
          tsv1Operations.unlockGovernanceLock(
            participant,
            owner,
            lockKind,
            lockSubject = lockSubject,
            unlockAmount = lockAmount,
            vestingStartTime = vestingStartTime,
            isValid = _.originalInstructionCid.toScala.exists(_.contractId == cid.contractId),
          )
        }
      },
    )(
      "SvDsoStore ingests all VestingLocks",
      _ => listVestingLocks should have length numLocks.toLong,
    )

    clue("The locks are not yet expired, so the trigger has no work") {
      trigger.runOnce().futureValue shouldBe false
      listVestingLocks should have length numLocks.toLong
    }

    // `listExpiredFromPayloadExpiry` uses a strict `expires_at < now`, and the trigger reads `now`
    // 5s behind the clock (`clockSkewAutomationDelay`), so step well past `endTime`.
    advanceTime(vestingDuration.plusSeconds(10))

    clue("The locks stay put while the trigger is paused") {
      listVestingLocks should have length numLocks.toLong
    }

    clue(s"First run archives one full batch of $triggerBatchSize") {
      eventually()(trigger.runOnce().futureValue shouldBe true)
      listVestingLocks should have length (numLocks - triggerBatchSize).toLong
    }

    clue("Second run archives the remaining partial batch") {
      trigger.runOnce().futureValue shouldBe true
      listVestingLocks shouldBe empty
    }
  }

  "ExpireGovernanceLockTransferTrigger archives expired transfers in batches" in { implicit env =>
    val numTransfers = 3
    val newLockOwner = aliceValidatorBackend.getValidatorPartyId()
    val vestingEndTime = getLedgerTime.plus(vestingDuration).underlying.toInstant
    val trigger =
      sv1Backend.dsoDelegateBasedAutomation.trigger[ExpireGovernanceLockTransferTrigger]

    actAndCheck(
      s"Propose $numTransfers transfers to $newLockOwner",
      (1 to numTransfers).foreach { _ =>
        tsv1Operations.transferVestingLock(vestingDuration, vestingEndTime)(
          sv1Backend.participantClientWithAdminToken,
          RichPartyId.local(sv1Backend.getDsoInfo().svParty),
          lockKind,
          lockSubject,
          newLockOwner,
          1000,
        )
      },
    )(
      "SvDsoStore ingests all GovernanceLockTransfers",
      _ => listLockTransfers should have length numTransfers.toLong,
    )

    clue("The lock transfers are not yet expired, so the trigger has no work") {
      trigger.runOnce().futureValue shouldBe false
      listLockTransfers should have length numTransfers.toLong
    }

    // `listExpiredGovernanceLockTransfers` uses a strict `contract_expires_at < now`, and the
    // trigger reads `now` 5s behind the clock (`clockSkewAutomationDelay`), so step well past
    // `endTime`.
    advanceTime(vestingDuration.plusSeconds(10))

    clue("The lock transfers stay put while the trigger is paused") {
      listLockTransfers should have length numTransfers.toLong
    }

    clue(s"First run archives one full batch of $triggerBatchSize") {
      eventually()(trigger.runOnce().futureValue shouldBe true)
      listLockTransfers should have length (numTransfers - triggerBatchSize).toLong
    }

    clue("Second run archives the remaining partial batch") {
      trigger.runOnce().futureValue shouldBe true
      listLockTransfers shouldBe empty
    }
  }

  "ExpireGovernanceLockSubstitutionTrigger archives expired substitutions in batches" in {
    implicit env =>
      val numSubstitutions = 3
      val vestingEndTime = getLedgerTime.plus(vestingDuration).underlying.toInstant
      val trigger =
        sv1Backend.dsoDelegateBasedAutomation.trigger[ExpireGovernanceLockSubstitutionTrigger]

      val sv1Party = sv1Backend.getDsoInfo().svParty

      // Proposed by a party other than the lock owner, so each proposal escrows collateral
      // that the DSO must return when the proposal expires.
      val proposerParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val proposer = RichPartyId.local(proposerParty)
      val proposerParticipant = aliceValidatorBackend.participantClientWithAdminToken
      val collateral = lockAmount * numSubstitutions

      actAndCheck(
        s"Tap $collateral CC for the proposer",
        aliceWalletClient.tap(walletAmuletToUsd(collateral)),
      )(
        "the tapped amulet is visible",
        _ =>
          listHoldings(proposerParticipant, proposerParty).filter(
            _._2.lock.isEmpty
          ) should not be empty,
      )

      actAndCheck(
        s"The proposer proposes $numSubstitutions substitutions",
        (1 to numSubstitutions).foreach { _ =>
          tsv1Operations.substituteVestingLock(sv1Party, vestingDuration, vestingEndTime)(
            proposerParticipant,
            proposer,
            lockKind,
            lockSubject,
            lockAmount,
          )
        },
      )(
        "SvDsoStore ingests all GovernanceLockSubstitutions, with the collateral locked",
        _ => {
          listLockSubstitutions should have length numSubstitutions.toLong
          listHoldings(proposerParticipant, proposerParty).filter(
            _._2.lock.isEmpty
          ) shouldBe empty
        },
      )

      clue("The substitutions are not yet expired, so the trigger has no work") {
        trigger.runOnce().futureValue shouldBe false
        listLockSubstitutions should have length numSubstitutions.toLong
      }

      // `listExpiredGovernanceLockSubstitutions` uses a strict `contract_expires_at < now`, and the
      // trigger reads `now` 5s behind the clock (`clockSkewAutomationDelay`), so step well past
      // `endTime`.
      advanceTime(vestingDuration.plusSeconds(10))

      clue("The substitutions stay put while the trigger is paused") {
        listLockSubstitutions should have length numSubstitutions.toLong
      }

      clue(s"First run archives one full batch of $triggerBatchSize") {
        eventually()(trigger.runOnce().futureValue shouldBe true)
        listLockSubstitutions should have length (numSubstitutions - triggerBatchSize).toLong
      }

      clue("Second run archives the remaining partial batch") {
        trigger.runOnce().futureValue shouldBe true
        listLockSubstitutions shouldBe empty
      }

      clue("Expiry returns the proposer's collateral") {
        // The proposer held no unlocked amulets while the proposals were pending, so any
        // unlocked holding here is returned collateral. The count is left open because
        // wallet automation merges amulets.
        eventually() {
          listHoldings(proposerParticipant, proposerParty).filter(
            _._2.lock.isEmpty
          ) should not be empty
        }
      }
  }

  private def listLockSubstitutions(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.appState.dsoStore.multiDomainAcsStore
      .listContracts(governancelock.substitution.GovernanceLockSubstitution.COMPANION)
      .futureValue

  private def listLockTransfers(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.appState.dsoStore.multiDomainAcsStore
      .listContracts(governancelock.transfer.GovernanceLockTransfer.COMPANION)
      .futureValue

  private def listVestingLocks(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.appState.dsoStore.multiDomainAcsStore
      .listContracts(governancelock.GovernanceLock.COMPANION)
      .futureValue
      .filter(lock => GovernanceLockUtil.getVestingLockSpecification(lock.payload).isDefined)
}
