// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.lfdecentralizedtrust.splice.store.KeyValueStore
import org.lfdecentralizedtrust.splice.store.KeyValueStore.timestampCodec
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdatesSegment
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.VotesBackfillCursor

class ScanKeyValueProvider(val store: KeyValueStore, val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {}

object ScanKeyValueProvider {
  implicit val updatesSegmentCodec: Codec[UpdatesSegment] = deriveCodec[UpdatesSegment]

  implicit val votesBackfillCursorCodec: Codec[VotesBackfillCursor] =
    deriveCodec[VotesBackfillCursor]
}
