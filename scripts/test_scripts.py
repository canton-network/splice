# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO(#8086): add real unit tests for scripts/*.py; this is only a smoke test

import importlib.util
import unittest
from datetime import timedelta
from pathlib import Path
from types import ModuleType


def load_script(filename: str) -> ModuleType:
    """Import a script whose file name is not a valid module name."""
    spec = importlib.util.spec_from_file_location(
        filename.replace("-", "_").removesuffix(".py"), Path(__file__).parent / filename
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class InitialPackageConfigTest(unittest.TestCase):
    def test_to_tag_name(self) -> None:
        module = load_script("initial-package-config.py")
        self.assertEqual(
            module.to_tag_name("splice-amulet", "0.1.19"),
            "org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19",
        )


class CheckAcsSnapshotsTest(unittest.TestCase):
    def test_parse_duration(self) -> None:
        module = load_script("check-acs-snapshots.py")
        self.assertEqual(module.parse_duration("30s"), timedelta(seconds=30))
        self.assertEqual(module.parse_duration("2h"), timedelta(hours=2))


class BumpWalletGatewayVersionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.module = load_script("bump-wallet-gateway-version.py")

    def test_release_versions_skips_backports_and_strips_v(self) -> None:
        self.assertEqual(
            self.module.release_versions(["v1.11.3", "v1.11.3-backport.3", "1.12.0", "latest", "v1.2"]),
            {"1.11.3", "1.12.0"},
        )

    def test_latest_common_version_requires_image_and_chart(self) -> None:
        self.assertEqual(
            self.module.latest_common_version(["v1.9.0", "v1.10.0", "v1.13.0"], ["1.9.0", "1.10.0", "1.12.0"]),
            "1.10.0",
        )

    def test_latest_common_version_compares_numerically(self) -> None:
        self.assertEqual(self.module.latest_common_version(["v1.9.0", "v1.10.0"], ["1.9.0", "1.10.0"]), "1.10.0")

    def test_latest_common_version_none_when_disjoint(self) -> None:
        self.assertIsNone(self.module.latest_common_version(["v1.0.0"], ["2.0.0"]))

    def test_update_compose_env(self) -> None:
        env = "FOO=1\nWALLET_GATEWAY_VERSION=1.13.0\n# comment\nPORTFOLIO_VERSION=1.14.0\n"
        self.assertEqual(
            self.module.update_compose_env(env, "1.15.0", "1.16.0"),
            "FOO=1\nWALLET_GATEWAY_VERSION=1.15.0\n# comment\nPORTFOLIO_VERSION=1.16.0\n",
        )

    def test_update_pulumi_config_keeps_quoting(self) -> None:
        config = "enabled: false\nversion: '1.13.0'\nportfolioVersion: '1.14.0'\n"
        self.assertEqual(
            self.module.update_pulumi_config(config, "1.15.0", "1.16.0"),
            "enabled: false\nversion: '1.15.0'\nportfolioVersion: '1.16.0'\n",
        )

    def test_update_fails_on_missing_pin(self) -> None:
        with self.assertRaises(ValueError):
            self.module.update_compose_env("FOO=1\n", "1.15.0", "1.16.0")

    def test_read_compose_version(self) -> None:
        env = "WALLET_GATEWAY_VERSION=1.13.0\nPORTFOLIO_VERSION=1.14.0\n"
        self.assertEqual(self.module.read_compose_version(env, "PORTFOLIO_VERSION"), "1.14.0")


if __name__ == "__main__":
    unittest.main()
