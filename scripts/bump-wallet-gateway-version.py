#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Bump the pinned Wallet Gateway and Portfolio UI versions to the latest upstream releases and open a PR."""

import argparse
import json
import os
import re
import subprocess
import sys
from collections.abc import Iterable
from datetime import date
from pathlib import Path

COMPOSE_ENV = Path("cluster/compose/validator/.env")
PULUMI_CONFIG = Path("cluster/configs/shared/wallet_gateway.yaml")
UPSTREAM = {"wallet-gateway": "wallet-gateway", "portfolio": "splice-portfolio"}
DEFAULT_REVIEWERS = "isegall-da,pawelperek-da"

# final releases only: v1.2.3 for images, 1.2.3 for charts; backports like v1.11.3-backport.3 are skipped
RELEASE_TAG_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def release_versions(tags: Iterable[str]) -> set[str]:
    return {m.expand(r"\1.\2.\3") for tag in tags if (m := RELEASE_TAG_RE.match(tag))}


def latest_common_version(image_tags: Iterable[str], chart_tags: Iterable[str]) -> str | None:
    """Highest version published both as a docker image and as a helm chart."""
    common = release_versions(image_tags) & release_versions(chart_tags)
    return max(common, key=lambda v: tuple(int(part) for part in v.split(".")), default=None)


def read_compose_version(compose_env: str, key: str) -> str:
    match = re.search(rf"^{key}=(.*)$", compose_env, re.MULTILINE)
    if not match:
        raise ValueError(f"{key} not found in {COMPOSE_ENV}")
    return match.group(1)


def replace_line(content: str, pattern: str, replacement: str) -> str:
    new_content, count = re.subn(pattern, replacement, content, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"expected exactly one line matching {pattern!r}, found {count}")
    return new_content


def update_compose_env(compose_env: str, wg_version: str, portfolio_version: str) -> str:
    compose_env = replace_line(compose_env, r"^WALLET_GATEWAY_VERSION=.*$", f"WALLET_GATEWAY_VERSION={wg_version}")
    return replace_line(compose_env, r"^PORTFOLIO_VERSION=.*$", f"PORTFOLIO_VERSION={portfolio_version}")


def update_pulumi_config(pulumi_config: str, wg_version: str, portfolio_version: str) -> str:
    pulumi_config = replace_line(pulumi_config, r"^version: .*$", f"version: '{wg_version}'")
    return replace_line(pulumi_config, r"^portfolioVersion: .*$", f"portfolioVersion: '{portfolio_version}'")


def run(*args: str, capture: bool = False) -> str:
    result = subprocess.run(args, check=True, text=True, capture_output=capture)
    return result.stdout if capture else ""


def list_tags(image: str) -> list[str]:
    tags: list[str] = json.loads(run("skopeo", "list-tags", "--no-creds", f"docker://{image}", capture=True))["Tags"]
    return tags


def latest_upstream_version(repo: str) -> str:
    base = f"ghcr.io/digital-asset/{repo}"
    version = latest_common_version(list_tags(f"{base}/docker/{repo}"), list_tags(f"{base}/helm/{repo}"))
    if version is None:
        sys.exit(f"No release of {repo} is published as both an image and a chart")
    return version


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wallet-gateway-version", default=os.environ.get("WALLET_GATEWAY_VERSION") or None)
    parser.add_argument("--portfolio-version", default=os.environ.get("PORTFOLIO_VERSION") or None)
    parser.add_argument("--dry-run", action="store_true", help="update the pins but do not commit, push or open a PR")
    parser.add_argument("--reviewers", default=DEFAULT_REVIEWERS)
    args = parser.parse_args()

    os.chdir(os.environ["SPLICE_ROOT"])

    wg_version = args.wallet_gateway_version or latest_upstream_version(UPSTREAM["wallet-gateway"])
    portfolio_version = args.portfolio_version or latest_upstream_version(UPSTREAM["portfolio"])

    compose_env = COMPOSE_ENV.read_text()
    current_wg = read_compose_version(compose_env, "WALLET_GATEWAY_VERSION")
    current_portfolio = read_compose_version(compose_env, "PORTFOLIO_VERSION")
    print(f"Wallet Gateway: current {current_wg}, target {wg_version}")
    print(f"Portfolio UI:   current {current_portfolio}, target {portfolio_version}")

    if (current_wg, current_portfolio) == (wg_version, portfolio_version):
        print("Wallet Gateway and Portfolio UI versions are up to date.")
        return

    COMPOSE_ENV.write_text(update_compose_env(compose_env, wg_version, portfolio_version))
    PULUMI_CONFIG.write_text(update_pulumi_config(PULUMI_CONFIG.read_text(), wg_version, portfolio_version))
    run("make", "update-expected")

    if args.dry_run:
        print("Dry run: pins updated, not creating a PR.")
        run("git", "status", "--short")
        return

    print("Wallet Gateway and Portfolio UI versions are not up to date. Creating a PR...")
    title = f"Bump Wallet Gateway to {wg_version} and Portfolio UI to {portfolio_version} (auto-generated)"
    branch = f"wallet-gateway-version-bump-{date.today():%Y-%m-%d}"
    run("git", "add", str(COMPOSE_ENV), str(PULUMI_CONFIG), "cluster/expected", "cluster/deployment")
    run("git", "switch", "-c", branch)
    run("git", "commit", "-s", "-m", f"[ci] {title[0].lower()}{title[1:]}")
    run("git", "push", "origin", branch)
    run(
        "gh", "pr", "create",
        "--base", "main",
        "--head", branch,
        "--title", title,
        "--body", f"Wallet Gateway: {current_wg} -> {wg_version}\nPortfolio UI: {current_portfolio} -> {portfolio_version}",
        "--reviewer", args.reviewers,
    )
    print("Done.")


if __name__ == "__main__":
    main()
