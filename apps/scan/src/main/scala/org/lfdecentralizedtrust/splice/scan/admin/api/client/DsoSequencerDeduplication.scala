// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

object DsoSequencerDeduplication {

  def partitionUniqueBy[Entry, Key](entries: Seq[Entry])(
      key: Entry => Key
  ): (Seq[Entry], Map[Key, Seq[Entry]]) = {
    val entriesByKey = entries.groupBy(key)
    val duplicates = entriesByKey.filter { case (_, entriesWithKey) => entriesWithKey.sizeIs > 1 }
    val unique = entries.filterNot(entry => duplicates.contains(key(entry)))
    (unique, duplicates)
  }
}
