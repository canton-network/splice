# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import unittest
from typing import Optional

from select_checks import Change, parse_name_status, select


def modified(*paths: str) -> list[Change]:
    return [Change("M", p) for p in paths]


def no_file(path: str) -> Optional[str]:
    return None


class ParseTest(unittest.TestCase):
    def test_name_status(self) -> None:
        changes = parse_name_status("M\tapps/a.scala\nA\tapps/x/src/test/T.scala\n\n")
        self.assertEqual(
            changes, [Change("M", "apps/a.scala"), Change("A", "apps/x/src/test/T.scala")]
        )


class StaticTest(unittest.TestCase):
    def test_scala_only(self) -> None:
        out = select("static", modified("apps/scan/src/main/scala/A.scala"), no_file)
        self.assertEqual(out["sbt_cmd"], "; lintStaticFormat ; headerCheck")
        groups = ("python", "daml", "gha", "grafana", "base_images", "npm_names")
        self.assertEqual([g for g in groups if out[g] == "true"], [])

    def test_docs_only_skips_sbt(self) -> None:
        out = select("static", modified("README.md"), no_file)
        self.assertEqual(out["sbt_cmd"], "")

    def test_python(self) -> None:
        out = select("static", modified("scripts/foo.py"), no_file)
        self.assertEqual(out["python"], "true")
        self.assertEqual(out["sbt_cmd"], "; headerCheck")

    def test_daml(self) -> None:
        out = select("static", modified("daml/splice-amulet/daml/Splice/Amulet.daml"), no_file)
        self.assertEqual(out["daml"], "true")
        self.assertEqual(out["sbt_cmd"], "; illegalDamlReferencesCheck ; headerCheck")

    def test_package_json(self) -> None:
        out = select("static", modified("apps/package.json"), no_file)
        self.assertEqual(out["npm_names"], "true")
        self.assertEqual(out["sbt_cmd"], "; syncpackCheck")

    def test_extensionless_shell_script(self) -> None:
        first_lines = {"build-tools/cncluster": "#!/usr/bin/env bash\n"}
        out = select("static", modified("build-tools/cncluster"), first_lines.get)
        self.assertEqual(out["sbt_cmd"], "; runShellcheck ; headerCheck")

    def test_build_change_runs_all(self) -> None:
        out = select("static", modified("project/BuildCommon.scala"), no_file)
        self.assertEqual(out["sbt_cmd"], "lintStatic")
        self.assertTrue(all(out[g] == "true" for g in ("python", "daml", "gha")))


class BuildTest(unittest.TestCase):
    def test_test_config_on_added_test(self) -> None:
        out = select("build", [Change("A", "apps/scan/src/test/scala/NewTest.scala")], no_file)
        self.assertEqual(out["test_config"], "true")

    def test_no_test_config_on_modified_test(self) -> None:
        out = select("build", modified("apps/scan/src/test/scala/OldTest.scala"), no_file)
        self.assertEqual(out["test_config"], "false")

    def test_npm_lint_scala_only(self) -> None:
        out = select("build", modified("apps/scan/src/main/scala/A.scala"), no_file)
        self.assertEqual(out["npm_lint"], "")

    def test_npm_lint_per_frontend(self) -> None:
        out = select(
            "build", modified("apps/sv/frontend/src/App.tsx", "cluster/pulumi/sv/src/x.ts"), no_file
        )
        self.assertEqual(out["npm_lint"], " apps-frontends/npmLint pulumi/npmLint")

    def test_npm_lint_root_json_runs_all(self) -> None:
        out = select("build", modified("package.json"), no_file)
        self.assertEqual(
            out["npm_lint"],
            " apps-frontends/npmLint pulumi/npmLint load-tester/npmLint party-allocator/npmLint",
        )


if __name__ == "__main__":
    unittest.main()
