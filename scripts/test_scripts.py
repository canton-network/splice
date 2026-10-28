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


if __name__ == "__main__":
    unittest.main()
