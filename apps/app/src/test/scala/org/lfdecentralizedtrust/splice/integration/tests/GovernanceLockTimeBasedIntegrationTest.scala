// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet as amuletCodegen, governancelock}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
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
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
}
import org.scalatest.Assertion

import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class GovernanceLockTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with WalletTxLogTestUtil
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

  // Wallet automation runs in the background, e.g. to merge the amulets that a withdrawal unlocks,
  // so its entries could appear after the cursor of the previous lock action
  private def isWalletAutomationBatch(entry: TxLogEntry): Boolean =
    entry match {
      case t: TransferTxLogEntry =>
        t.getSubtype.choice == install.WalletAppInstall.CHOICE_WalletAppInstall_ExecuteBatch.name
      case _ => false
    }

  private def checkLockActionHistory(
      previousEventId: Option[String],
      expectedBalanceChange: BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment): Option[String] =
    checkTxHistory(
      aliceWalletClient,
      Seq({ case entry: TransferTxLogEntry =>
        entry.sender.value.amount shouldBe expectedBalanceChange
      }),
      previousEventId = previousEventId,
      trafficTopups = IgnoreTopupsDevNet,
      ignore = isWalletAutomationBatch,
    ).headOption.map(_.eventId)

  /** Checks alice's unlocked and locked balances after a lock action, and that
    * the action's wallet history entry captures expected balance change.
    */
  private def checkBalancesAndHistory(
      action: String,
      previousEventId: Option[String],
      expectedUnlocked: BigDecimal,
      expectedLocked: BigDecimal,
      expectedBalanceChange: BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment): Option[String] =
    clue(s"alice's balances and wallet history after $action") {
      eventually() {
        val balance = aliceWalletClient.balance()
        balance.unlockedQty shouldBe expectedUnlocked
        balance.lockedQty shouldBe expectedLocked
      }
      checkLockActionHistory(previousEventId, expectedBalanceChange)
    }

  private def testTokenStandardCompat[CId <: ContractId[?], View](
      ops: TokenStandardOps[CId, View]
  ) =
    s"GovernanceLock TSv${ops.version} compatibility" in { implicit env =>
      // Setup alice as the lock owner
      val ownerParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val owner = RichPartyId.local(ownerParty)

      aliceWalletClient.tap(lockAmount)
      val beforeCreate = checkTxHistory(
        aliceWalletClient,
        Seq({ case e: BalanceChangeTxLogEntry =>
          e.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
        }),
        trafficTopups = IgnoreTopupsDevNet,
      ).headOption.map(_.eventId)
      val unlockedAfterTap = aliceWalletClient.balance().unlockedQty

      // Create the governance lock via a transfer to the magic party
      val governanceLockCid = ops.createGovernanceLock(
        aliceValidatorBackend.participantClientWithAdminToken,
        owner,
        lockKind,
        lockSubject,
        amount = lockAmount,
      )
      val beforeUnlock = checkBalancesAndHistory(
        "creating the lock",
        beforeCreate,
        expectedUnlocked = unlockedAfterTap - lockAmount,
        expectedLocked = lockAmount,
        expectedBalanceChange = -lockAmount,
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
        ops.transferValues.receiver(view) shouldBe
          GovernanceLockUtil.lockManagerParty.toProtoPrimitive
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
      ops.transferValues.receiver(vestingLockView) shouldBe
        GovernanceLockUtil.lockManagerParty.toProtoPrimitive
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

      // The amulet remains locked while vesting
      val beforePartialWithdraw = checkBalancesAndHistory(
        "unlocking and starting to vest",
        beforeUnlock,
        expectedUnlocked = unlockedAfterTap - lockAmount,
        expectedLocked = lockAmount,
        expectedBalanceChange = BigDecimal(0),
      )

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
      ) shouldBe GovernanceLockUtil.lockManagerParty.toProtoPrimitive
      ops.transferValues.amount(remainingVestingLockView) shouldBe expectedRemainingLockAmount
      checkLockKind(GovernanceLockUtil.getGovernanceLockKind(remainingVestingLock))
      val remainingVestingAmount = BigDecimal(remainingVestingLock.amount)
      remainingVestingAmount shouldBe expectedRemainingLockAmount
      val beforeFullWithdraw = checkBalancesAndHistory(
        "withdrawing the vested funds mid-vesting",
        beforePartialWithdraw,
        expectedUnlocked = unlockedAfterTap - expectedRemainingLockAmount,
        expectedLocked = expectedRemainingLockAmount,
        expectedBalanceChange = lockAmount - expectedRemainingLockAmount,
      )

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
      // The vested funds stay in the expired LockedAmulet, which the wallet
      // still counts as locked, so neither balance changes
      checkBalancesAndHistory(
        "withdrawing the fully vested funds",
        beforeFullWithdraw,
        expectedUnlocked = unlockedAfterTap - expectedRemainingLockAmount,
        expectedLocked = expectedRemainingLockAmount,
        expectedBalanceChange = BigDecimal(0),
      )

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

      val beforeExpiry = clue("The proposer's wallet history shows the tap and each proposal") {
        checkTxHistory(
          aliceWalletClient,
          Seq.fill[CheckTxHistoryFn](numSubstitutions)({ case e: TransferTxLogEntry =>
            e.sender.value.amount shouldBe -lockAmount
          }) :+ { case e: BalanceChangeTxLogEntry =>
            e.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          },
          trafficTopups = IgnoreTopupsDevNet,
          ignore = isWalletAutomationBatch,
        ).headOption.map(_.eventId)
      }

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

      clue("The proposer's wallet history shows each returned collateral") {
        checkTxHistory(
          aliceWalletClient,
          Seq.fill[CheckTxHistoryFn](numSubstitutions)({ case e: BalanceChangeTxLogEntry =>
            e.amount shouldBe lockAmount
          }),
          previousEventId = beforeExpiry,
          trafficTopups = IgnoreTopupsDevNet,
          ignore = isWalletAutomationBatch,
        )
      }
  }

  "a received lock is merged with the receiver's own lock when transferred on, and the final receiver can unlock and withdraw it" in {
    implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val charlieParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val alice = RichPartyId.local(aliceParty)
      val bob = RichPartyId.local(bobParty)
      val charlie = RichPartyId.local(charlieParty)
      val aliceParticipant = aliceValidatorBackend.participantClientWithAdminToken
      val bobParticipant = bobValidatorBackend.participantClientWithAdminToken
      val tapAmount = BigDecimal(100000)
      val initialAliceLockAmount = BigDecimal(80000)
      val initialBobLockAmount = BigDecimal(30000)
      val lockTransferAmount = BigDecimal(50000)
      val onwardTransferAmount = BigDecimal(60000)

      aliceWalletClient.tap(walletAmuletToUsd(tapAmount))
      bobWalletClient.tap(walletAmuletToUsd(tapAmount))
      tsv1Operations.createGovernanceLock(
        aliceParticipant,
        alice,
        lockKind,
        lockSubject,
        initialAliceLockAmount,
      )
      tsv1Operations.createGovernanceLock(
        bobParticipant,
        bob,
        lockKind,
        lockSubject,
        initialBobLockAmount,
      )
      val (aliceLockCid, aliceLock) = governanceLocks(aliceParticipant, aliceParty).loneElement

      val aliceToBobCid = tsv1Operations.transferGovernanceLock(
        aliceParticipant,
        alice,
        lockKind,
        lockSubject,
        bobParty,
        transferAmount = lockTransferAmount,
      )

      clue("bob sees the proposal as an incoming transfer of the transferred amount") {
        eventually() {
          val (_, view) = listTransferInstructions(bobParticipant, bobParty)
            .filter(_._1 == aliceToBobCid)
            .loneElement
          view.transfer.sender shouldBe aliceParty.toProtoPrimitive
          view.transfer.receiver shouldBe bobParty.toProtoPrimitive
          BigDecimal(view.transfer.amount) shouldBe lockTransferAmount
          compareLockMemo(
            view.transfer.meta.values.get(TokenStandardMetadata.reasonMetaKey),
            makeAcceptTransferLockMemo(lockKind, lockSubject),
          )
        }
      }

      clue("Scan's accept context discloses alice's lock") {
        sv1ScanBackend
          .getTransferInstructionAcceptContext(aliceToBobCid)
          .disclosedContracts
          .map(_.getContractId) should contain(aliceLockCid.contractId)
      }

      val (_, bobLocks) = actAndCheck(
        "bob accepts the transfer on his own participant",
        acceptTransferInstructionV1(bobParticipant, bob, aliceToBobCid),
      )(
        "alice keeps the remainder, and bob owns a new lock next to his own",
        _ => {
          governanceLocks(aliceParticipant, aliceParty)
            .map(lock => BigDecimal(lock._2.amount)) shouldBe Seq(
            initialAliceLockAmount - lockTransferAmount
          )
          val bobLocks = governanceLocks(bobParticipant, bobParty)
          bobLocks.map(lock => BigDecimal(lock._2.amount)) should contain theSameElementsAs
            Seq(initialBobLockAmount, lockTransferAmount)
          val (_, receivedLock) =
            bobLocks.filter(lock => BigDecimal(lock._2.amount) == lockTransferAmount).loneElement
          receivedLock.specification shouldBe aliceLock.specification
          receivedLock.originalLockCid.toScala.map(_.contractId) shouldBe
            Some(aliceToBobCid.contractId)
          aliceWalletClient.balance().lockedQty shouldBe initialAliceLockAmount - lockTransferAmount
          bobWalletClient.balance().lockedQty shouldBe initialBobLockAmount + lockTransferAmount
          bobLocks
        },
      )

      val checkAccept: CheckTxHistoryFn = { case entry: TransferTxLogEntry =>
        entry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.TransferInstruction_Accept.toProto
        entry.sender.value shouldBe PartyAndAmount(aliceParty.toProtoPrimitive, BigDecimal(0))
        entry.receivers shouldBe Seq(PartyAndAmount(bobParty.toProtoPrimitive, BigDecimal(0)))
      }

      val checkPropose: CheckTxHistoryFn = { case entry: TransferTxLogEntry =>
        entry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
        entry.sender.value shouldBe PartyAndAmount(aliceParty.toProtoPrimitive, BigDecimal(0))
        entry.receivers shouldBe empty
        entry.transferInstructionReceiver shouldBe
          GovernanceLockUtil.lockManagerParty.toProtoPrimitive
        entry.transferInstructionAmount shouldBe Some(BigDecimal(0))
        compareLockMemo(
          entry.description,
          makeInputLockMemo(TransferLock, lockKind, lockSubject)(
            (Locked, bobParty, lockTransferAmount, None, None)
          ),
        )
      }
      checkTxHistory(
        aliceWalletClient,
        Seq(checkAccept, checkPropose),
        ignore = isUnrelatedTo(aliceToBobCid),
      )
      checkTxHistory(bobWalletClient, Seq(checkAccept), ignore = isUnrelatedTo(aliceToBobCid))

      // Neither of bob's locks alone covers the transferred amount
      val bobToCharlieCid = tsv1Operations.transferGovernanceLock(
        bobParticipant,
        bob,
        lockKind,
        lockSubject,
        charlieParty,
        transferAmount = onwardTransferAmount,
      )

      clue("Scan's accept context discloses both of bob's locks") {
        sv1ScanBackend
          .getTransferInstructionAcceptContext(bobToCharlieCid)
          .disclosedContracts
          .map(_.getContractId) should contain allElementsOf bobLocks.map(_._1.contractId)
      }

      actAndCheck(
        "charlie accepts the transfer on a participant that does not host bob",
        acceptTransferInstructionV1(aliceParticipant, charlie, bobToCharlieCid),
      )(
        "bob's locks are merged into the remainder, and charlie owns the transferred amount",
        _ => {
          governanceLocks(bobParticipant, bobParty)
            .map(lock => BigDecimal(lock._2.amount)) shouldBe Seq(
            initialBobLockAmount + lockTransferAmount - onwardTransferAmount
          )
          val (_, charlieLock) = governanceLocks(aliceParticipant, charlieParty).loneElement
          BigDecimal(charlieLock.amount) shouldBe onwardTransferAmount
        },
      )

      val vestingStartTime = truncateInstant(getLedgerTime.plusSeconds(1).toInstant)
      tsv1Operations
        .unlockGovernanceLock(
          aliceParticipant,
          charlie,
          lockKind,
          lockSubject,
          unlockAmount = onwardTransferAmount,
          vestingStartTime = vestingStartTime,
          // The lineage of charlie's lock starts at the accepted proposal
          isValid = _.originalInstructionCid.toScala.exists(
            _.contractId == bobToCharlieCid.contractId
          ),
        )
        .value
      val charlieVestingLock = governanceLocks(aliceParticipant, charlieParty).loneElement._2

      advanceTime(vestingDuration.plusSeconds(1))

      actAndCheck(
        "charlie withdraws the fully vested lock",
        tsv1Operations.withdrawVestingLock(
          aliceParticipant,
          charlie,
          lockKind,
          lockSubject,
          charlieVestingLock,
          vestedUntilTime = vestingStartTime.plus(vestingDuration),
        ),
      )(
        "charlie has no lock left",
        _ => governanceLocks(aliceParticipant, charlieParty) shouldBe empty,
      )
  }

  "rejected and withdrawn transfers leave the lock unchanged, and a vesting lock can be transferred" in {
    implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val alice = RichPartyId.local(aliceParty)
      val bob = RichPartyId.local(bobParty)
      val aliceParticipant = aliceValidatorBackend.participantClientWithAdminToken
      val bobParticipant = bobValidatorBackend.participantClientWithAdminToken
      val tapAmount = BigDecimal(100000)
      val initialLockAmount = BigDecimal(40000)
      val vestingTransferAmount = BigDecimal(10000)

      aliceWalletClient.tap(walletAmuletToUsd(tapAmount))
      val lockCid = tsv1Operations.createGovernanceLock(
        aliceParticipant,
        alice,
        lockKind,
        lockSubject,
        initialLockAmount,
      )

      def proposeFullTransfer() = tsv1Operations.transferGovernanceLock(
        aliceParticipant,
        alice,
        lockKind,
        lockSubject,
        bobParty,
        transferAmount = initialLockAmount,
      )

      def checkClosedAndLockUnchanged(
          proposalCid: transferinstructionv1.TransferInstruction.ContractId
      ) = {
        listTransferInstructions(aliceParticipant, aliceParty).map(_._1) should not contain
          proposalCid
        governanceLocks(aliceParticipant, aliceParty).map(_._1.contractId) shouldBe
          Seq(lockCid.contractId)
        governanceLocks(bobParticipant, bobParty) shouldBe empty
      }

      val rejectedCid = proposeFullTransfer()
      actAndCheck(
        "bob rejects the transfer",
        rejectTransferInstructionV1(bobParticipant, bob, rejectedCid),
      )(
        "the proposal is archived and alice's lock is unchanged",
        _ => checkClosedAndLockUnchanged(rejectedCid),
      )

      val withdrawnCid = proposeFullTransfer()
      actAndCheck(
        "alice withdraws the transfer",
        withdrawTransferInstructionV1(aliceParticipant, alice, withdrawnCid),
      )(
        "the proposal is archived and alice's lock is unchanged",
        _ => checkClosedAndLockUnchanged(withdrawnCid),
      )

      val (vestingLockCid, _) = tsv1Operations
        .unlockGovernanceLock(
          aliceParticipant,
          alice,
          lockKind,
          lockSubject,
          unlockAmount = initialLockAmount,
          vestingStartTime = truncateInstant(getLedgerTime.plusSeconds(1).toInstant),
          isValid = _.originalInstructionCid.toScala.exists(
            _.contractId == lockCid.contractId
          ),
        )
        .value
      val (_, vestingLock) = governanceLocks(aliceParticipant, aliceParty).loneElement

      val vestingTransferCid = tsv1Operations.transferVestingLock(vestingLock)(
        aliceParticipant,
        alice,
        lockKind,
        lockSubject,
        bobParty,
        transferAmount = vestingTransferAmount,
      )

      actAndCheck(
        "bob accepts the transfer of part of the vesting lock",
        acceptTransferInstructionV1(bobParticipant, bob, vestingTransferCid),
      )(
        "alice and bob own vesting locks with the same vesting schedule",
        _ => {
          val (aliceRemainderCid, aliceRemainder) =
            governanceLocks(aliceParticipant, aliceParty).loneElement
          aliceRemainderCid.contractId should not be vestingLockCid.contractId
          BigDecimal(aliceRemainder.amount) shouldBe initialLockAmount - vestingTransferAmount
          aliceRemainder.specification shouldBe vestingLock.specification
          val (_, bobLock) = governanceLocks(bobParticipant, bobParty).loneElement
          BigDecimal(bobLock.amount) shouldBe vestingTransferAmount
          bobLock.specification shouldBe vestingLock.specification
        },
      )
  }

  private def governanceLocks(
      participant: ParticipantClientReference,
      owner: PartyId,
  ): Seq[(governancelock.GovernanceLock.ContractId, governancelock.GovernanceLock)] =
    participant.ledger_api_extensions.acs
      .filterJava(governancelock.GovernanceLock.COMPANION)(
        owner,
        _.data.owner == owner.toProtoPrimitive,
      )
      .map(lock => (lock.id, lock.data))

  /** Ignores all wallet history entries that are not about the given transfer instruction */
  private def isUnrelatedTo(
      instructionCid: transferinstructionv1.TransferInstruction.ContractId
  ): TxLogEntry => Boolean = {
    case entry: TransferTxLogEntry => entry.transferInstructionCid != instructionCid.contractId
    case _ => true
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
