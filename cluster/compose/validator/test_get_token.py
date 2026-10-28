# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import subprocess
import sys
import unittest
from pathlib import Path

import jwt


class GetTokenTest(unittest.TestCase):
    def test_token_for_user(self) -> None:
        script = Path(__file__).parent / "get-token.py"
        env = {**os.environ, "VALIDATOR_AUTH_AUDIENCE": "aud"}
        out = subprocess.run(
            [sys.executable, str(script), "alice"], capture_output=True, text=True, check=True, env=env
        ).stdout.strip()
        claims = jwt.decode(out, "unsafe", algorithms=["HS256"], audience="aud")
        self.assertEqual(claims["sub"], "alice")


if __name__ == "__main__":
    unittest.main()
