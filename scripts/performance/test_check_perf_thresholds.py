# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import unittest

import check_perf_thresholds


class ExtractThresholdTest(unittest.TestCase):
    def test_max(self) -> None:
        self.assertEqual(check_perf_thresholds.extract_threshold({"max": 5}), 5.0)

    def test_unusable(self) -> None:
        self.assertIsNone(check_perf_thresholds.extract_threshold({}))
        self.assertIsNone(check_perf_thresholds.extract_threshold({"max": "x"}))
        self.assertIsNone(check_perf_thresholds.extract_threshold(3))


if __name__ == "__main__":
    unittest.main()
