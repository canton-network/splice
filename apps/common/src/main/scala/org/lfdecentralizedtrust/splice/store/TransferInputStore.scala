// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  AppRewardCoupon,
  RewardCouponV2,
  ValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.InputAmulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait TransferInputStore extends AppStore with LimitHelpers {

  /** List all non-expired amulets owned by a user in descending order according to their amount. */
  def listSortedAmuletsAndQuantity(
      limit: Limit = defaultLimit
  )(implicit
      tc: TraceContext
  ): Future[Seq[(BigDecimal, InputAmulet)]] = for {
    amulets <- multiDomainAcsStore.listContracts(Amulet.COMPANION)
  } yield amulets
    .map(c =>
      (
        c.payload.amount.initialAmount,
        c,
      )
    )
    .sortBy(quantityAndAmulet =>
      // negating because largest values should come first.
      quantityAndAmulet._1.negate()
    )
    .take(limit.limit)
    .map(quantityAndAmulet =>
      (
        quantityAndAmulet._1,
        new InputAmulet(
          quantityAndAmulet._2.contractId
        ),
      )
    )

  /** Returns the validator reward coupon sorted by their round in ascending order.
    * Optionally filtered by a set of issuing rounds.
    */
  def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = defaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]
  ]] =
    for {
      rewards <- multiDomainAcsStore.listContracts(
        ValidatorRewardCoupon.COMPANION
      )
    } yield applyLimit(
      "listSortedValidatorRewards",
      limit,
      // TODO(DACH-NY/canton-network-node#6119) Perform filter, sort, and limit in the database query
      rewards.view
        .filter(rw =>
          activeIssuingRoundsO match {
            case Some(rounds) => rounds.contains(rw.payload.round.number)
            case None => true
          }
        )
        .map(_.contract)
        .toSeq
        .sortBy(_.payload.round.number),
    )

  /** Returns the app reward coupon sorted by their round in ascending order and their value in descending order.
    * All rewards are from the given `activeIssuingRounds`.
    */
  def listSortedAppRewards(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = defaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[AppRewardCoupon.ContractId, AppRewardCoupon], BigDecimal)
  ]] =
    for {
      rewards <- multiDomainAcsStore.listContracts(
        AppRewardCoupon.COMPANION
      )
    } yield applyLimit(
      "listSortedAppRewards",
      limit,
      rewards
        // TODO(DACH-NY/canton-network-node#6119) Perform filter, sort, and limit in the database query
        .flatMap { rw =>
          val issuingO = issuingRoundsMap.get(rw.payload.round)
          issuingO
            .map(i => {
              val quantity =
                if (rw.payload.featured)
                  rw.payload.amount.multiply(i.issuancePerFeaturedAppRewardCoupon)
                else
                  rw.payload.amount.multiply(i.issuancePerUnfeaturedAppRewardCoupon)
              (rw.contract, BigDecimal(quantity))
            })
        }
        .sorted(
          Ordering[(Long, BigDecimal)].on(
            (x: (
                Contract.Has[AppRewardCoupon.ContractId, AppRewardCoupon],
                BigDecimal,
            )) => (x._1.payload.round.number, -x._2)
          )
        ),
    )

  /** Returns RewardCouponV2 contracts filtered by assignment status and
    * sorted according to the specified order. Implemented in DB stores
    * with SQL-level filtering to avoid page-limit issues.
    */
  def listRewardCouponsV2(
      filter: RewardCouponV2Filter,
      sortOrder: RewardCouponV2SortOrder,
      limit: Limit = defaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]
  ]]
}

sealed trait RewardCouponV2Filter {
  def matches(rw: ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]): Boolean
}

object RewardCouponV2Filter {
  case object UnassignedOnly extends RewardCouponV2Filter {
    def matches(rw: ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]): Boolean =
      rw.payload.beneficiary.isEmpty
  }
  case object AssignedOnly extends RewardCouponV2Filter {
    def matches(rw: ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]): Boolean =
      rw.payload.beneficiary.isPresent
  }
  case object All extends RewardCouponV2Filter {
    def matches(rw: ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]): Boolean = true
  }
}

sealed trait RewardCouponV2SortOrder {
  def ordering: Ordering[ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]]
}

object RewardCouponV2SortOrder {
  case object ByExpiresAtAsc extends RewardCouponV2SortOrder {
    def ordering: Ordering[ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]] =
      Ordering.by(_.payload.expiresAt)
  }
  case object ByRoundAscAmountDesc extends RewardCouponV2SortOrder {
    def ordering: Ordering[ContractWithState[RewardCouponV2.ContractId, RewardCouponV2]] =
      Ordering[(Long, BigDecimal)].on(rw => (rw.payload.round.number, -BigDecimal(rw.payload.amount)))
  }
}
