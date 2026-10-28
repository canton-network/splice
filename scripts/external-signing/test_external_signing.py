# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "external_signing", Path(__file__).parent / "external-signing.py"
)
assert spec is not None and spec.loader is not None
external_signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(external_signing)


class KeyNamesTest(unittest.TestCase):
    def test_key_names(self) -> None:
        self.assertEqual(
            external_signing.key_names("/keys", "alice"), ["/keys/alice.priv", "/keys/alice.pub"]
        )


if __name__ == "__main__":
    unittest.main()
