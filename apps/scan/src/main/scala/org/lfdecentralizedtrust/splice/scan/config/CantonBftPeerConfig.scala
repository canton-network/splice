// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import org.apache.pekko.http.scaladsl.model.Uri

case class CantonBftPeerConfig(
    p2pUrl: Uri
)
