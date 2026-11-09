package org.lfdecentralizedtrust.splice.scan.admin.api.client

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class DsoSequencerDeduplicationTest extends AnyWordSpec with BaseTest {
  import DsoSequencerDeduplicationTest.Entry

  private def partition(entries: Seq[Entry]) =
    DsoSequencerDeduplication.partitionUniqueBy(entries)(entry => (entry.serial, entry.id))

  "partitionUniqueBy" should {
    "keep all entries if keys are unique" in {
      val entries = Seq(Entry(0, "seq1", "sv1"), Entry(0, "seq2", "sv2"), Entry(0, "seq3", "sv3"))
      partition(entries) shouldBe (entries, Map.empty)
    }

    "drop every entry of a duplicated key and keep the others in order" in {
      val sv1 = Entry(0, "seq1", "sv1")
      val sv2 = Entry(0, "seq2", "sv2")
      val sv3 = Entry(0, "seq3", "sv3")
      val sv4CopyingSv2 = Entry(0, "seq2", "sv4")
      val (unique, duplicates) = partition(Seq(sv1, sv2, sv3, sv4CopyingSv2))
      unique shouldBe Seq(sv1, sv3)
      duplicates shouldBe Map((0L, "seq2") -> Seq(sv2, sv4CopyingSv2))
    }

    "keep the same id on different serials" in {
      val entries = Seq(Entry(0, "seq1", "sv1"), Entry(1, "seq1", "sv1"))
      partition(entries) shouldBe (entries, Map.empty)
    }
  }
}

object DsoSequencerDeduplicationTest {
  private final case class Entry(serial: Long, id: String, svName: String)
}
