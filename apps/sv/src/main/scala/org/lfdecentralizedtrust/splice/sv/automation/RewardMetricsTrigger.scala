// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Saturation
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.automation.RewardMetricsTrigger.RewardMetrics
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.collection.concurrent.TrieMap
import scala.concurrent.{blocking, ExecutionContext, Future}

class RewardMetricsTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    hiddenCouponProvidersSampleSize: Int,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    val mat: Materializer,
) extends PollingTrigger {

  private val rewardMetrics = new RewardMetrics(context.metricsFactory)

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      calculateRewards <- dsoStore.listCalculateRewardsV2()
      _ = {
        val (dryRun, minting) = calculateRewards.partition(_.payload.dryRun)
        rewardMetrics.calculateRewardsActiveContractsDryRun.updateValue(dryRun.size)
        rewardMetrics.calculateRewardsActiveContractsMinting.updateValue(minting.size)
      }
      processRewards <- dsoStore.listProcessRewardsV2()
      _ = {
        val (dryRun, minting) = processRewards.partition(_.payload.dryRun)
        rewardMetrics.processRewardsActiveContractsDryRun.updateValue(dryRun.size)
        rewardMetrics.processRewardsActiveContractsMinting.updateValue(minting.size)
      }
      providers <- dsoStore.listNonObserverRewardCouponsV2ProvidersSample(
        PageLimit.tryCreate(hiddenCouponProvidersSampleSize)
      )
      _ <- Future.traverse(providers) { provider =>
        dsoStore
          .listNonObserverRewardCouponsV2ForProvider(provider, PageLimit.Max)
          .map(coupons => rewardMetrics.updateHiddenCoupons(provider, coupons.size.toLong))
      }
      _ = rewardMetrics.pruneHiddenCouponGauges(providers)
    } yield false

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync().appended(SyncCloseable("reward metrics", rewardMetrics.close()))
}

object RewardMetricsTrigger {

  class RewardMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix

    private def activeContractsGauge(
        template: String,
        templateName: String,
        dryRun: Boolean,
    ): Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = prefix :+ template :+ "active_contracts",
          summary =
            s"The number of active $templateName contracts, as seen by the RewardMetricsTrigger",
          qualification = Saturation,
        ),
        initial = -1,
      )(MetricsContext.Empty.withExtraLabels("dryRun" -> dryRun.toString))

    val calculateRewardsActiveContractsDryRun: Gauge[Int] =
      activeContractsGauge("calculate_rewards_v2", "CalculateRewardsV2", dryRun = true)
    val calculateRewardsActiveContractsMinting: Gauge[Int] =
      activeContractsGauge("calculate_rewards_v2", "CalculateRewardsV2", dryRun = false)
    val processRewardsActiveContractsDryRun: Gauge[Int] =
      activeContractsGauge("process_rewards_v2", "ProcessRewardsV2", dryRun = true)
    val processRewardsActiveContractsMinting: Gauge[Int] =
      activeContractsGauge("process_rewards_v2", "ProcessRewardsV2", dryRun = false)

    private val hiddenCouponGauges: TrieMap[PartyId, Gauge[Long]] = TrieMap.empty

    @SuppressWarnings(Array("com.digitalasset.canton.RequireBlocking"))
    private def getHiddenCouponGauge(provider: PartyId): Gauge[Long] =
      hiddenCouponGauges.getOrElse(
        provider,
        // We must synchronize here to avoid allocating the metric for the same party multiple
        // times, which would lead to duplicate metric labels being reported by OpenTelemetry.
        blocking {
          synchronized {
            hiddenCouponGauges.getOrElseUpdate(
              provider,
              metricsFactory.gauge(
                MetricInfo(
                  prefix :+ "reward_coupons_v2" :+ "hidden_coupons",
                  "The number of hidden (non-observer) RewardCouponV2 contracts for this provider party",
                  Saturation,
                ),
                initial = 0L,
              )(MetricsContext.Empty.withExtraLabels("party" -> provider.toProtoPrimitive)),
            )
          }
        },
      )

    def updateHiddenCoupons(provider: PartyId, count: Long): Unit =
      getHiddenCouponGauge(provider).updateValue(count)

    @SuppressWarnings(Array("com.digitalasset.canton.RequireBlocking"))
    def pruneHiddenCouponGauges(currentProviders: Seq[PartyId]): Unit = {
      val toClose = hiddenCouponGauges.keySet.toSet -- currentProviders.toSet
      hiddenCouponGauges.view.filterKeys(toClose.contains).foreach(_._2.close())
      blocking {
        synchronized {
          hiddenCouponGauges --= toClose: Unit
        }
      }
    }

    override def close(): Unit = {
      calculateRewardsActiveContractsDryRun.close()
      calculateRewardsActiveContractsMinting.close()
      processRewardsActiveContractsDryRun.close()
      processRewardsActiveContractsMinting.close()
      hiddenCouponGauges.values.foreach(_.close())
    }
  }
}
