package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.data.CantonTimestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ScanVerdictIngestionServiceTest extends AnyWordSpec with Matchers {

  private def ts(micros: Long) = CantonTimestamp.ofEpochMicro(micros)

  "findMissingTrafficSummaries" should {

    "return empty when ingestion hasn't started" in {
      ScanVerdictIngestionService.findMissingTrafficSummaries(
        Seq(ts(100), ts(200)),
        Set.empty,
        None,
      ) shouldBe empty
    }

    "return empty when all verdicts have summaries" in {
      ScanVerdictIngestionService.findMissingTrafficSummaries(
        Seq(ts(100), ts(200)),
        Set(ts(100), ts(200)),
        Some(50),
      ) shouldBe empty
    }

    "flag missing summaries for verdicts at or after start" in {
      ScanVerdictIngestionService.findMissingTrafficSummaries(
        Seq(ts(100), ts(200)),
        Set(ts(100)),
        Some(50),
      ) shouldBe Seq(ts(200))
    }

    "ignore missing summaries for verdicts before start" in {
      ScanVerdictIngestionService.findMissingTrafficSummaries(
        Seq(ts(100), ts(200), ts(300)),
        Set(ts(300)),
        Some(200),
      ) shouldBe Seq(ts(200))
    }

    "return empty when all verdicts are before start" in {
      ScanVerdictIngestionService.findMissingTrafficSummaries(
        Seq(ts(100), ts(200)),
        Set.empty,
        Some(300),
      ) shouldBe empty
    }
  }
}
