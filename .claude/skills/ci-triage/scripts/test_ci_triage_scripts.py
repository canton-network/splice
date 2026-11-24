# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Smoke test only: every script imports, and every argparse script parses --help without side effects.

import argparse
import contextlib
import os
import tempfile
import importlib
import io
import pathlib
import shlex
import sys
import unittest
from unittest import mock

SCRIPTS = sorted(
    p.stem for p in pathlib.Path(__file__).resolve().parent.glob("*.py") if not p.stem.startswith("test_")
)
# Has no argparse: main() probes the network straight away.
NO_HELP = {"probe_access"}
# Shared modules, not scripts.
LIBS = {"export_dir"}


class ScriptsSmokeTest(unittest.TestCase):
    def test_all_scripts_found(self) -> None:
        self.assertIn("set_status", SCRIPTS)

    def test_import(self) -> None:
        for name in SCRIPTS:
            if name in LIBS:
                continue
            with self.subTest(script=name):
                self.assertTrue(callable(importlib.import_module(name).main))

    def test_help(self) -> None:
        for name in SCRIPTS:
            if name in NO_HELP | LIBS:
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
        code, out, _ = run_main("gcp_logs", self.GCP_OK + ["--filter", 'resource.labels.x="a b"'])
        self.assertIsNone(code)
        command = out.splitlines()[-1]
        self.assertEqual(shlex.split(command)[:6], [
            "gcloud", "logging", "read",
            'timestamp>="2026-11-03T09:15:00Z" AND timestamp<="2026-11-03T09:45:00Z" AND (resource.labels.x="a b")',
            "--project", "da-cn-ci"])

    def test_gcp_logs_rejects_quote_and_backslash(self) -> None:
        for flt in ["a='b'", "a=b\\c"]:
            with self.subTest(filter=flt):
                code, _, _ = run_main("gcp_logs", self.GCP_OK + ["--filter", flt])
                self.assertIn("single quotes or backslashes", str(code))

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
        for name, args in {
            "export_circleci": ["../x", "1"],
            "export_gcp_logs": ["../x", *self.GCP_OK, "--query", "q", "f"],
        }.items():
            with self.subTest(script=name):
                code, _, err = run_main(name, args)
                self.assertEqual(code, 2)
                self.assertIn("argument ref", err)

    def test_export_gcp_logs_rejects(self) -> None:
        ok = ["1", *self.GCP_OK, "--query", "q", "f"]
        bad = {
            "--project": ["1", "--project", "x;y", *self.GCP_OK[2:], "--query", "q", "f"],
            "--start": ["1", *self.GCP_OK[:2], "--start", "yesterday", *self.GCP_OK[4:], "--query", "q", "f"],
            "--cluster": [*ok, "--cluster", "../x"],
            "query name": ["1", *self.GCP_OK, "--query", "../../x", "f"],
        }
        for what, args in bad.items():
            with self.subTest(arg=what):
                code, _, err = run_main("export_gcp_logs", args)
                self.assertEqual(code, 2)
                self.assertIn(what, err)

    def test_other_choices_and_types(self) -> None:
        for name, args, msg in [
            ("export_board", ["--status", "Closed"], "argument --status"),
            ("export_issues", ["--since", "last week"], "argument --since"),
        ]:
            with self.subTest(script=name):
                code, _, err = run_main(name, args)
                self.assertEqual(code, 2)
                self.assertIn(msg, err)

    def test_no_output_location_arguments(self) -> None:
        removed = {
            "export_board": ["--out-dir", "/tmp/x"],
            "export_issues": ["--since", "2026-11-01", "--out", "/tmp/x"],
            "deployment_runs": ["--out", "/tmp/x"],
            "export_circleci": ["1", "2", "--out", "/tmp/x"],
        }
        for name, args in removed.items():
            with self.subTest(script=name):
                code, _, err = run_main(name, args)
                self.assertEqual(code, 2)
                self.assertIn("unrecognized arguments", err)
        code, _, err = run_main("export_circleci", ["1", "2", "--slug", "x/y"])
        self.assertEqual(code, 2)

    def test_export_root_from_splice_root(self) -> None:
        export_dir = importlib.import_module("export_dir")
        with mock.patch.dict(os.environ, {"SPLICE_ROOT": "/checkout"}):
            self.assertEqual(export_dir.export_root(), pathlib.Path("/checkout/log/ci-triage-export"))
            self.assertEqual(export_dir.ref_dir(5), pathlib.Path("/checkout/log/ci-triage-export/5"))
        with mock.patch.dict(os.environ, {"SPLICE_ROOT": ""}), self.assertRaises(SystemExit):
            export_dir.export_root()

    def test_comment_file(self) -> None:
        comment_file = importlib.import_module("close_duplicates").comment_file
        with tempfile.TemporaryDirectory() as tmp:
            good = pathlib.Path(tmp, "clone", "ci-triage", "rounds", "r1", "comments", "11.md")
            good.parent.mkdir(parents=True)
            good.write_text("body")
            self.assertEqual(comment_file(str(good)), good.resolve())
            secret = pathlib.Path(tmp, "hosts.yml")
            secret.write_text("token")
            link = good.parent / "12.md"
            os.symlink(secret, link)
            elsewhere = pathlib.Path(tmp, "clone", "ci-triage", "notes.md")
            elsewhere.write_text("x")
            for bad in (secret, link, elsewhere, good.parent / "missing.md"):
                with self.subTest(path=str(bad)), self.assertRaises(argparse.ArgumentTypeError):
                    comment_file(str(bad))


if __name__ == "__main__":
    unittest.main()
