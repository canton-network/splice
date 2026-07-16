// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_ClaimExpiredRewardsV2
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpireRewardCouponV2Trigger.{Task, Coupon, CouponCid, getStakeholders}
import org.lfdecentralizedtrust.splice.environment.{DarResources, PackageIdResolver}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class ExpireRewardCouponV2Trigger(
    svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends BatchedMultiDomainExpiredContractTrigger.Template[CouponCid, Coupon](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svConfig.delegatelessAutomationExpiredRewardCouponV2BatchSize,
      svTaskContext.dsoStore.listExpiredRewardCouponsV2(None),
      splice.amulet.RewardCouponV2.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      getStakeholders,
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val expiredCoupons = task.work.expiredContracts
    // The batch is already split by the amulet version so we skip the whole batch.
    // The amulet version here is same as supportsTrafficBasedAppRewards.
    if (task.work.vettedVersion < DarResources.amulet_0_1_19.metadata.version) {
      Future.successful(
        TaskSuccess(
          s"Skipped batch of ${expiredCoupons.size} reward coupons at amulet version ${task.work.vettedVersion}: "
        )
      )
    } else {
      val cids = expiredCoupons.map(_.contractId).asJava
      val expiryInformees = (task.work.stakeholders - store.key.dsoParty)
        .map(_.toProtoPrimitive)
        .toSeq
        .distinct
        .sorted
      for {
        dsoRules <- store.getDsoRules()
        amuletRules <- store.getAmuletRules()
        cmd = dsoRules.exercise(
          _.exerciseDsoRules_ClaimExpiredRewardsV2(
            amuletRules.contractId,
            new AmuletRules_ClaimExpiredRewardsV2(
              cids,
              expiryInformees.asJava,
            ),
            controller,
          )
        )
        _ <- svTaskContext
          .connection(SpliceLedgerConnectionPriority.Low)
          .submit(
            Seq(store.key.svParty),
            Seq(store.key.dsoParty),
            cmd,
          )
          .noDedup
          .withSynchronizerId(dsoRules.domain)
          .yieldUnit()
      } yield TaskSuccess(s"archived ${expiredCoupons.size} expired reward coupons v2")
    }
  }
}

object ExpireRewardCouponV2Trigger extends ContractStakeholders[splice.amulet.RewardCouponV2] {
  private type CouponCid = splice.amulet.RewardCouponV2.ContractId
  private type Coupon = splice.amulet.RewardCouponV2

  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[CouponCid, Coupon]
    ]

  override def informees(payload: splice.amulet.RewardCouponV2): Seq[String] = if (
    payload.providerIsObserver
  ) payload.provider +: payload.beneficiary.toScala.toList
  else Seq.empty

  override def dso(payload: splice.amulet.RewardCouponV2): String = payload.dso
}
