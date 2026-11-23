# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from contextlib import redirect_stdout
from io import StringIO
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from typing import override

from git import Repo

from test_scripts import load_script

checks = load_script("python-checks.py")


class PythonChecksTest(unittest.TestCase):
    @override
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.repo = Repo.init(self.root)
        self.addCleanup(self.repo.close)
        self.config = checks.Config((), {}, {})

    def write(self, name: str, content: str, *, tracked: bool = True) -> Path:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        if tracked:
            self.repo.index.add([name])
        return path

    def test_empty_and_outside_selections_fail(self) -> None:
        self.write("covered.py", "")
        for target in (self.root.parent, self.root / "missing"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                checks.python_files(self.root, self.config, target)

    def test_selection_recognizes_only_tracked_extensionless_python_shebangs(self) -> None:
        self.write(".py", "")
        self.write("regular.py", "")
        self.write("scripts/python", "#!/usr/bin/env python\n")
        self.write("scripts/python3", "#!/usr/bin/env python3\n")
        self.write("scripts/shell", "#!/usr/bin/env bash\n")
        self.write("scripts/plain", "print('not a shebang')\n")
        self.write("scripts/later", "\n#!/usr/bin/env python3\n")
        self.write("scripts/other-version", "#!/usr/bin/env python3.14\n")
        self.write("scripts/other.txt", "#!/usr/bin/env python3\n")
        self.write("scripts/untracked", "#!/usr/bin/env python3\n", tracked=False)
        self.write("excluded/ignored", "#!/usr/bin/env python3\n")
        self.write("scripts/binary", "").write_bytes(b"\xff" * 256)
        (self.root / "scripts/symlink").symlink_to(self.root / "missing")
        self.repo.index.add(["scripts/symlink"])
        config = checks.Config(("excluded/**",), {}, {})
        self.assertEqual(
            checks.python_files(self.root, config, None),
            [".py", "regular.py", "scripts/python", "scripts/python3"],
        )
        self.assertEqual(
            checks.python_files(self.root, config, self.root / "scripts/python3"),
            ["scripts/python3"],
        )

    def test_root_relative_exclusions_include_nested_files(self) -> None:
        self.write("keep.py", "")
        self.write("vendor/nested/skip.py", "")
        self.write("nested/vendor/keep.py", "")
        config = checks.Config(("vendor/**",), {}, {})
        self.assertEqual(
            checks.python_files(self.root, config, None),
            ["keep.py", "nested/vendor/keep.py"],
        )

    def test_selection_skips_deleted_extensionless_files(self) -> None:
        self.write("keep.py", "")
        self.write("command", "#!/usr/bin/env python3\n")
        self.write("LICENSE", "license text\n").unlink()
        self.write("nested/README", "documentation\n").unlink()
        self.write("missing.py", "").unlink()
        self.assertEqual(
            checks.python_files(self.root, self.config, None),
            ["command", "keep.py", "missing.py"],
        )

    def test_selection_reports_unreadable_extensionless_files(self) -> None:
        self.write("command", "#!/usr/bin/env python3\n")
        with patch.object(checks, "Repo", return_value=self.repo), patch.object(
            Path, "open", side_effect=PermissionError("unreadable"),
        ):
            with self.assertRaises(PermissionError):
                checks.python_files(self.root, self.config, None)

    def test_check_isolates_directories_and_aggregates_failures(self) -> None:
        config = checks.Config((), {"first": ("lib",)}, {"warn-unreachable": "Example exception"})
        with patch.object(checks.subprocess, "run", side_effect=[
            subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0),
        ]) as run, redirect_stdout(StringIO()):
            self.assertEqual(checks.check(
                self.root, config, ["first/a.py", "first/b.py", "second/a.py"],
            ), 1)
        first, second = run.call_args_list
        command = first.args[0]
        self.assertIn("--config-file=", command)
        self.assertIn("--strict", command)
        self.assertIn("explicit-override", command)
        self.assertNotIn("--warn-unreachable", command)
        self.assertEqual(command[-3], "--")
        self.assertEqual(command[-2:], ["first/a.py", "first/b.py"])
        self.assertEqual(first.kwargs["env"]["MYPYPATH"], os.pathsep.join(["first", "lib"]))
        self.assertEqual(second.kwargs["env"]["MYPYPATH"], "second")

    def test_test_only_runs_selected_tracked_test_modules(self) -> None:
        self.write("-tests/test_ok.py",
                   "import unittest\nclass Test(unittest.TestCase):\n    def test_ok(self) -> None:\n        pass\n")
        self.write("-tests/test_untracked.py", "raise RuntimeError('untracked')", tracked=False)
        self.write("-tests/test_excluded.py", "raise RuntimeError('excluded')")
        self.write("-tests/test_command", "#!/usr/bin/env python3\nraise RuntimeError('not a module')\n")
        config = checks.Config(("-tests/test_excluded.py",), {}, {})
        with redirect_stdout(StringIO()):
            self.assertEqual(checks.test(
                self.root, checks.python_files(self.root, config, None),
            ), 0)
        with self.assertRaisesRegex(ValueError, "no tests"):
            checks.test(self.root, ["source.py"])
        with self.assertRaisesRegex(ValueError, "no tests"):
            checks.test(self.root, ["-tests/test_command"])

    def test_test_aggregates_failures(self) -> None:
        with patch.object(checks.subprocess, "run", side_effect=[
            subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0),
        ]) as run, redirect_stdout(StringIO()):
            self.assertEqual(checks.test(self.root, ["a/test_one.py", "b/test_two.py"]), 1)
        self.assertEqual(run.call_count, 2)


if __name__ == "__main__":
    unittest.main()
