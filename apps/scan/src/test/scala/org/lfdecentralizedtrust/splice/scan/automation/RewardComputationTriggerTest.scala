package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

class RewardComputationTriggerTest extends AnyWordSpec with BaseTest with HasExecutionContext {

  import RewardComputationTrigger.takeFirstN

  // A lookup that succeeds for even numbers and fails for odd
  private def evenOnly(n: Int): Future[Option[String]] =
    Future.successful(if (n % 2 == 0) Some(s"r$n") else None)

  "takeFirstN" should {

    "collect up to n successful results" in {
      takeFirstN(Seq(2, 4, 6, 8), 3, evenOnly).futureValue shouldBe Seq("r2", "r4", "r6")
    }

    "skip None results without counting toward n" in {
      takeFirstN(Seq(1, 2, 3, 4, 5, 6), 2, evenOnly).futureValue shouldBe Seq("r2", "r4")
    }

    "return fewer than n if candidates are exhausted" in {
      takeFirstN(Seq(1, 2, 3), 5, evenOnly).futureValue shouldBe Seq("r2")
    }

    "return empty when n is zero" in {
      takeFirstN(Seq(2, 4), 0, evenOnly).futureValue shouldBe Seq.empty
    }

    "return empty when candidates are empty" in {
      takeFirstN(Seq.empty[Int], 5, evenOnly).futureValue shouldBe Seq.empty
    }

    "return empty when all candidates return None" in {
      takeFirstN(Seq(1, 3, 5), 3, evenOnly).futureValue shouldBe Seq.empty
    }
  }
}
