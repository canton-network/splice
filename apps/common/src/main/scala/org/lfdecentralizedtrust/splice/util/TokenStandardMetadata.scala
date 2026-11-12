// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

object TokenStandardMetadata {
  val splicePrefix = "splice.lfdecentralizedtrust.org"

  val reasonMetaKey = s"$splicePrefix/reason"
  val senderMetaKey = s"$splicePrefix/sender"
  val burnedMetaKey = s"$splicePrefix/burned"

  // Mirrors "trafficPurchaseReceiver" in splice.ExternalPartyAmuletRules.daml.
  val trafficPurchaseReceiver =
    "splice_traffic-manager::1220000000000000000000000000000000000000000000000000000000000000abcd"

  // Mirrors "trafficPurchaseMemoPrefix" in splice.DecentralizedSynchronizer.daml.
  val trafficPurchaseMemoPrefix = "splice/memo:"

  // Mirrors "registeredSynchronizerContextKey" in splice.Amulet.TokenApiUtils.daml.
  val registeredSynchronizerContextKey = "registered-synchronizer"

  /** The `synchronizer-id` of a traffic purchase memo, if it has a non-empty one. The Daml code
    * (`parseTrafficPurchaseMemo` in splice.DecentralizedSynchronizer.daml) validates the full memo.
    */
  def trafficPurchaseMemoSynchronizerId(memo: String): Option[String] =
    Option
      .when(memo.startsWith(trafficPurchaseMemoPrefix))(memo.stripPrefix(trafficPurchaseMemoPrefix))
      .flatMap(
        _.split("&").iterator
          .map(_.split("=", -1))
          .collectFirst { case Array("synchronizer-id", synchronizerId) => synchronizerId }
      )
      .filter(_.nonEmpty)

  val expireLockKey = "expire-lock"
}
