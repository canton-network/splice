// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.store.db.{
  DbUnavailablePartiesStore,
  SplicePostgresTest,
  StoreDescriptor,
}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class DbUnavailablePartiesStoreTest
    extends StoreTestBase
    with Matchers
    with HasExecutionContext
    with SplicePostgresTest {

  private val storeDescriptor = StoreDescriptor(
    version = 1,
    name = "DbUnavailablePartiesStoreTest",
    party = dsoParty,
    participant = mkParticipantId("participant"),
    key = Map(),
  )

  private val storeDescriptor2 = storeDescriptor.copy(version = 2)

  private val baseDuration = NonNegativeFiniteDuration(1.second)
  private val maxIgnoreDuration = NonNegativeFiniteDuration(4.seconds)
  private val oneSecond = 1_000_000L

  private def atSeconds(seconds: Long): Long = seconds * oneSecond

  private def mkStore(descriptor: StoreDescriptor = storeDescriptor) =
    DbUnavailablePartiesStore(
      descriptor,
      storage,
      baseDuration,
      maxIgnoreDuration,
      loggerFactory,
    )

  "DbUnavailablePartiesStore" should {

    "addParties" should {

      "be a no-op for an empty sequence" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq.empty, atSeconds(0))
          parties <- store.listParties(atSeconds(0))
        } yield parties shouldBe empty
      }

      "ignores new parties for the base duration" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1), userParty(2)), atSeconds(0))
          justBefore <- store.listParties(atSeconds(1) - 1)
          atExpiry <- store.listParties(atSeconds(1))
        } yield {
          justBefore should contain theSameElementsAs Seq(userParty(1), userParty(2))
          atExpiry shouldBe empty
        }
      }

      "double the ignore duration when a party is re-added later" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0)) // expires at 1s
          _ <- store.addParties(
            Seq(userParty(1)),
            atSeconds(1),
          ) // should bump ignore duration to 2s => expires at 3s
          justBefore <- store.listParties(atSeconds(3) - 1)
          atExpiry <- store.listParties(atSeconds(3))
        } yield {
          justBefore should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

      "not double the ignore duration when a party is re-added within its window" in {
        val midWindow = atSeconds(1) / 2
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0)) // expires at 1s
          _ <- store.addParties(Seq(userParty(1)), midWindow) // already ignored => no-op
          justBefore <- store.listParties(atSeconds(1) - 1)
          atExpiry <- store.listParties(atSeconds(1))
        } yield {
          justBefore should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

      "cap the ignore duration at maxIgnoreDuration" in {
        for {
          store <- mkStore()
          // 0s => 1s, 1s => 2s, 3s => 4s, 7s => 4s (capped), 11s => 4s (capped) => expires at 15s
          _ <- MonadUtil.sequentialTraverse(Seq(0L, 1L, 3L, 7L, 11L))(n =>
            store.addParties(Seq(userParty(1)), atSeconds(n))
          )
          justBefore <- store.listParties(atSeconds(15) - 1)
          atExpiry <- store.listParties(atSeconds(15))
        } yield {
          justBefore should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

      "is idempotent when replayed with the same timestamp" in {
        for {
          store <- mkStore()
          // DbStorage.update may retry the statement, which must not double the duration:
          // the expiry must stay at 1s, not move to 2s
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0))
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0))
          justBefore <- store.listParties(atSeconds(1) - 1)
          atExpiry <- store.listParties(atSeconds(1))
        } yield {
          justBefore should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

      "restart the expiry window from the latest marking" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0)) // 1s => expires at 1s
          _ <- store.addParties(Seq(userParty(1)), atSeconds(2)) // 2s from 2s => expires at 4s
          atOldExpiry <- store.listParties(atSeconds(1))
          justBefore <- store.listParties(atSeconds(4) - 1)
          atExpiry <- store.listParties(atSeconds(4))
        } yield {
          atOldExpiry should contain(userParty(1))
          justBefore should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

      "deduplicate parties within a single call" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1), userParty(1)), atSeconds(0))
          justBefore <- store.listParties(atSeconds(1) - 1)
          atExpiry <- store.listParties(atSeconds(1))
        } yield {
          justBefore should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

      "apply the insert and the doubling branch independently within one call" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0)) // expires at 1s
          // party 1's window has just elapsed => doubles to 2s => expires at 3s
          // party 2 is new => base duration => expires at 2s
          _ <- store.addParties(Seq(userParty(1), userParty(2)), atSeconds(1))
          beforeParty2Expiry <- store.listParties(atSeconds(2) - 1)
          atParty2Expiry <- store.listParties(atSeconds(2))
          atParty1Expiry <- store.listParties(atSeconds(3))
        } yield {
          beforeParty2Expiry should contain theSameElementsAs Seq(userParty(1), userParty(2))
          atParty2Expiry should contain theSameElementsAs Seq(userParty(1))
          atParty1Expiry shouldBe empty
        }
      }

      "keep the later expiry when a marking arrives out of order" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(2)) // 1s => expires at 3s
          // had the stale marking landed, updated_at would rewind to 0s and the duration
          // would double to 2s, expiring at 2s instead
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0))
          atRewoundExpiry <- store.listParties(atSeconds(2))
          atExpiry <- store.listParties(atSeconds(3))
        } yield {
          atRewoundExpiry should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

    }

    "removeParties" should {

      "remove only the given parties" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1), userParty(2), userParty(3)), atSeconds(0))
          deleted <- store.removeParties(Seq(userParty(1), userParty(3)))
          parties <- store.listParties(atSeconds(0))
        } yield {
          deleted shouldBe 2
          parties should contain theSameElementsAs Seq(userParty(2))
        }
      }

      "be a no-op for unknown parties" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0))
          deleted <- store.removeParties(Seq(userParty(99)))
          parties <- store.listParties(atSeconds(0))
        } yield {
          deleted shouldBe 0
          parties should contain(userParty(1))
        }
      }

      "be a no-op for an empty sequence" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0))
          deleted <- store.removeParties(Seq.empty)
          parties <- store.listParties(atSeconds(0))
        } yield {
          deleted shouldBe 0
          parties should contain(userParty(1))
        }
      }

      "reset the backoff, so a re-added party starts from the base duration again" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0)) // 1s => expires at 1s
          _ <- store.addParties(Seq(userParty(1)), atSeconds(1)) // 2s => expires at 3s
          deleted <- store.removeParties(Seq(userParty(1)))
          afterRemoval <- store.listParties(atSeconds(1))
          _ <- store.addParties(Seq(userParty(1)), atSeconds(3))
          justBefore <- store.listParties(atSeconds(4) - 1)
          atExpiry <- store.listParties(atSeconds(4))
        } yield {
          deleted shouldBe 1
          afterRemoval shouldBe empty
          justBefore should contain(userParty(1))
          atExpiry shouldBe empty
        }
      }

      "remove parties regardless of which store recorded them" in {
        for {
          store1 <- mkStore(storeDescriptor)
          store2 <- mkStore(storeDescriptor2)
          _ <- store1.addParties(Seq(userParty(1)), atSeconds(0))
          deleted <- store2.removeParties(Seq(userParty(1)))
          parties <- store1.listParties(atSeconds(0))
        } yield {
          deleted shouldBe 1
          parties shouldBe empty
        }
      }
    }

    "removePartiesUpToStoreId" should {

      "remove every party recorded at or below the given store id" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1), userParty(2)), atSeconds(0))
          deleted <- store.removePartiesUpToStoreId(store.storeId.toLong)
          parties <- store.listParties(atSeconds(0))
        } yield {
          deleted shouldBe 2
          parties shouldBe empty
        }
      }

      "leave parties recorded by a later store id in place" in {
        for {
          store1 <- mkStore(storeDescriptor)
          store2 <- mkStore(storeDescriptor2)
          _ = store1.storeId should be < store2.storeId
          _ <- store1.addParties(Seq(userParty(1)), atSeconds(0))
          _ <- store2.addParties(Seq(userParty(2)), atSeconds(0))
          deleted <- store1.removePartiesUpToStoreId(store1.storeId.toLong)
          remaining <- store2.listParties(atSeconds(0))
        } yield {
          deleted shouldBe 1
          remaining should contain theSameElementsAs Seq(userParty(2))
        }
      }
    }

    "listParties" should {

      "only return entries whose ignore window has not elapsed" in {
        for {
          store <- mkStore()
          _ <- store.addParties(Seq(userParty(1)), atSeconds(0)) // expires at 1s
          _ <- store.addParties(Seq(userParty(2)), atSeconds(1)) // expires at 2s
          parties <- store.listParties(atSeconds(1))
        } yield {
          parties should contain theSameElementsAs Seq(userParty(2))
        }
      }

      "return entries recorded by any store" in {
        for {
          store1 <- mkStore(storeDescriptor)
          store2 <- mkStore(storeDescriptor2)
          _ <- store1.addParties(Seq(userParty(1)), atSeconds(0))
          _ <- store2.addParties(Seq(userParty(2)), atSeconds(0))
          parties1 <- store1.listParties(atSeconds(0))
          parties2 <- store2.listParties(atSeconds(0))
        } yield {
          parties1 should contain theSameElementsAs Seq(userParty(1), userParty(2))
          parties2 should contain theSameElementsAs Seq(userParty(1), userParty(2))
        }
      }
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}
