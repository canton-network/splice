// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.DevelopmentFundCoupon
import org.lfdecentralizedtrust.splice.util.Contract

import java.time.Instant
import scala.jdk.OptionConverters.*

object DevelopmentFundCouponUtil {

  def isMintable(
      coupon: Contract[DevelopmentFundCoupon.ContractId, DevelopmentFundCoupon],
      now: Instant,
  ): Boolean =
    coupon.payload.mintAfter.toScala.forall(mintAfter => !mintAfter.isAfter(now))
}
