# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO(#8089): add real unit tests for build-tools; this is only a smoke test

import unittest

import delete_ghcr_package_version


class GetNextPageUrlTest(unittest.TestCase):
    def test_next_link(self) -> None:
        headers = {"Link": '<https://x/prev>; rel="prev", <https://x/next>; rel="next"'}
        self.assertEqual(delete_ghcr_package_version.get_next_page_url(headers), "https://x/next")

    def test_no_link(self) -> None:
        self.assertIsNone(delete_ghcr_package_version.get_next_page_url({}))


if __name__ == "__main__":
    unittest.main()
