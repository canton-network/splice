// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.RewardCouponV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.rewardassignmentv1.{
  RewardBeneficiary,
  RewardCoupon,
  RewardCoupon_AssignBeneficiaries,
}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.HardLimit
import org.lfdecentralizedtrust.splice.util.{AssignedContract, ChoiceContextWithDisclosures}
import cats.data.NonEmptyList
import org.lfdecentralizedtrust.splice.wallet.config.RewardSharingConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class RewardSharingTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    config: RewardSharingConfig,
    spliceLedgerConnection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[RewardSharingTrigger.Task] {

  import RewardSharingTrigger.*

  private def endUserParty = store.key.endUserParty

  override protected def extraMetricLabels = Seq("party" -> endUserParty.toString)

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] =
    for {
      unassignedCoupons <- store.listRewardCouponsV2(
        includeUnassigned = true,
        includeAssigned = false,
        limit = HardLimit.tryCreate(context.config.parallelism * config.batchSize),
      )
    } yield {
      unassignedCoupons
        .flatMap(_.toAssignedContract)
        .toList
        .grouped(config.batchSize)
        .flatMap(NonEmptyList.fromList)
        .filter(shouldShareNow)
        .map(Task(_))
        .toSeq
    }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val primaryCoupon = task.coupons.head
    val additionalCoupons = task.coupons.tail

    val newBeneficiaries = config.allDamlBeneficiaries(endUserParty).map { case (party, pct) =>
      new RewardBeneficiary(party.toProtoPrimitive, pct)
    }

    val assignArgs = new RewardCoupon_AssignBeneficiaries(
      additionalCoupons
        .map(_.contract.contractId.toInterface(RewardCoupon.INTERFACE))
        .asJava,
      newBeneficiaries.asJava,
      ChoiceContextWithDisclosures.emptyExtraArgs,
    )

    val primaryInterfaceCid =
      primaryCoupon.contract.contractId
        .toInterface(RewardCoupon.INTERFACE)

    spliceLedgerConnection
      .submit(
        actAs = Seq(endUserParty),
        readAs = Seq(endUserParty),
        primaryInterfaceCid.exerciseRewardCoupon_AssignBeneficiaries(assignArgs),
      )
      .withSynchronizerId(primaryCoupon.domain)
      .noDedup
      .yieldUnit()
      .map { _ =>
        TaskSuccess(
          s"Shared ${task.coupons.size} RewardCouponV2 with ${newBeneficiaries.size} beneficiaries for $endUserParty"
        )
      }
  }

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] =
    // Stale if all of the task's coupons have been archived
    store.multiDomainAcsStore.containsArchived(
      task.coupons.toList.map(_.contract.contractId)
    )

  private def shouldShareNow(
      coupons: NonEmptyList[AssignedContract[RewardCouponV2.ContractId, RewardCouponV2]]
  ): Boolean = {
    val now = context.clock.now.toInstant
    val minTtl = config.minTtlAfterSharing.asJava
    coupons.exists { c =>
      !c.contract.payload.expiresAt.isAfter(now.plus(minTtl))
    }
  }
}

object RewardSharingTrigger {

  final case class Task(
      coupons: NonEmptyList[AssignedContract[RewardCouponV2.ContractId, RewardCouponV2]]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("couponCount", _.coupons.size)
      )
  }
}
