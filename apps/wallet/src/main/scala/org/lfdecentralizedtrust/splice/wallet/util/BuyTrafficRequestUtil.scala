// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.buytrafficrequest as trafficRequestCodegen

object BuyTrafficRequestUtil {

  /** `maxTrackingIdLength` of `Splice.TextLimits` in Daml. */
  private val maxTrackingIdLength: Int = 279

  /** Whether the tracking id of the request satisfies the `ensure` clause of `BuyTrafficRequest`.
    *
    * Requests that violate it can only exist if they were created with an earlier version of
    * `splice-wallet`, which did not bound the tracking id. They can then no longer be completed or
    * expired, so the automation skips them instead of retrying forever.
    */
  def hasValidTrackingId(request: trafficRequestCodegen.BuyTrafficRequest): Boolean =
    // Daml's `DA.Text.length` counts code points
    request.trackingId.codePointCount(0, request.trackingId.length) <= maxTrackingIdLength
}
