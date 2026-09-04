package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.Future

class InternedStringStoreTest extends StoreTestBase with SplicePostgresTest {

  private def mkStore = internedStringStore(storage)

  "InternedStringStore" should {

    "return the same id for repeated values" in {
      val store = mkStore
      for {
        id1 <- store.getOrIntern("same-value")
        id2 <- store.getOrIntern("same-value")
      } yield id2 shouldBe id1
    }

    "return different ids for different values" in {
      val store = mkStore
      for {
        id1 <- store.getOrIntern("value-1")
        id2 <- store.getOrIntern("value-2")
      } yield id2 should not be id1
    }

    "reuse interned ids across store instances" in {
      val store1 = mkStore
      val store2 = mkStore
      for {
        id1 <- store1.getOrIntern("persisted-value")
        id2 <- store2.getOrIntern("persisted-value")
      } yield id2 shouldBe id1
    }

    "handle concurrent interning of the same value" in {
      val store = mkStore
      for {
        ids <- Future.sequence((1 to 20).map(_ => store.getOrIntern("concurrent-value")))
      } yield ids.distinct should have size 1
    }

    "handle concurrent interning of the same value without cache" in {
      val store = new DbInternedStringStore(storage)
      for {
        ids <- Future.sequence((1 to 20).map(_ => store.getOrIntern("concurrent-db-value")))
      } yield ids.distinct should have size 1
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    for {
      _ <- resetAllAppTables(storage)
      _ <- storage.queryAndUpdate(
        sqlu"truncate interned_strings",
        "truncateInternedStrings",
      )
    } yield ()
  }
}

