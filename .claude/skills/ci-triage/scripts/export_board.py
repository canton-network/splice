#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Export the open issues of the cn-test-failures board by Status, with bodies, comments and the PRs that will close
them, one JSON object per line and one file per Status.

Runs on the host (needs a gh token with the project scope, SSO-authorized for the organization). Usage, from the
repo root:
  direnv exec . .claude/skills/ci-triage/scripts/export_board.py                      # Inbox, Triaged, In progress
  direnv exec . .claude/skills/ci-triage/scripts/export_board.py --status Inbox
Closed issues are skipped, whatever their Status.
Writes log/ci-triage-export/<status>.jsonl (inbox.jsonl, triaged.jsonl, in-progress.jsonl) of this checkout.
"""
import argparse
import json
import subprocess
import time
from collections.abc import Iterator
from typing import Any

from export_dir import export_root

# Pass 1 pages through the whole board (thousands of closed items), so it fetches only what selects an item.
ITEMS_QUERY = """
query($org: String!, $number: Int!, $endCursor: String) {
  organization(login: $org) {
    projectV2(number: $number) {
      items(first: 100, after: $endCursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          status: fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
          content { ... on Issue { id state repository { nameWithOwner } } }
        }
      }
    }
  }
}
"""

# Pass 2 fetches bodies and comments for the selected issues only.
ISSUES_QUERY = """
query($ids: [ID!]!) {
  nodes(ids: $ids) {
    ... on Issue {
      number title url state createdAt
      author { login }
      assignees(first: 10) { nodes { login } }
      labels(first: 20) { nodes { name } }
      body
      comments(first: 100) { totalCount nodes { author { login } createdAt body } }
      closedByPullRequestsReferences(first: 10, includeClosedPrs: true) { nodes { url state } }
    }
  }
}
"""

ORG = "DACH-NY"
PROJECT = 48
REPO = "DACH-NY/cn-test-failures"
ISSUES_BATCH = 20
DEFAULT_STATUSES = ("Inbox", "Triaged", "In progress")
STATUSES = DEFAULT_STATUSES + ("Infrequent / no repro",)


def graphql(query: str, variables: dict[str, Any]) -> Any:
    req = json.dumps({"query": query, "variables": variables})
    # Large board pages occasionally time out on the GitHub side (HTTP 502/504); retry those.
    for attempt in range(4):
        res = subprocess.run(["gh", "api", "graphql", "--input", "-"], input=req, capture_output=True, text=True)
        if res.returncode == 0 or "HTTP 5" not in res.stderr:
            break
        time.sleep(5 * (attempt + 1))
    # range(4) always assigns res; mypy does not infer nonempty ranges.
    if res.returncode != 0:  # type: ignore[possibly-undefined]
        raise SystemExit(f"gh api graphql failed ({res.returncode}):\n{res.stderr.strip()}\n{res.stdout.strip()}")
    return json.loads(res.stdout)["data"]


def pages(org: str, number: int) -> Iterator[list[Any]]:
    cursor = None
    while True:
        data = graphql(ITEMS_QUERY, {"org": org, "number": number, "endCursor": cursor})
        project = data["organization"]["projectV2"]
        if project is None:
            raise SystemExit(f"project {org}/{number} not readable: check the token's project scope and SSO grant")
        items = project["items"]
        yield items["nodes"]
        if not items["pageInfo"]["hasNextPage"]:
            return
        cursor = items["pageInfo"]["endCursor"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--status", action="append", choices=STATUSES, help="board Status to export, repeatable (default: " + ", ".join(DEFAULT_STATUSES) + ")")
    args = ap.parse_args()
    statuses: list[str] = args.status or list(DEFAULT_STATUSES)
    out_dir = export_root()

    ids: dict[str, list[str]] = {s: [] for s in statuses}
    seen = 0
    for nodes in pages(ORG, PROJECT):
        for n in nodes:
            seen += 1
            c = n.get("content") or {}
            status = (n.get("status") or {}).get("name")
            if status not in ids:
                continue
            if (c.get("repository") or {}).get("nameWithOwner") != REPO:
                continue
            if c.get("state") != "OPEN":
                continue
            ids[status].append(c["id"])

    out_dir.mkdir(parents=True, exist_ok=True)
    for status in statuses:
        out = out_dir / (status.lower().replace(" ", "-") + ".jsonl")
        rows = issues(ids[status])
        with open(out, "w") as f:
            for r in sorted(rows, key=lambda r: r["number"]):
                f.write(json.dumps(r) + "\n")
        print(f"{len(rows)} open {status} issues -> {out}")
    print(f"({seen} board items)")


def issues(ids: list[str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for i in range(0, len(ids), ISSUES_BATCH):
        for c in graphql(ISSUES_QUERY, {"ids": ids[i:i + ISSUES_BATCH]})["nodes"]:
            rows.append({
                "number": c["number"],
                "title": c["title"],
                "url": c["url"],
                "state": c["state"],
                "createdAt": c["createdAt"],
                "author": (c.get("author") or {}).get("login"),
                "assignees": [a["login"] for a in c["assignees"]["nodes"]],
                "labels": [lab["name"] for lab in c["labels"]["nodes"]],
                "body": c["body"],
                "commentCount": c["comments"]["totalCount"],
                "comments": [{"author": (m.get("author") or {}).get("login"), "createdAt": m["createdAt"],
                              "body": m["body"]} for m in c["comments"]["nodes"]],
                "closingPullRequests": [{"url": pr["url"], "state": pr["state"]}
                                        for pr in c["closedByPullRequestsReferences"]["nodes"]],
            })
    return rows


if __name__ == "__main__":
    main()
