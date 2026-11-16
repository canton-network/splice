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


def run_main(name: str, args: list[str]) -> tuple[int | str | None, str, str]:
    """Run a script's main() with args; return the exit code (None without SystemExit), stdout and stderr."""
    module = importlib.import_module(name)
    out, err = io.StringIO(), io.StringIO()
    code: int | str | None = None
    with (
        mock.patch.object(sys, "argv", [f"{name}.py", *args]),
        contextlib.redirect_stdout(out),
        contextlib.redirect_stderr(err),
    ):
        try:
            module.main()
        except SystemExit as e:
            code = e.code
    return code, out.getvalue(), err.getvalue()


class ArgumentValidationTest(unittest.TestCase):
    """Arguments that end up in commands or select private data are fixed or validated (PR 8488 review)."""

    GCP_OK = ["--project", "da-cn-ci", "--start", "2026-11-03T09:15:00Z", "--end", "2026-11-03T09:45:00Z"]

    def test_gcp_logs_valid(self) -> None:
        code, out, _ = run_main("gcp_logs", self.GCP_OK + ["--filter", "severity>=ERROR"])
        self.assertIsNone(code)
        self.assertIn("--project 'da-cn-ci'", out)

    def test_gcp_logs_rejects(self) -> None:
        bad = {
            "project": ["--project", "x; rm -rf ~", *self.GCP_OK[2:]],
            "start": [*self.GCP_OK[:2], "--start", "2026-11-03", *self.GCP_OK[4:]],
            "end": [*self.GCP_OK[:4], "--end", "2026-11-03T09:45:00Z; id"],
        }
        for what, args in bad.items():
            with self.subTest(arg=what):
                code, _, err = run_main("gcp_logs", args)
                self.assertEqual(code, 2)
                self.assertIn("argument --" + what, err)

    def test_fixed_repo_and_board(self) -> None:
        removed = {
            "export_board": [["--org", "x"], ["--project", "1"], ["--repo", "x/y"]],
            "set_status": [["Inbox", "Triaged", "1", "--repo", "x/y"], ["Inbox", "Triaged", "1", "--project", "1"]],
            "export_issues": [["--since", "2026-11-01", "--repo", "x/y"]],
            "close_duplicates": [["1", "2", "--comment", "c", "--repo", "x/y"]],
        }
        for name, cases in removed.items():
            for args in cases:
                with self.subTest(script=name, args=args):
                    code, _, err = run_main(name, args)
                    self.assertEqual(code, 2)
                    self.assertIn("unrecognized arguments", err)

    def test_export_ref_is_a_number(self) -> None:
        code, _, err = run_main("export_circleci", ["../x", "1"])
        self.assertEqual(code, 2)
        self.assertIn("argument ref", err)


if __name__ == "__main__":
    unittest.main()
