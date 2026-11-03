# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO(#8091): add real unit tests for .github/actions/scripts; this is only a smoke test

import py_compile
import tempfile
import unittest
from pathlib import Path


class PublishGhaMetricsTest(unittest.TestCase):
    def test_compiles(self) -> None:
        # The script starts a server at import time, so only check that it compiles.
        script = Path(__file__).parent / "publish_gha_metrics.py"
        with tempfile.TemporaryDirectory() as tmp:
            py_compile.compile(str(script), cfile=str(Path(tmp) / "out.pyc"), doraise=True)


if __name__ == "__main__":
    unittest.main()
