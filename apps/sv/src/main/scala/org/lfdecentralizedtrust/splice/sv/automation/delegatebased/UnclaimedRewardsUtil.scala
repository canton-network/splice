// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedReward
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.{ExecutionContext, Future}

object UnclaimedRewardsUtil {

  type UnclaimedRewardContract = Contract[UnclaimedReward.ContractId, UnclaimedReward]

  final case class Shortfall(amount: BigDecimal)

  private val UnclaimedRewardsLimit = PageLimit.tryCreate(100)

  def collectSufficientUnclaimedRewards(store: SvDsoStore, requiredAmount: BigDecimal)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Either[Shortfall, Seq[UnclaimedRewardContract]]] =
    store.listUnclaimedRewards(UnclaimedRewardsLimit).map { unclaimedRewards =>
      val (selected, selectedAmount) =
        takeSufficient(unclaimedRewards.sortBy(_.payload.amount.negate()), requiredAmount)
      Either.cond(
        selectedAmount >= requiredAmount,
        selected,
        Shortfall(requiredAmount - selectedAmount),
      )
    }

  private def takeSufficient(
      rewards: Seq[UnclaimedRewardContract],
      target: BigDecimal,
  ): (Seq[UnclaimedRewardContract], BigDecimal) = {
    @annotation.tailrec
    def loop(
        remaining: List[UnclaimedRewardContract],
        acc: Seq[UnclaimedRewardContract],
        amountAcc: BigDecimal,
    ): (Seq[UnclaimedRewardContract], BigDecimal) =
      remaining match {
        case next :: rest if amountAcc < target =>
          loop(rest, acc :+ next, amountAcc + next.payload.amount)
        case _ => (acc, amountAcc)
      }

    loop(rewards.toList, Seq.empty, BigDecimal(0))
  }
}
