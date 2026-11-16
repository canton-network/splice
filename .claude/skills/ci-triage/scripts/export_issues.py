#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Export recent cn-test-failures issues with bodies and comments, one JSON object per line.

Runs on the host (needs a gh login that can read the repository). Usage, from the repo root:
  direnv exec . .claude/skills/ci-triage/scripts/export_issues.py --since 2026-10-19
Writes log/ci-triage-export/issues-since-<date>.jsonl of this checkout.
"""
import argparse
import datetime
import json
import subprocess
from typing import Any

from export_dir import export_root

REPO = "DACH-NY/cn-test-failures"
FIELDS = "number,title,state,stateReason,createdAt,closedAt,assignees,labels,body,comments"
SEARCH_CAP = 1000  # GitHub search returns at most 1000 results per query
WINDOW = datetime.timedelta(days=7)


def fetch_window(repo: str, start: datetime.date, end: datetime.date) -> list[dict[str, Any]]:
    """Issues created in [start, end], both inclusive."""
    raw = subprocess.run(
        ["gh", "issue", "list", "-R", repo, "--state", "all", "--search", f"created:{start}..{end}",
         "--limit", str(SEARCH_CAP), "--json", FIELDS],
        capture_output=True, text=True, check=True,
    ).stdout
    issues: list[dict[str, Any]] = json.loads(raw)
    if len(issues) >= SEARCH_CAP:
        raise SystemExit(f"error: {start}..{end} has {SEARCH_CAP} or more issues; lower WINDOW in {__file__}")
    return issues


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", required=True, type=datetime.date.fromisoformat, help="creation date, YYYY-MM-DD")
    args = ap.parse_args()
    out = export_root() / f"issues-since-{args.since}.jsonl"

    issues: dict[int, dict[str, Any]] = {}
    start: datetime.date = args.since
    today = datetime.datetime.now(datetime.timezone.utc).date()
    while start <= today:
        end = min(start + WINDOW - datetime.timedelta(days=1), today)
        for i in fetch_window(REPO, start, end):
            issues[i["number"]] = i
        start = end + datetime.timedelta(days=1)

    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as f:
        for _, i in sorted(issues.items()):
            f.write(json.dumps({
                "number": i["number"],
                "title": i["title"],
                "state": i["state"],
                "stateReason": i["stateReason"],
                "createdAt": i["createdAt"],
                "closedAt": i["closedAt"],
                "assignees": [a["login"] for a in i["assignees"]],
                "labels": [lab["name"] for lab in i["labels"]],
                "body": i["body"],
                "comments": [{"author": (c.get("author") or {}).get("login"), "createdAt": c["createdAt"],
                              "body": c["body"]} for c in i["comments"]],
            }) + "\n")
    print(f"{len(issues)} issues -> {out}")


if __name__ == "__main__":
    main()
