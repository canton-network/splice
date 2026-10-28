# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "restart_watchdog", Path(__file__).parent / "restart-watchdog.py"
)
assert spec is not None and spec.loader is not None
restart_watchdog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restart_watchdog)


class ParseSamplesTest(unittest.TestCase):
    def test_labelled_and_unlabelled(self) -> None:
        text = 'm{type="a"} 3\nm 5\nother 7\n'
        self.assertEqual(
            restart_watchdog.parse_samples(text, "m", {}, "test"),
            {(("type", "a"),): 3.0, (): 5.0},
        )
        self.assertEqual(
            restart_watchdog.parse_samples(text, "m", {"type": "a"}, "test"),
            {(("type", "a"),): 3.0},
        )


class WatchdogEvaluateTest(unittest.TestCase):
    def test_negative_rate_resets(self) -> None:
        watchdog = restart_watchdog.Watchdog(None)
        self.assertIsNone(watchdog.evaluate(10.0, (0.0, -1.0, 0.0)))
        self.assertIsNone(watchdog.evaluate(10.0, (0.0, 0.0, -1.0)))


if __name__ == "__main__":
    unittest.main()
