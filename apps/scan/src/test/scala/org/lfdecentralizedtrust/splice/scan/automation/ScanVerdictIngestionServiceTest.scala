package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.mediator.admin.v30
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level.INFO

class ScanVerdictIngestionServiceTest extends AnyWordSpec with BaseTest {

  private def ts(micros: Long) = CantonTimestamp.ofEpochMicro(micros)

  private def mkVerdict(updateId: String, accepted: Boolean): v30.Verdict =
    v30.Verdict.defaultInstance.copy(
      updateId = updateId,
      verdict =
        if (accepted) v30.VerdictResult.VERDICT_RESULT_ACCEPTED
        else v30.VerdictResult.VERDICT_RESULT_REJECTED,
    )

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

  "findDuplicateUpdateIds" should {

    "return empty when all update ids are distinct" in {
      ScanVerdictIngestionService.findDuplicateUpdateIds(
        Seq(mkVerdict("a", true), mkVerdict("b", false))
      ) shouldBe empty
    }

    "return only the update ids that appear more than once" in {
      val result = ScanVerdictIngestionService.findDuplicateUpdateIds(
        Seq(mkVerdict("a", true), mkVerdict("b", false), mkVerdict("a", false))
      )
      result.keySet shouldBe Set("a")
      result("a").map(_._2) shouldBe Seq(0, 2)
    }
  }

  "duplicatesContainSubsequentAccept" should {

    "return false when no duplicate is an accept" in {
      val duplicates = ScanVerdictIngestionService.findDuplicateUpdateIds(
        Seq(mkVerdict("a", false), mkVerdict("a", false))
      )
      ScanVerdictIngestionService.duplicatesContainSubsequentAccept(duplicates) shouldBe false
    }

    "return true when a duplicate group contains an accept after another verdict" in {
      val duplicates = ScanVerdictIngestionService.findDuplicateUpdateIds(
        Seq(mkVerdict("a", false), mkVerdict("a", true))
      )
      ScanVerdictIngestionService.duplicatesContainSubsequentAccept(duplicates) shouldBe true
    }

    "return false when a duplicate group contains an accept before another verdict" in {
      val duplicates = ScanVerdictIngestionService.findDuplicateUpdateIds(
        Seq(mkVerdict("a", true), mkVerdict("a", false))
      )
      ScanVerdictIngestionService.duplicatesContainSubsequentAccept(duplicates) shouldBe false
    }

    "ignore an accept that is not part of a duplicate group" in {
      // "a" is accepted but unique; only "b" is duplicated (both rejected).
      val duplicates = ScanVerdictIngestionService.findDuplicateUpdateIds(
        Seq(mkVerdict("a", true), mkVerdict("b", false), mkVerdict("b", false))
      )
      ScanVerdictIngestionService.duplicatesContainSubsequentAccept(duplicates) shouldBe false
    }
  }

  "logDuplicateUpdateIds" should {

    "not log when there are no duplicates" in {
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(INFO))(
        ScanVerdictIngestionService.logDuplicateUpdateIds(
          Seq(mkVerdict("a", true), mkVerdict("b", true)),
          logger,
        ),
        _ shouldBe empty,
      )
    }

    "log at info level when no duplicate is an accept" in {
      loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(INFO))(
        ScanVerdictIngestionService.logDuplicateUpdateIds(
          Seq(mkVerdict("a", false), mkVerdict("a", false)),
          logger,
        ),
        _.infoMessage should include("Duplicate verdicts:"),
      )
    }

    "log at warning level when a duplicate is a subsequent accept" in {
      loggerFactory.assertLogs(
        ScanVerdictIngestionService.logDuplicateUpdateIds(
          Seq(mkVerdict("a", false), mkVerdict("a", true)),
          logger,
        ),
        _.warningMessage should endWith("Duplicate verdicts contains a subsequent accept."),
      )
    }

    "log at info level when a duplicate is not a subsequent accept" in {
      loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(INFO))(
        ScanVerdictIngestionService.logDuplicateUpdateIds(
          Seq(mkVerdict("a", true), mkVerdict("a", false)),
          logger,
        ),
        _.infoMessage should include("Duplicate verdicts:"),
      )
    }
  }
}
