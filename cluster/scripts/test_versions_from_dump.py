# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import subprocess
import sys
import unittest
from pathlib import Path


class VersionsFromDumpTest(unittest.TestCase):
    def test_groups_chart_versions_by_namespace(self) -> None:
        script = Path(__file__).parent / "versions-from-dump.py"
        dump = {
            "deployment": {
                "resources": [
                    {"type": "other"},
                    {
                        "type": "kubernetes:helm.sh/v3:Release",
                        "outputs": {"version": "1.2.3", "status": {"namespace": "ns", "chart": "c"}},
                    },
                ]
            }
        }
        out = subprocess.run(
            [sys.executable, str(script)], input=json.dumps(dump), capture_output=True, text=True, check=True
        ).stdout
        self.assertEqual(json.loads(out), {"ns": {"c": "1.2.3"}})


if __name__ == "__main__":
    unittest.main()
