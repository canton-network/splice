// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.{
  InputAmulet,
  InputAppRewardCoupon,
  InputDevelopmentFundCoupon,
  InputRewardCouponV2,
  InputUnclaimedActivityRecord,
  InputValidatorLivenessActivityRecord,
  InputValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  Amulet,
  DevelopmentFundCoupon,
  RewardCouponV2,
  UnclaimedActivityRecord,
  ValidatorRewardCoupon,
  ValidatorRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.rewardassignmentv1.{
  RewardBeneficiary,
  RewardCoupon,
  RewardCoupon_AssignBeneficiaries,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLivenessActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation.MintingDelegation
import org.lfdecentralizedtrust.splice.environment.{RetryFor, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.HardLimit
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  ChoiceContextWithDisclosures,
  Contract,
  ContractWithState,
  DisclosedContracts,
  SpliceUtil,
}
import org.lfdecentralizedtrust.splice.wallet.config.RewardSharingConfig
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// Although this trigger is part of external-party automation
// The work performed here is done as the delegate of the MintingDelegation contract
class MintingDelegationCollectRewardsTrigger(
    override protected val context: TriggerContext,
    store: ExternalPartyWalletStore,
    scanConnection: BftScanConnection,
    spliceLedgerConnection: SpliceLedgerConnection,
    rewardSharingConfig: RewardSharingConfig,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    materializer: Materializer,
) extends PollingTrigger {

  private def externalParty = store.key.externalParty

  override protected def extraMetricLabels = Seq("party" -> externalParty.toString)

  override def isRewardOperationTrigger: Boolean = true

  override def performWorkIfAvailable()(implicit tc: TraceContext): Future[Boolean] = {
    context.retryProvider.retry(
      RetryFor.Automation,
      "collect_rewards_as_delegate",
      "Collect rewards as delegate for the minting delegation",
      collectRewardsAsDelegate(),
      logger,
    )
  }

  private def collectRewardsAsDelegate()(implicit tc: TraceContext): Future[Boolean] = {
    for {
      delegations <- store.multiDomainAcsStore.listContracts(
        MintingDelegation.COMPANION,
        store.defaultLimit,
      )

      // In the steady state there is at most one active delegation per beneficiary.
      // Thus if there are multiple ones, we can just pick one of them.
      result <- delegations.flatMap(_.toAssignedContract).headOption match {
        case Some(delegation) => processDelegation(delegation)
        case None => Future.successful(false)
      }
    } yield result
  }

  private def processDelegation(
      assignedDelegation: AssignedContract[MintingDelegation.ContractId, MintingDelegation]
  )(implicit tc: TraceContext): Future[Boolean] = {
    val delegation = assignedDelegation.contract
    val delegateParty = PartyId.tryFromProtoPrimitive(delegation.payload.delegate)

    // Confirm that delegate is an active local party, else ignore
    spliceLedgerConnection.getParty(delegateParty).flatMap {
      case Some(partyDetails) if partyDetails.isLocal =>
        val now = context.clock.now.toInstant
        if (delegation.payload.expiresAt.isBefore(now)) {
          logger.info(
            s"Skipping reward collection for expired minting delegation (expired at ${delegation.payload.expiresAt})"
          )
          Future.successful(false)
        } else {
          for {
            (openRound, openIssuingRounds, issuingRoundsMap, amuletRules) <- fetchDataFromScan()
            couponsData <- fetchCouponsData(issuingRoundsMap)
            amulets <- store.listAmulets()
            validatorRightOpt <- store.lookupValidatorRight()
            mintInputs = MintInputs(
              delegation = delegation,
              openRound = openRound,
              openIssuingRounds = openIssuingRounds,
              amuletRules = amuletRules,
              maxNumInputs = openRound.payload.transferConfigUsd.maxNumInputs.intValue(),
              validatorRightOpt = validatorRightOpt,
            )
            result <- performMintIfNeeded(mintInputs, couponsData, amulets)
          } yield result
        }
      case _ =>
        Future.successful(false)
    }
  }

  private sealed trait SharingMode
  private case object NoSharing extends SharingMode
  private case object InternalSharing extends SharingMode
  private case object ExternalSharing extends SharingMode

  private def performMintIfNeeded(
      mintInputs: MintInputs,
      couponsData: CouponsData,
      amulets: Seq[Contract[Amulet.ContractId, Amulet]],
  )(implicit tc: TraceContext): Future[Boolean] = {
    // ValidatorRewardCoupons can only be collected if we have the
    // ValidatorRight contract for the beneficiary; drop them otherwise.
    val filteredCouponsData =
      if (mintInputs.validatorRightOpt.isDefined) couponsData
      else couponsData.copy(validatorRewardCoupons = Seq.empty)
    val amuletsToMerge = selectAmuletsToMerge(amulets, mintInputs.delegation)
    val shouldMergeAmulets = amuletsToMerge.nonEmpty

    // Handling of unassigned V2 coupons (no beneficiary yet) depends on the sharing mode:
    //   - No sharing (no beneficiaries, not external): mint them directly to ourselves.
    //   - InternalSharing (beneficiaries set): hold them back, assign to the configured
    //     beneficiaries first, then mint; already-assigned coupons mint directly.
    //   - ExternalSharing (isExternal): hold them back and leave them untouched, so the
    //     off-node automation owns their assignment; only already-assigned coupons mint here.
    val mode: SharingMode =
      if (rewardSharingConfig.isExternal) ExternalSharing
      else if (rewardSharingConfig.beneficiaries.nonEmpty) InternalSharing
      else NoSharing

    val (unassignedV2, mintableV2) = mode match {
      case NoSharing => (Seq.empty, filteredCouponsData.rewardCouponsV2)
      case InternalSharing | ExternalSharing =>
        filteredCouponsData.rewardCouponsV2.partition(_.payload.beneficiary.isEmpty)
    }
    val couponsToMint = filteredCouponsData.copy(rewardCouponsV2 = mintableV2)

    // Share when the TTL threshold is reached, or batch sharing with
    // amulet merging to reduce traffic costs by combining both in one transaction.
    val shouldAssign = mode == InternalSharing && unassignedV2.nonEmpty &&
      (shouldShareNow(unassignedV2, rewardSharingConfig) || shouldMergeAmulets)

    val submission = buildMintSubmissionData(mintInputs, couponsToMint, amuletsToMerge)
    if (shouldAssign) {
      performAssignAndMint(submission, unassignedV2.toList, rewardSharingConfig)
    } else if (couponsToMint.hasRewards || shouldMergeAmulets) {
      performMint(submission)
    } else {
      // Nothing to do: no rewards to mint, coupons to assign, or amulets to merge
      Future.successful(false)
    }
  }

  private def performMint(
      submission: MintSubmissionData
  )(implicit tc: TraceContext): Future[Boolean] = {
    spliceLedgerConnection
      .submit(
        actAs = Seq(submission.delegateParty),
        readAs = Seq(submission.delegateParty, externalParty),
        submission.delegation.contractId
          .exerciseMintingDelegation_Mint(submission.inputs, submission.paymentContext),
      )
      .withDisclosedContracts(submission.contractsToDisclose)
      .noDedup
      .yieldUnit()
      .map { _ =>
        logger.debug(
          show"Minted ${submission.couponsData} and merged ${submission.amuletsToMerge.size} amulets for delegation ${PrettyContractId(submission.delegation)}"
        )
        true
      }
  }

  private def performAssignAndMint(
      submission: MintSubmissionData,
      unassignedV2: List[Contract[RewardCouponV2.ContractId, RewardCouponV2]],
      config: RewardSharingConfig,
  )(implicit tc: TraceContext): Future[Boolean] = {
    unassignedV2 match {
      case Nil =>
        Future.failed(
          Status.INTERNAL
            .withDescription(s"No unassigned RewardCouponV2 contracts to assign for $externalParty")
            .asRuntimeException()
        )
      case primaryCoupon :: additionalCoupons =>
        val newBeneficiaries = config.allDamlBeneficiaries(externalParty).map { case (party, pct) =>
          new RewardBeneficiary(party.toProtoPrimitive, pct)
        }
        val assignArgs = new RewardCoupon_AssignBeneficiaries(
          additionalCoupons
            .map(_.contractId.toInterface(RewardCoupon.INTERFACE))
            .asJava,
          newBeneficiaries.asJava,
          ChoiceContextWithDisclosures.emptyExtraArgs,
        )
        val couponCid = primaryCoupon.contractId.toInterface(RewardCoupon.INTERFACE)

        spliceLedgerConnection
          .submit(
            actAs = Seq(submission.delegateParty),
            readAs = Seq(submission.delegateParty, externalParty),
            submission.delegation.contractId.exerciseMintingDelegation_AssignAndMint(
              couponCid,
              assignArgs,
              submission.inputs,
              submission.paymentContext,
            ),
          )
          .withDisclosedContracts(submission.contractsToDisclose)
          .noDedup
          .yieldUnit()
          .map { _ =>
            logger.debug(
              show"Assigned ${unassignedV2.size} V2 coupons to ${newBeneficiaries.size} beneficiaries, minted ${submission.couponsData} and merged ${submission.amuletsToMerge.size} amulets for delegation ${PrettyContractId(submission.delegation)}"
            )
            true
          }
    }
  }

  // Helper APIs
  private def fetchDataFromScan()(implicit tc: TraceContext): Future[
    (
        ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        Map[Round, IssuingMiningRound],
        ContractWithState[AmuletRules.ContractId, AmuletRules],
    )
  ] = {
    for {
      (openRounds, issuingRounds) <- scanConnection.getOpenAndIssuingMiningRounds()
      amuletRules <- scanConnection.getAmuletRulesWithState()
    } yield {
      val now = context.clock.now
      val openRound = SpliceUtil.selectLatestOpenMiningRound(now, openRounds)
      val openIssuingRounds = issuingRounds.filter(c => c.payload.opensAt.isBefore(now.toInstant))
      val issuingRoundsMap = openIssuingRounds.view.map { r =>
        val imr = r.payload
        (imr.round, imr)
      }.toMap
      (openRound, openIssuingRounds, issuingRoundsMap, amuletRules)
    }
  }

  private case class CouponsData(
      livenessActivityRecords: Seq[Contract[
        ValidatorLivenessActivityRecord.ContractId,
        ValidatorLivenessActivityRecord,
      ]],
      validatorRewardCoupons: Seq[Contract[
        ValidatorRewardCoupon.ContractId,
        ValidatorRewardCoupon,
      ]],
      appRewardCoupons: Seq[Contract[
        AppRewardCoupon.ContractId,
        AppRewardCoupon,
      ]],
      unclaimedActivityRecords: Seq[Contract[
        UnclaimedActivityRecord.ContractId,
        UnclaimedActivityRecord,
      ]],
      developmentFundCoupons: Seq[Contract[
        DevelopmentFundCoupon.ContractId,
        DevelopmentFundCoupon,
      ]],
      rewardCouponsV2: Seq[Contract[
        RewardCouponV2.ContractId,
        RewardCouponV2,
      ]],
  ) extends PrettyPrinting {
    def hasRewards: Boolean =
      livenessActivityRecords.nonEmpty ||
        validatorRewardCoupons.nonEmpty ||
        appRewardCoupons.nonEmpty ||
        rewardCouponsV2.nonEmpty ||
        unclaimedActivityRecords.nonEmpty ||
        developmentFundCoupons.nonEmpty

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("livenessActivityRecords", _.livenessActivityRecords.size),
      param("validatorRewardCoupons", _.validatorRewardCoupons.size),
      param("appRewardCoupons", _.appRewardCoupons.size),
      param("rewardCouponsV2", _.rewardCouponsV2.size),
      param("unclaimedActivityRecords", _.unclaimedActivityRecords.size),
      param("developmentFundCoupons", _.developmentFundCoupons.size),
    )
  }

  private def fetchCouponsData(
      issuingRoundsMap: Map[
        Round,
        IssuingMiningRound,
      ]
  )(implicit tc: TraceContext): Future[CouponsData] = {
    for {
      livenessActivityRecordsWithQuantity <- store.listSortedLivenessActivityRecords(
        issuingRoundsMap
      )
      validatorRewardCoupons <- store.listSortedValidatorRewards(
        Some(issuingRoundsMap.keySet.map(_.number))
      )
      appRewardCouponsWithQuantity <- store.listSortedAppRewards(issuingRoundsMap)
      rewardCouponsV2 <- store.listRewardCouponsV2(
        includeUnassigned = true,
        includeAssigned = true,
        limit = HardLimit.tryCreate(rewardSharingConfig.batchSize),
      )
      unclaimedActivityRecords <- store.listUnclaimedActivityRecords()
      developmentFundCoupons <- store.listDevelopmentFundCoupons()
    } yield CouponsData(
      livenessActivityRecordsWithQuantity.map(_._1),
      validatorRewardCoupons,
      appRewardCouponsWithQuantity.map(_._1),
      unclaimedActivityRecords,
      developmentFundCoupons,
      rewardCouponsV2.map(_.contract),
    )
  }

  private def buildTransferInputs(
      couponsData: CouponsData,
      amuletsToMerge: Seq[Contract[
        Amulet.ContractId,
        Amulet,
      ]],
      maxNumInputs: Int,
  ): Seq[TransferInput] = {
    val livenessInputs: Seq[TransferInput] = couponsData.livenessActivityRecords.map { record =>
      new InputValidatorLivenessActivityRecord(record.contractId): TransferInput
    }

    val validatorCouponInputs: Seq[TransferInput] = couponsData.validatorRewardCoupons.map {
      coupon =>
        new InputValidatorRewardCoupon(coupon.contractId): TransferInput
    }

    val appCouponInputs: Seq[TransferInput] = couponsData.appRewardCoupons.map { coupon =>
      new InputAppRewardCoupon(coupon.contractId): TransferInput
    }

    val unclaimedActivityRecordInputs: Seq[TransferInput] =
      couponsData.unclaimedActivityRecords.map { record =>
        new InputUnclaimedActivityRecord(record.contractId): TransferInput
      }

    val developmentFundCouponInputs: Seq[TransferInput] =
      couponsData.developmentFundCoupons.map { coupon =>
        new InputDevelopmentFundCoupon(coupon.contractId): TransferInput
      }

    val amuletInputs: Seq[TransferInput] = amuletsToMerge.map { amulet =>
      new InputAmulet(amulet.contractId): TransferInput
    }

    val rewardCouponV2Inputs: Seq[TransferInput] = couponsData.rewardCouponsV2.map { coupon =>
      new InputRewardCouponV2(coupon.contractId): TransferInput
    }

    val allInputs = livenessInputs ++ validatorCouponInputs ++ appCouponInputs ++
      rewardCouponV2Inputs ++ unclaimedActivityRecordInputs ++ developmentFundCouponInputs ++ amuletInputs
    allInputs.take(maxNumInputs)
  }

  private def buildTransferContext(
      openRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
      openIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
      couponsData: CouponsData,
      validatorRightOpt: Option[Contract[ValidatorRight.ContractId, ValidatorRight]],
  ): TransferContext = {
    // Only include ValidatorRight in context if we're actually collecting ValidatorRewardCoupons
    val validatorRightsMap =
      (validatorRightOpt, couponsData.validatorRewardCoupons.nonEmpty) match {
        case (Some(vr), true) => Map(vr.payload.user -> vr.contractId)
        case _ => Map.empty[String, ValidatorRight.ContractId]
      }

    new TransferContext(
      openRound.contractId,
      openIssuingRounds.view
        .filter(r =>
          couponsData.livenessActivityRecords.exists(_.payload.round == r.payload.round) ||
            couponsData.validatorRewardCoupons.exists(_.payload.round == r.payload.round) ||
            couponsData.appRewardCoupons.exists(_.payload.round == r.payload.round) ||
            couponsData.rewardCouponsV2.exists(_.payload.round == r.payload.round)
        )
        .map(r => (r.payload.round, r.contractId))
        .toMap[
          Round,
          IssuingMiningRound.ContractId,
        ]
        .asJava,
      validatorRightsMap.asJava,
      None.toJava,
    )
  }

  private def shouldShareNow(
      coupons: Seq[Contract[RewardCouponV2.ContractId, RewardCouponV2]],
      config: RewardSharingConfig,
  ): Boolean = {
    val now = context.clock.now.toInstant
    val minTtl = config.minTtlAfterSharing.asJava
    coupons.exists { c =>
      !c.payload.expiresAt.isAfter(now.plus(minTtl))
    }
  }

  // Merge amulets only if we're above 2x the merge limit to reduce potential waste of traffic
  private def selectAmuletsToMerge(
      amulets: Seq[Contract[Amulet.ContractId, Amulet]],
      delegation: Contract[MintingDelegation.ContractId, MintingDelegation],
  ): Seq[Contract[Amulet.ContractId, Amulet]] = {
    val mergeLimit = delegation.payload.amuletMergeLimit.longValue()
    if (amulets.size >= 2 * mergeLimit) {
      // Merge the smallest amounts first
      // we do +1 here to maintain exactly 'mergeLimit' amulets after the mint
      amulets
        .sortBy(a => BigDecimal(a.payload.amount.initialAmount))
        .take(amulets.size - mergeLimit.toInt + 1)
    } else Seq.empty
  }

  private case class MintInputs(
      delegation: Contract[MintingDelegation.ContractId, MintingDelegation],
      openRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
      openIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
      amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
      maxNumInputs: Int,
      validatorRightOpt: Option[Contract[ValidatorRight.ContractId, ValidatorRight]],
  )

  private case class MintSubmissionData(
      delegation: Contract[MintingDelegation.ContractId, MintingDelegation],
      couponsData: CouponsData,
      amuletsToMerge: Seq[Contract[Amulet.ContractId, Amulet]],
      inputs: java.util.List[TransferInput],
      paymentContext: PaymentTransferContext,
      contractsToDisclose: DisclosedContracts.NE,
      delegateParty: PartyId,
  )

  private def buildMintSubmissionData(
      mintInputs: MintInputs,
      couponsData: CouponsData,
      amuletsToMerge: Seq[Contract[Amulet.ContractId, Amulet]],
  ): MintSubmissionData = {
    val inputs = buildTransferInputs(couponsData, amuletsToMerge, mintInputs.maxNumInputs)
    val transferContext =
      buildTransferContext(
        mintInputs.openRound,
        mintInputs.openIssuingRounds,
        couponsData,
        mintInputs.validatorRightOpt,
      )
    MintSubmissionData(
      delegation = mintInputs.delegation,
      couponsData = couponsData,
      amuletsToMerge = amuletsToMerge,
      inputs = inputs.asJava,
      paymentContext =
        new PaymentTransferContext(mintInputs.amuletRules.contractId, transferContext),
      contractsToDisclose = spliceLedgerConnection
        .disclosedContracts(
          mintInputs.amuletRules,
          mintInputs.openRound,
        ) addAll mintInputs.openIssuingRounds,
      delegateParty = PartyId.tryFromProtoPrimitive(mintInputs.delegation.payload.delegate),
    )
  }
}
