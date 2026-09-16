// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv2

import scala.jdk.OptionConverters.*

object TokenStandardAccount {

  def tryGetRegularAccountOwner(account: holdingv2.Account): String = {
    account.owner.toScala.getOrElse(
      throw new IllegalArgumentException(
        s"expected regular account, but got a special account without an owner: $account"
      )
    )
  }

}
