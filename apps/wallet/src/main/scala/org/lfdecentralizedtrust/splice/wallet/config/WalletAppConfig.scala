// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.config

import com.google.common.annotations.VisibleForTesting
import org.lfdecentralizedtrust.splice.config.{HttpClientConfig, NetworkAppClientConfig}
import org.lfdecentralizedtrust.splice.util.SpliceUtil
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.topology.PartyId

case class WalletSynchronizerConfig(
    global: SynchronizerAlias
)

// Inlined to avoid a dependency
case class WalletValidatorAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

case class WalletAppClientConfig(
    adminApi: NetworkAppClientConfig,
    ledgerApiUser: String,
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

final case class WalletSweepConfig(
    // The maximum balance in USD that should be kept in the wallet. When
    // exceeded a transfer offer for the difference between current balance and
    // minBalanceUsd will be made to the receiver.
    maxBalanceUsd: NonNegativeNumeric[BigDecimal],
    // The minimum balance in USD to keep in the wallet.
    minBalanceUsd: NonNegativeNumeric[BigDecimal],
    receiver: PartyId,
    // If set to true we use the transfer preapproval of the receiver to transfer
    // directly instead of creating a transfer offer.
    useTransferPreapproval: Boolean = false,
)

final case class AutoAcceptTransfersConfig(
    fromParties: Seq[PartyId] = Seq()
)

/** A beneficiary that receives a share of the provider's app reward coupons.
  * @param beneficiary the party receiving the share
  * @param percentage fraction of the reward in (0.0, 1.0]; per-party percentages must sum to at most 1.0
  */
final case class AppRewardBeneficiaryConfig(
    beneficiary: PartyId,
    percentage: BigDecimal,
)

/** How traffic-based app reward coupons are shared with beneficiaries. */
sealed trait RewardSharingConfig

object RewardSharingConfig {

  val DefaultBatchSize: Int = 100

  /** Off-node automation owns beneficiary assignment: the node holds unassigned coupons back
    * and leaves them untouched rather than assigning or minting them itself.
    */
  case class External() extends RewardSharingConfig

  /** The node performs beneficiary assignment and minting itself.
    * @param minTtlAfterSharing minimum remaining coupon TTL before sharing is triggered;
    *   e.g., 30h means share when 30h of coupon lifetime remains (6h after creation for 36h coupons)
    * @param beneficiaries parties to share rewards with and their percentages;
    *   the provider keeps the remainder (1.0 - sum of percentages)
    * @param batchSize maximum number of coupons to share or assign per trigger run
    */
  final case class BuiltIn(
      minTtlAfterSharing: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(30),
      beneficiaries: Seq[AppRewardBeneficiaryConfig] = Seq.empty,
      batchSize: Int = DefaultBatchSize,
  ) extends RewardSharingConfig {
    def providerRemainder: BigDecimal = BigDecimal(1.0) - beneficiaries.map(_.percentage).sum

    @VisibleForTesting
    def allBeneficiaries(provider: PartyId): Seq[AppRewardBeneficiaryConfig] = {
      val remainder = providerRemainder
      beneficiaries ++
        (if (remainder > 0) Seq(AppRewardBeneficiaryConfig(provider, remainder))
         else Seq.empty)
    }

    def allDamlBeneficiaries(provider: PartyId): Seq[(PartyId, java.math.BigDecimal)] =
      allBeneficiaries(provider).map(b => (b.beneficiary, SpliceUtil.damlDecimal(b.percentage)))
  }
}
