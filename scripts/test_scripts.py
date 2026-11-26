# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO(#8086): add real unit tests for scripts/*.py; this is only a smoke test

from typing import Any, Optional, override
import argparse
import importlib.util
import unittest
from datetime import datetime, timedelta, timezone
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


def vote_result(tracking_cid: str, completed_at: str) -> dict[str, Any]:
    return {"request": {"trackingCid": tracking_cid, "requester": "sv1"}, "completedAt": completed_at}


class FakeScan:
    """Serves the vote results endpoints from fixed lists of vote results, paged by index."""

    def __init__(self, module: Any, v0: list[dict[str, Any]], v1: Optional[list[dict[str, Any]]],
                 v1_status: int = 200) -> None:
        self.module, self.url, self.v0, self.v1, self.v1_status = module, "https://fake", v0, v1, v1_status

    def request(self, method: str, path: str, fail_fast: Any = (), **kwargs: Any) -> Optional[dict[str, Any]]:
        body = kwargs["json"]
        if path == "/v0/admin/sv/voteresults":
            start, limit = body.get("pageToken", 0), body["limit"]
            page = self.v0[start:start + limit]
            return {"dso_rules_vote_results": page, "next_page_token": start + len(page) if page else None}
        assert path == "/v1/admin/sv/voteresults"
        if self.v1_status in fail_fast:
            raise self.module.ScanUnavailable(f"HTTP {self.v1_status}")
        if self.v1 is None:
            return None
        # Opaque string tokens, like the real endpoint
        start, limit = int(body.get("page_token", "token-0").removeprefix("token-")), body["page_size"]
        items = [{"update_id": f"update-{r['request']['trackingCid']}", "event_number": 0, "vote_result": r}
                 for r in self.v1[start:start + limit]]
        return {"vote_results": items, "next_page_token": f"token-{start + len(items)}" if items else None}


class CheckAcsSnapshotsVotesTest(unittest.TestCase):
    @override
    def setUp(self) -> None:
        self.module = load_script("check-acs-snapshots.py")
        self.args = argparse.Namespace(page_size=2, max_tracked_ids=100, progress_interval=1000)
        self.cutoff = datetime(2026, 1, 10, tzinfo=timezone.utc)
        self.results = [vote_result(f"00cid{i}", f"2026-01-0{i}T00:00:00Z") for i in range(1, 6)]
        self.late = vote_result("00late", "2026-01-11T00:00:00.123456Z")

    def fetch(self, scan: FakeScan) -> Any:
        return self.module.fetch_votes(scan, self.args, self.cutoff)

    def test_pages_through_both_versions_and_ignores_votes_after_the_cutoff(self) -> None:
        votes = self.fetch(FakeScan(self.module, [self.late] + self.results, [self.late] + self.results[::-1]))
        self.assertEqual(votes.v0.count, 5)
        assert votes.v1 is not None
        self.assertEqual(votes.v1.count, 5)
        self.assertIsNone(votes.v0.error)
        self.assertTrue(self.module.compare_vote_versions({"scan": votes}))

    def test_ignores_null_fields_when_comparing_versions(self) -> None:
        with_null = [dict(r, newOptionalField=None) for r in self.results]
        votes = self.fetch(FakeScan(self.module, with_null, self.results))
        self.assertTrue(self.module.compare_vote_versions({"scan": votes}))

    def test_reports_vote_results_that_differ_between_versions(self) -> None:
        votes = self.fetch(FakeScan(self.module, self.results, self.results[:-1]))
        self.assertFalse(self.module.compare_vote_versions({"scan": votes}))

    def test_skips_v1_on_scans_that_do_not_serve_it(self) -> None:
        for scan in [FakeScan(self.module, self.results, self.results, v1_status=503),
                     FakeScan(self.module, self.results, None)]:
            votes = self.fetch(scan)
            self.assertIsNone(votes.v1)
            self.assertIsNotNone(votes.v1_unavailable)
            self.assertEqual(votes.v0.count, 5)
            self.assertTrue(self.module.compare_vote_versions({"scan": votes}))

    def test_compares_v1_across_scans_including_the_order(self) -> None:
        same = self.fetch(FakeScan(self.module, self.results, self.results))
        reordered = self.fetch(FakeScan(self.module, self.results, self.results[::-1]))
        assert same.v1 is not None and reordered.v1 is not None
        self.assertTrue(self.module.compare_digests("votes_v1", "vote results",
                                                    {"a": same.v1, "b": same.v1}, 100))
        self.assertFalse(self.module.compare_digests("votes_v1", "vote results",
                                                     {"a": same.v1, "b": reordered.v1}, 100))


class BumpWalletGatewayVersionTest(unittest.TestCase):
    @override
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

    def test_find_duplicate_matches_open_pr_for_same_versions(self) -> None:
        older = {"number": 1, "title": self.module.pr_title("1.13.0", "1.15.0"), "headRefName": "b1", "url": "u1"}
        same = {"number": 2, "title": self.module.pr_title("1.14.0", "1.16.0"), "headRefName": "b2", "url": "u2"}
        title = self.module.pr_title("1.14.0", "1.16.0")
        self.assertEqual(self.module.find_duplicate([older, same], title), same)
        self.assertIsNone(self.module.find_duplicate([older], title))
        self.assertIsNone(self.module.find_duplicate([], title))


if __name__ == "__main__":
    unittest.main()
