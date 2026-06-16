// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_ClaimExpiredRewardsV2
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpireRewardCouponV2Trigger.*
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.util.AssignedContract

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
      ExpireRewardCouponV2Trigger.listExpiredVettedRewardCouponsV2(
        svTaskContext,
        context.loggerFactory.getTracedLogger(classOf[ExpireRewardCouponV2Trigger]),
      ),
      splice.amulet.RewardCouponV2.COMPANION,
      svTaskContext.vettingLookupService,
      PackageIdResolver.Package.SpliceAmulet,
      payload => (payload.dso +: observerParties(payload)).map(PartyId.tryFromProtoPrimitive(_)),
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val expiredCoupons = task.work.expiredContracts
    val cids = expiredCoupons.map(_.contractId).asJava
    val expiryObservers =
      expiredCoupons.flatMap(c => observerParties(c.payload)).distinct.sorted.asJava
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewardsV2(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewardsV2(
            cids,
            expiryObservers,
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

object ExpireRewardCouponV2Trigger {
  private type CouponCid = splice.amulet.RewardCouponV2.ContractId
  private type Coupon = splice.amulet.RewardCouponV2
  private type CouponContract = AssignedContract[CouponCid, Coupon]

  type Task =
    ScheduledTaskTrigger.ReadyTask[
      BatchedMultiDomainExpiredContractTrigger.Batch[CouponCid, Coupon]
    ]

  private def observerParties(coupon: Coupon): Seq[String] =
    if (coupon.providerIsObserver) coupon.provider +: coupon.beneficiary.toScala.toList
    else Seq.empty

  // Selects which expired RewardCouponV2 contracts the trigger may archive:
  //  - providerIsObserver=false: these can always be archived, as only the dso is an observer.
  //  - providerIsObserver=true: Provider and possibly even beneficiary
  //                             are observers, so confirm the vetted state.
  private[delegatebased] def listExpiredVettedRewardCouponsV2(
      svTaskContext: SvTaskBasedTrigger.Context,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext
  ): BatchedMultiDomainExpiredContractTrigger.ListExpiredContracts[CouponCid, Coupon] =
    (now, limit) =>
      implicit tc =>
        for {
          expired <- svTaskContext.dsoStore.listExpiredRewardCouponsV2(now, limit)(tc)
          (observerCoupons, hiddenCoupons) = expired.partition(_.payload.providerIsObserver)
          vetted <- filterCorrectlyVetted(svTaskContext, logger, observerCoupons, now)
        } yield hiddenCoupons ++ vetted

  private def filterCorrectlyVetted(
      svTaskContext: SvTaskBasedTrigger.Context,
      logger: TracedLogger,
      observerCoupons: Seq[CouponContract],
      now: CantonTimestamp,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Seq[CouponContract]] = {
    def observerPartySet(coupon: CouponContract): Set[PartyId] =
      observerParties(coupon.payload).map(PartyId.tryFromProtoPrimitive(_)).toSet

    val couponsByObserverSet: Seq[(Set[PartyId], Seq[CouponContract])] =
      observerCoupons.groupBy(observerPartySet).toSeq
    for {
      groups <- Future.traverse(couponsByObserverSet) { case (parties, coupons) =>
        svTaskContext.packageVersionSupport
          .supportsTrafficBasedAppRewards(parties.toSeq, now)
          .map(coupons -> _.supported)
      }
    } yield {
      val (vetted, skipped) = groups.partition { case (_, supported) => supported }
      val skippedCoupons = skipped.flatMap(_._1)
      if (skippedCoupons.nonEmpty)
        logger.debug(
          s"Skipping ${skippedCoupons.size} contracts whose observers are not correctly vetted."
        )
      vetted.flatMap(_._1)
    }
  }
}
