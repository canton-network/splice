# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import contextlib
import io
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
        self.assertEqual(out["sbt_cmd"], "lintStaticFormat headerCheck")
        groups = ("python", "daml", "gha", "grafana", "base_images", "npm_names")
        self.assertEqual([g for g in groups if out[g] == "true"], [])

    def test_docs_only_skips_sbt(self) -> None:
        out = select("static", modified("README.md"), no_file)
        self.assertEqual(out["sbt_cmd"], "")

    def test_python(self) -> None:
        out = select("static", modified("scripts/foo.py"), no_file)
        self.assertEqual(out["python"], "true")
        self.assertEqual(out["sbt_cmd"], "headerCheck")

    def test_daml(self) -> None:
        out = select("static", modified("daml/splice-amulet/daml/Splice/Amulet.daml"), no_file)
        self.assertEqual(out["daml"], "true")
        self.assertEqual(out["sbt_cmd"], "illegalDamlReferencesCheck headerCheck")

    def test_package_json(self) -> None:
        out = select("static", modified("apps/package.json"), no_file)
        self.assertEqual(out["npm_names"], "true")
        self.assertEqual(out["sbt_cmd"], "syncpackCheck")

    def test_extensionless_shell_script(self) -> None:
        first_lines = {"build-tools/cncluster": "#!/usr/bin/env bash\n"}
        out = select("static", modified("build-tools/cncluster"), first_lines.get)
        self.assertEqual(out["sbt_cmd"], "runShellcheck headerCheck")

    def test_extensionless_python_script_in_known_dir(self) -> None:
        first_lines = {".github/actions/scripts/foo": "#!/usr/bin/env python3\n"}
        out = select("static", modified(".github/actions/scripts/foo"), first_lines.get)
        self.assertEqual(out["python"], "true")
        self.assertEqual(out["sbt_cmd"], "")

    def test_extensionless_python_script_is_not_unknown(self) -> None:
        first_lines = {"scripts/foo": "#!/usr/bin/env python\n"}
        out = select("static", modified("scripts/foo"), first_lines.get)
        self.assertEqual(out["python"], "true")
        self.assertEqual(out["sbt_cmd"], "")

    def test_unknown_file_runs_all_with_notice(self) -> None:
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            out = select("static", modified("apps/scan/src/main/proto/x.proto"), no_file)
        self.assertEqual(out["sbt_cmd"], "lintStatic")
        self.assertIn(
            "::notice file=apps/scan/src/main/proto/x.proto::No check selection rule",
            stderr.getvalue(),
        )

    def test_other_python_shebang_is_not_python_checked(self) -> None:
        first_lines = {".github/actions/scripts/foo": "#!/usr/bin/python3\n"}
        out = select("static", modified(".github/actions/scripts/foo"), first_lines.get)
        self.assertEqual(out["python"], "false")

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


class TestDefinitionTest(unittest.TestCase):
    test_file = "apps/scan/src/test/scala/FooTest.scala"

    def build(self, diff: Optional[str]) -> str:
        return select("build", modified(self.test_file), no_file, diff)["test_config"]

    def test_new_class_in_existing_file(self) -> None:
        diff = "--- a/x\n+++ b/x\n@@ -10,0 +11 @@\n+class BarTest extends StoreTestBase {\n"
        self.assertEqual(self.build(diff), "true")

    def test_class_renamed_in_file(self) -> None:
        diff = "@@ -3 +3 @@\n-final class OldTest extends X\n+final class NewTest extends X\n"
        self.assertEqual(self.build(diff), "true")

    def test_removed_object(self) -> None:
        self.assertEqual(self.build("@@ -5 +4,0 @@\n-  object Helpers {\n"), "true")

    def test_body_change_only(self) -> None:
        diff = "--- a/x\n+++ b/x\n@@ -20 +20 @@\n-    x shouldBe 1\n+    x shouldBe 2\n"
        self.assertEqual(self.build(diff), "false")

    def test_diff_unavailable_runs_the_check(self) -> None:
        self.assertEqual(self.build(None), "true")


ALL_LINTS = " apps-frontends/npmLint pulumi/npmLint load-tester/npmLint party-allocator/npmLint"


class UnknownAndDirectoryTest(unittest.TestCase):
    def test_lint_config_without_suffix_in_frontend_runs_its_lint(self) -> None:
        out = select("build", modified("apps/sv/frontend/.prettierrc"), no_file)
        self.assertEqual(out["npm_lint"], " apps-frontends/npmLint")

    def test_new_file_type_in_frontend_runs_its_lint(self) -> None:
        out = select("build", modified("apps/wallet/frontend/src/Widget.vue"), no_file)
        self.assertEqual(out["npm_lint"], " apps-frontends/npmLint")

    def test_frontend_config_is_known_for_static(self) -> None:
        out = select("static", modified("apps/sv/frontend/.prettierrc"), no_file)
        self.assertEqual(out["sbt_cmd"], "")

    def test_unknown_file_runs_all_static(self) -> None:
        out = select("static", modified("apps/scan/weird.foo"), no_file)
        self.assertEqual(out["sbt_cmd"], "lintStatic")
        self.assertEqual(out["python"], "true")

    def test_unknown_root_file_runs_all_build(self) -> None:
        out = select("build", modified("rust-toolchain.toml"), no_file)
        self.assertEqual(out["npm_lint"], ALL_LINTS)
        self.assertEqual(out["test_config"], "true")

    def test_unknown_file_next_to_known_files_runs_all(self) -> None:
        changes = modified("apps/scan/src/main/scala/A.scala", "proto/x.proto")
        out = select("build", changes, no_file)
        self.assertEqual(out["npm_lint"], ALL_LINTS)

    def test_sql_migration_is_known(self) -> None:
        path = (
            "apps/common/src/main/resources/db/migration/canton-network/postgres/stable/V086__x.sql"
        )
        static = select("static", modified(path), no_file)
        build = select("build", modified(path), no_file)
        self.assertEqual((static["sbt_cmd"], build["npm_lint"]), ("", ""))

    def test_helm_values_run_pulumi_lint(self) -> None:
        out = select("build", modified("cluster/helm/splice-scan/values.yaml"), no_file)
        self.assertEqual(out["npm_lint"], " pulumi/npmLint")


if __name__ == "__main__":
    unittest.main()
