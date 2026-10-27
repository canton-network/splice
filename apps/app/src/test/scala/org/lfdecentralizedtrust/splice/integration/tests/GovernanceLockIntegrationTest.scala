// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{GovernanceLockUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.store.{BalanceChangeTxLogEntry, TransferTxLogEntry}

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class GovernanceLockIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with WalletTxLogTestUtil
    with GovernanceLockTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  private val lockKind = SuperValidatorLock
  private val lockSubject = "sv1"

  private val targetLockAmount = BigDecimal(20000)
  private val partialSubstitutionAmount = BigDecimal(10000)

  private def aliceParticipant(implicit env: SpliceTestConsoleEnvironment) =
    aliceValidatorBackend.participantClientWithAdminToken

  private def bobParticipant(implicit env: SpliceTestConsoleEnvironment) =
    bobValidatorBackend.participantClientWithAdminToken

  private def governanceLocksFor(participant: ParticipantClientReference, owner: PartyId) =
    participant.ledger_api_extensions.acs
      .filterJava(governancelock.GovernanceLock.COMPANION)(
        owner,
        predicate = c => c.data.owner == owner.toProtoPrimitive && !isVestingLock(c.data),
      )
      .map(_.data)

  private def vestingLocksFor(participant: ParticipantClientReference, owner: PartyId) =
    participant.ledger_api_extensions.acs
      .filterJava(governancelock.GovernanceLock.COMPANION)(
        owner,
        predicate = c => c.data.owner == owner.toProtoPrimitive && isVestingLock(c.data),
      )
      .map(_.data)

  private def latestEventId(wallet: WalletAppClientReference): Option[String] =
    wallet.listTransactions(None, pageSize = 1).headOption.map(_.eventId)

  /** Checks that the only new wallet history entry is a transfer with the given sender and receiver amounts. */
  private def checkTransferHistory(
      wallet: WalletAppClientReference,
      previousEventId: Option[String],
      sender: (PartyId, BigDecimal),
      receivers: Seq[(PartyId, BigDecimal)],
  ): Unit =
    checkTxHistory(
      wallet,
      Seq({ case entry: TransferTxLogEntry =>
        entry.sender.value.party shouldBe sender._1.toProtoPrimitive
        entry.sender.value.amount shouldBe sender._2
        entry.receivers.map(r => (r.party, r.amount)) shouldBe
          receivers.map { case (party, amount) => (party.toProtoPrimitive, amount) }
      }),
      previousEventId = previousEventId,
      trafficTopups = IgnoreTopupsDevNet,
    )

  /** Checks that the only new wallet history entry is a balance change of `amount`. */
  private def checkBalanceChangeHistory(
      wallet: WalletAppClientReference,
      previousEventId: Option[String],
      amount: BigDecimal,
  ): Unit =
    checkTxHistory(
      wallet,
      Seq({ case entry: BalanceChangeTxLogEntry =>
        entry.amount shouldBe amount
      }),
      previousEventId = previousEventId,
      trafficTopups = IgnoreTopupsDevNet,
    )

  private def testTokenStandardCompat[CId <: ContractId[?], View](
      ops: TokenStandardOps[CId, View]
  ) = {
    // alice locks `targetLockAmount`, and bob has `bobFunds` of unlocked funds
    def setup(bobFunds: BigDecimal)(implicit env: SpliceTestConsoleEnvironment) = {
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val alice = RichPartyId.local(aliceParty)
      val bob = RichPartyId.local(bobParty)

      aliceWalletClient.tap(targetLockAmount)
      bobWalletClient.tap(bobFunds)

      ops.createGovernanceLock(
        aliceParticipant,
        alice,
        lockKind,
        lockSubject,
        amount = targetLockAmount,
      )
      val targetLock = governanceLocksFor(aliceParticipant, aliceParty).loneElement
      eventually() {
        aliceWalletClient.balance().lockedQty shouldBe targetLockAmount
        bobWalletClient.balance().unlockedQty should be > BigDecimal(0)
      }

      (alice, bob, targetLock)
    }

    def checkSubstitutionView(
        participant: ParticipantClientReference,
        proposer: PartyId,
        substitutionCid: CId,
        targetOwner: PartyId,
        amount: BigDecimal,
    ) = {
      val (_, view) = ops
        .listTransfers(participant, proposer)
        .filter(_._1.contractId == substitutionCid.contractId)
        .loneElement
      ops.transferValues.sender(view) shouldBe proposer.toProtoPrimitive
      ops.transferValues.receiver(view) shouldBe targetOwner.toProtoPrimitive
      ops.transferValues.amount(view) shouldBe amount
    }

    s"A GovernanceLock substitution created via TSv${ops.version} can be accepted by the owner of the target lock" in {
      implicit env =>
        val (alice, bob, targetLock) = setup(bobFunds = partialSubstitutionAmount)
        val bobBeforeCreate = latestEventId(bobWalletClient)

        val substitutionCid = ops.substituteGovernanceLock(targetLock)(
          bobParticipant,
          bob,
          lockKind,
          lockSubject,
          partialSubstitutionAmount,
        )

        clue("the substitution is a pending TransferInstruction from bob to alice") {
          checkSubstitutionView(
            bobParticipant,
            bob.partyId,
            substitutionCid,
            alice.partyId,
            partialSubstitutionAmount,
          )
        }

        clue("bob's wallet history shows the funds he locked for the substitution") {
          checkTransferHistory(
            bobWalletClient,
            bobBeforeCreate,
            sender = (bob.partyId, -partialSubstitutionAmount),
            receivers = Seq.empty,
          )
        }

        clue("Scan serves accept and withdraw choice contexts for the substitution") {
          ops
            .getAcceptContext(substitutionCid)
            .disclosedContracts should not be empty
          ops
            .getWithdrawContext(substitutionCid)
            .disclosedContracts should not be empty
        }

        val aliceUnlockedBefore = aliceWalletClient.balance().unlockedQty
        val aliceBeforeAccept = latestEventId(aliceWalletClient)
        val bobBeforeAccept = latestEventId(bobWalletClient)

        actAndCheck(
          "alice accepts the substitution",
          ops.acceptTransfer(aliceParticipant, alice, substitutionCid),
        )(
          "bob owns the substituted lock and alice keeps the remainder",
          _ => {
            // The substitution is gone; bob's only instruction is his new lock
            ops
              .listTransfers(bobParticipant, bob.partyId)
              .map(t => ops.transferValues.receiver(t._2)) shouldBe Seq(
              GovernanceLockUtil.lockManagerParty.toProtoPrimitive
            )

            val bobLock = governanceLocksFor(bobParticipant, bob.partyId).loneElement
            BigDecimal(bobLock.amount) shouldBe partialSubstitutionAmount
            GovernanceLockUtil.getGovernanceLockKind(bobLock) shouldBe
              GovernanceLockUtil.getGovernanceLockKind(targetLock)

            val aliceLock = governanceLocksFor(aliceParticipant, alice.partyId).loneElement
            BigDecimal(aliceLock.amount) shouldBe targetLockAmount - partialSubstitutionAmount

            aliceWalletClient
              .balance()
              .unlockedQty shouldBe aliceUnlockedBefore + partialSubstitutionAmount
          },
        )

        clue("the wallet histories show the substituted funds returned to alice") {
          val expectedSender = (bob.partyId, BigDecimal(0))
          val expectedReceivers = Seq((alice.partyId, partialSubstitutionAmount))
          checkTransferHistory(
            aliceWalletClient,
            aliceBeforeAccept,
            expectedSender,
            expectedReceivers,
          )
          checkTransferHistory(bobWalletClient, bobBeforeAccept, expectedSender, expectedReceivers)
        }
    }

    s"A GovernanceLock substitution created via TSv${ops.version} can be withdrawn by the proposer" in {
      implicit env =>
        val (alice, bob, targetLock) = setup(bobFunds = partialSubstitutionAmount)
        val bobUnlockedBefore = bobWalletClient.balance().unlockedQty

        val substitutionCid = ops.substituteGovernanceLock(targetLock)(
          bobParticipant,
          bob,
          lockKind,
          lockSubject,
          partialSubstitutionAmount,
        )

        clue("bob's funds are locked in the substitution") {
          eventually()(bobWalletClient.balance().lockedQty shouldBe partialSubstitutionAmount)
        }

        val aliceBeforeWithdraw = latestEventId(aliceWalletClient)
        val bobBeforeWithdraw = latestEventId(bobWalletClient)

        actAndCheck(
          "bob withdraws the substitution",
          ops.withdrawTransfer(bobParticipant, bob, substitutionCid),
        )(
          "the substitution is gone, bob's funds are unlocked and alice's lock is unchanged",
          _ => {
            ops.listTransfers(bobParticipant, bob.partyId) shouldBe empty
            bobWalletClient.balance().unlockedQty shouldBe bobUnlockedBefore
            governanceLocksFor(bobParticipant, bob.partyId) shouldBe empty
            BigDecimal(
              governanceLocksFor(aliceParticipant, alice.partyId).loneElement.amount
            ) shouldBe targetLockAmount
          },
        )

        clue("the wallet histories show bob's funds unlocked") {
          checkBalanceChangeHistory(bobWalletClient, bobBeforeWithdraw, partialSubstitutionAmount)
          checkBalanceChangeHistory(aliceWalletClient, aliceBeforeWithdraw, BigDecimal(0))
        }
    }

    s"A GovernanceLock substitution created via TSv${ops.version} can be rejected by the owner of the target lock" in {
      implicit env =>
        val (alice, bob, targetLock) = setup(bobFunds = partialSubstitutionAmount)
        val bobUnlockedBefore = bobWalletClient.balance().unlockedQty

        val substitutionCid = ops.substituteGovernanceLock(targetLock)(
          bobParticipant,
          bob,
          lockKind,
          lockSubject,
          partialSubstitutionAmount,
        )

        clue("Scan serves a reject choice context for the substitution") {
          ops
            .getRejectContext(substitutionCid)
            .disclosedContracts should not be empty
        }

        eventually()(bobWalletClient.balance().lockedQty shouldBe partialSubstitutionAmount)
        val aliceBeforeReject = latestEventId(aliceWalletClient)
        val bobBeforeReject = latestEventId(bobWalletClient)

        actAndCheck(
          "alice rejects the substitution",
          ops.rejectTransfer(aliceParticipant, alice, substitutionCid),
        )(
          "the substitution is gone, bob's funds are unlocked and alice's lock is unchanged",
          _ => {
            ops.listTransfers(bobParticipant, bob.partyId) shouldBe empty
            bobWalletClient.balance().unlockedQty shouldBe bobUnlockedBefore
            governanceLocksFor(bobParticipant, bob.partyId) shouldBe empty
            BigDecimal(
              governanceLocksFor(aliceParticipant, alice.partyId).loneElement.amount
            ) shouldBe targetLockAmount
          },
        )

        clue("the wallet histories show bob's funds unlocked") {
          checkBalanceChangeHistory(bobWalletClient, bobBeforeReject, partialSubstitutionAmount)
          checkBalanceChangeHistory(aliceWalletClient, aliceBeforeReject, BigDecimal(0))
        }
    }

    s"A vesting GovernanceLock substitution created via TSv${ops.version} can be accepted by the owner of the target lock" in {
      implicit env =>
        val (alice, bob, _) = setup(bobFunds = targetLockAmount)

        // The default vesting period of SV locks is about a year, so the lock stays vesting for the
        // rest of the test.
        val (vestingLockCid, _) = ops
          .unlockGovernanceLock(
            aliceParticipant,
            alice,
            lockKind,
            lockSubject,
            unlockAmount = targetLockAmount,
            vestingStartTime = env.environment.clock.now.plusSeconds(30).toInstant,
          )
          .value
        val vestingLock = vestingLocksFor(aliceParticipant, alice.partyId).loneElement

        val substitutionCid = ops.substituteVestingLock(vestingLock)(
          bobParticipant,
          bob,
          lockKind,
          lockSubject,
          BigDecimal(vestingLock.amount),
        )

        clue("the substitution is a pending TransferInstruction from bob to alice") {
          substitutionCid.contractId should not be vestingLockCid.contractId
          checkSubstitutionView(
            bobParticipant,
            bob.partyId,
            substitutionCid,
            alice.partyId,
            BigDecimal(vestingLock.amount),
          )
        }

        // Wait for the wallets to ingest the unlock and the substitution
        val aliceBeforeAccept = eventually() {
          val latest = aliceWalletClient.listTransactions(None, pageSize = 1).loneElement
          inside(latest) { case entry: TransferTxLogEntry =>
            entry.transferInstructionCid shouldBe vestingLockCid.contractId
          }
          Some(latest.eventId)
        }
        eventually()(bobWalletClient.balance().lockedQty shouldBe BigDecimal(vestingLock.amount))
        val bobBeforeAccept = latestEventId(bobWalletClient)
        val aliceUnlockedBefore = aliceWalletClient.balance().unlockedQty

        actAndCheck(
          "alice accepts the substitution",
          ops.acceptTransfer(aliceParticipant, alice, substitutionCid),
        )(
          "bob owns a GovernanceLock with the same vesting schedule and alice has none",
          _ => {
            // The substitution is gone; bob's only instruction is his new lock
            ops
              .listTransfers(bobParticipant, bob.partyId)
              .map(t => ops.transferValues.receiver(t._2)) shouldBe Seq(
              GovernanceLockUtil.lockManagerParty.toProtoPrimitive
            )

            val bobLock = vestingLocksFor(bobParticipant, bob.partyId).loneElement
            BigDecimal(bobLock.amount) shouldBe BigDecimal(vestingLock.amount)
            vestingLockSpecification(bobLock).vestingPeriod shouldBe
              vestingLockSpecification(vestingLock).vestingPeriod
            vestingLockSpecification(bobLock).endTime shouldBe
              vestingLockSpecification(vestingLock).endTime
            GovernanceLockUtil.getGovernanceLockKind(bobLock) shouldBe
              GovernanceLockUtil.getGovernanceLockKind(vestingLock)

            vestingLocksFor(aliceParticipant, alice.partyId) shouldBe empty
            aliceWalletClient
              .balance()
              .unlockedQty shouldBe aliceUnlockedBefore + BigDecimal(vestingLock.amount)
          },
        )

        clue("the wallet histories show the substituted funds returned to alice") {
          val expectedSender = (bob.partyId, BigDecimal(0))
          val expectedReceivers = Seq((alice.partyId, BigDecimal(vestingLock.amount)))
          checkTransferHistory(
            aliceWalletClient,
            aliceBeforeAccept,
            expectedSender,
            expectedReceivers,
          )
          checkTransferHistory(bobWalletClient, bobBeforeAccept, expectedSender, expectedReceivers)
        }
    }
  }

  testTokenStandardCompat(tsv1Operations)
  testTokenStandardCompat(tsv2Operations)
}
