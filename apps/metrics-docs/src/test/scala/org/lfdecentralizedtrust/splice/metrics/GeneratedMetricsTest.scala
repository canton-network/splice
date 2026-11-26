// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.metrics

import com.daml.metrics.api.MetricQualification
import com.digitalasset.canton.metrics.MetricDoc
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GeneratedMetricsTest extends AnyWordSpec with Matchers {

  private def item(name: String, summary: String, description: String): MetricDoc.Item =
    MetricDoc.Item(name, summary, description, "gauge", MetricQualification.Debug, Map.empty)

  "rstText" should {
    "escape RST inline markup" in {
      GeneratedMetrics.rstText("`code` *bold* ref_ |sub| :role: back\\slash") shouldBe
        "\\`code\\` \\*bold\\* ref\\_ \\|sub\\| \\:role\\: back\\\\slash"
    }

    "join lines so a description stays one paragraph of its bullet" in {
      GeneratedMetrics.rstText("first line\n\n  second line  ") shouldBe "first line second line"
    }

    "leave plain text unchanged" in {
      GeneratedMetrics.rstText("Number of updates ingested per second.") shouldBe
        "Number of updates ingested per second."
    }
  }

  "renderMetric" should {
    "underline the escaped name with its full length" in {
      val rendered = GeneratedMetrics(Nil, Nil, Nil, Nil)
        .renderMetric(item("splice.store.last_ingested", "summary", "description"))
      val lines = rendered.split("\n")
      lines(0) shouldBe "splice.store.last\\_ingested"
      lines(1) shouldBe "^" * lines(0).length
    }

    "escape markup in the summary and description" in {
      val rendered = GeneratedMetrics(Nil, Nil, Nil, Nil)
        .renderMetric(item("m", "uses `x`", "ends with::\nnext"))
      rendered should include("* **Summary**: uses \\`x\\`")
      rendered should include("* **Description**: ends with\\:\\: next")
    }
  }
}
