// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.AnyContract
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock.{
  ControllerSpecification,
  GovernanceLock,
  GovernanceLockKind,
  GovernanceLockSpecification,
  VestingLock,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock.governancelock_unlockresult.{
  GovernanceLock_UnlockResult_Completed,
  GovernanceLock_UnlockResult_Pending,
}
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Test helpers for integration tests to control governance locks using
  * production choices rather than constructing contracts directly.
  */
trait GovernanceLockTestUtil extends TestCommon { this: HasExecutionContext =>

  /** The one-entry choice context that governance-lock choices expect.
    *
    * Govenance lock implementations fail the transaction unless in the
    * `unfeaturedPaymentContextAndConfigFromChoiceContext` function unless the
    * context carries the `external-party-config-state` key.
    *
    * No disclosures are returned in this context because disclosures are not
    * needed as longn as the submission runs on the participant hosting the DSO
    * party with `readAs = dso`. The governance lock choices' contracts are
    * signed by DSO.
    */
  def getGovernanceLockContext(implicit
      env: SpliceTestConsoleEnvironment
  ): ChoiceContextWithDisclosures = {
    val configState = sv1Backend.appState.dsoStore
      .lookupLatestExternalPartyConfigState()
      .futureValue
      .value
    ChoiceContextWithDisclosures(
      disclosedContracts = Seq.empty,
      choiceContext = new metadatav1.ChoiceContext(
        Map[String, metadatav1.AnyValue](
          "external-party-config-state" -> new metadatav1.anyvalue.AV_ContractId(
            new AnyContract.ContractId(configState.contractId.contractId)
          )
        ).asJava
      ),
    )
  }

  /** The unlocked `Amulet`s of an `owner`.
    *
    * Used as the `inputs` for a choice that locks amulet for governance.
    */
  def listUnlockedHoldingCids(
      participantClient: ParticipantClientReference,
      owner: PartyId,
  ): Seq[holdingv1.Holding.ContractId] =
    participantClient.ledger_api_extensions.acs
      .filterJava(Amulet.COMPANION)(owner)
      .map(amulet => new holdingv1.Holding.ContractId(amulet.id.contractId))

  /** A specification where `owner` alone controls unlock, substitution and
    * vesting.
    */
  def ownerControlledSpecification(
      owner: PartyId,
      kind: GovernanceLockKind,
  ): GovernanceLockSpecification = {
    val controllers = new ControllerSpecification(
      List(List(owner.toProtoPrimitive).asJava).asJava
    )
    new GovernanceLockSpecification(kind, controllers, controllers, controllers)
  }

  /** Executes a choice to "lock" amulet for governance.
    *
    * Locks `amount` out of all of `owner`'s unlocked holdings. The remainder
    * comes back as a change `Amulet`.
    */
  def lockForGovernance(
      participantClient: ParticipantClientReference,
      userId: String,
      owner: PartyId,
      amount: BigDecimal,
      specification: GovernanceLockSpecification,
  )(implicit env: SpliceTestConsoleEnvironment): GovernanceLock.ContractId = {
    val rules = sv1ScanBackend.getExternalPartyAmuletRules()
    val inputs = listUnlockedHoldingCids(participantClient, owner)
    participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(owner),
        readAs = Seq(dsoParty),
        update = rules.contractId.exerciseExternalPartyAmuletRules_LockForGovernance(
          amount.bigDecimal,
          inputs.asJava,
          specification,
          getGovernanceLockContext.toExtraArgs(),
          owner.toProtoPrimitive,
          participantClient.ledger_api.time.get().toInstant,
          new metadatav1.Metadata(java.util.Map.of()),
        ),
      )
      .exerciseResult
      .governanceLock
  }

  /** Executes a choice to "unlock" a governance lock.
    *
    * Normally a vesting lock gets created as a result of the choice execution
    *  that releases amulet at linear schedule over a vesting period.
    *
    * `unlockAmount = None` unlocks the whole lock (no split, no continuing `GovernanceLock`).
    * `unlockAt` must be strictly in the future; the resulting `VestingLock.endTime` is
    * `unlockAt + vestingDurationFor specification.kind`, read off the `ExternalPartyConfigState`.
    *
    * `None` vesting lock is created and returned if this `GovernanceLock` was
    * representing a provisional featured app lock---vesting duration is 0.
    *
    * `None` governance lock is returned if it was unlocked fully.
    */
  def unlockGovernanceLock(
      participantClient: ParticipantClientReference,
      userId: String,
      lock: GovernanceLock.ContractId,
      unlockAmount: Option[BigDecimal],
      unlockAt: Instant,
      actors: Seq[PartyId],
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): (Option[VestingLock.ContractId], Option[GovernanceLock.ContractId]) = {
    val result = participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = actors,
        readAs = Seq(dsoParty),
        update = lock.exerciseGovernanceLock_Unlock(
          unlockAmount.map(_.bigDecimal).toJava,
          unlockAt,
          getGovernanceLockContext.toExtraArgs(),
          actors.map(_.toProtoPrimitive).asJava,
        ),
      )
      .exerciseResult
    result match {
      case completed: GovernanceLock_UnlockResult_Completed =>
        (completed.vestingLock.toScala, completed.governanceLock.toScala)
      case pending: GovernanceLock_UnlockResult_Pending =>
        fail(s"Unlock by $actors is still pending further approvals on ${pending.pendingLock}")
      case other => fail(s"Unexpected GovernanceLock_Unlock result: $other")
    }
  }
}
