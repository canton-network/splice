# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO(#8092): add real unit tests for docs/src; this is only a smoke test

import unittest
from unittest import mock

from sphinx.application import Sphinx

import release_directive


class ReleaseDirectiveTest(unittest.TestCase):
    def test_setup_registers_directive(self) -> None:
        app = mock.create_autospec(Sphinx, instance=True)
        release_directive.setup(app)
        app.add_directive.assert_called_once_with(
            "release-notes", release_directive.ReleaseNotesDirective
        )


if __name__ == "__main__":
    unittest.main()
