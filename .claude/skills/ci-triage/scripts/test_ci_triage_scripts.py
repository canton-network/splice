# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Smoke test only: every script imports, and every argparse script parses --help without side effects.

import contextlib
import importlib
import io
import pathlib
import sys
import unittest
from unittest import mock

SCRIPTS = sorted(
    p.stem for p in pathlib.Path(__file__).resolve().parent.glob("*.py") if not p.stem.startswith("test_")
)
# Has no argparse: main() probes the network straight away.
NO_HELP = {"probe_access"}


class ScriptsSmokeTest(unittest.TestCase):
    def test_all_scripts_found(self) -> None:
        self.assertIn("set_status", SCRIPTS)

    def test_import(self) -> None:
        for name in SCRIPTS:
            with self.subTest(script=name):
                self.assertTrue(callable(importlib.import_module(name).main))

    def test_help(self) -> None:
        for name in SCRIPTS:
            if name in NO_HELP:
                continue
            with self.subTest(script=name):
                module = importlib.import_module(name)
                out = io.StringIO()
                with (
                    mock.patch.object(sys, "argv", [f"{name}.py", "--help"]),
                    contextlib.redirect_stdout(out),
                    self.assertRaises(SystemExit) as exit_,
                ):
                    module.main()
                self.assertEqual(exit_.exception.code, 0)
                self.assertIn("usage:", out.getvalue())


if __name__ == "__main__":
    unittest.main()
