// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.resource.DbLockCounter

/** Lock counter registry for Splice app instance guards.
  *
  * Uses values in the range 100–119, above Canton's production range (1–76) and its
  * test-reserved range starting at 77.  The global uniqueness check in [[DbLockCounter]]
  * will catch any accidental collisions at startup.
  *
  * Each app type occupies two counters: one for the main (exclusive) connection and one
  * for the shared write-pool connections.
  */
object SpliceDbLockCounters {
  val VALIDATOR_WRITE: DbLockCounter = DbLockCounter(100)
  val VALIDATOR_WRITERS: DbLockCounter = DbLockCounter(101)
  val SV_WRITE: DbLockCounter = DbLockCounter(102)
  val SV_WRITERS: DbLockCounter = DbLockCounter(103)
  val SCAN_WRITE: DbLockCounter = DbLockCounter(104)
  val SCAN_WRITERS: DbLockCounter = DbLockCounter(105)
  val SPLITWELL_WRITE: DbLockCounter = DbLockCounter(106)
  val SPLITWELL_WRITERS: DbLockCounter = DbLockCounter(107)
}
