// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0

/** Conversions between the Daml FeaturedAppKind and its HTTP API representation. */
object FeaturedAppKindUtil {

  def toDaml(kind: d0.FeaturedAppKind): amuletCodegen.FeaturedAppKind = kind match {
    case d0.FeaturedAppKind.members.Basic => amuletCodegen.FeaturedAppKind.FAK_BASIC
    case d0.FeaturedAppKind.members.AssetMoving => amuletCodegen.FeaturedAppKind.FAK_ASSETMOVING
    case d0.FeaturedAppKind.members.Asset => amuletCodegen.FeaturedAppKind.FAK_ASSET
  }

  def fromDaml(kind: amuletCodegen.FeaturedAppKind): d0.FeaturedAppKind = kind match {
    case amuletCodegen.FeaturedAppKind.FAK_BASIC => d0.FeaturedAppKind.Basic
    case amuletCodegen.FeaturedAppKind.FAK_ASSETMOVING => d0.FeaturedAppKind.AssetMoving
    case amuletCodegen.FeaturedAppKind.FAK_ASSET => d0.FeaturedAppKind.Asset
  }
}
