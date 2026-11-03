# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO(#8092): add real unit tests for the sarif-unused-symbols skill; this is only a smoke test

import unittest

import sarif_unused


class ClassifyTest(unittest.TestCase):
    def test_private_function(self) -> None:
        self.assertEqual(sarif_unused.classify("Private function 'f' is never used"), ("def", True))

    def test_parameter(self) -> None:
        self.assertEqual(sarif_unused.classify("Parameter 'x' is never used"), ("param", False))


if __name__ == "__main__":
    unittest.main()
