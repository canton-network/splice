#!/usr/bin/env python3
"""Export the open Status = Inbox issues of the cn-test-failures board, with bodies and comments, one JSON object
per line.

Runs on the host (needs a gh token with the project scope, SSO-authorized for the organization). Usage, from the
repo root:
  direnv exec . ci-triage/scripts/export_inbox.py
Closed issues are skipped, whatever their Status.
Writes ci-triage/export/inbox.jsonl unless --out is given.
"""
import argparse
import json
import pathlib
import subprocess
import time
from collections.abc import Iterator
from typing import Any

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
    }
  }
}
"""

ISSUES_BATCH = 20


def graphql(query: str, variables: dict[str, Any]) -> Any:
    req = json.dumps({"query": query, "variables": variables})
    # Large board pages occasionally time out on the GitHub side (HTTP 502/504); retry those.
    for attempt in range(4):
        res = subprocess.run(["gh", "api", "graphql", "--input", "-"], input=req, capture_output=True, text=True)
        if res.returncode == 0 or "HTTP 5" not in res.stderr:
            break
        time.sleep(5 * (attempt + 1))
    if res.returncode != 0:
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
    ap.add_argument("--org", default="DACH-NY")
    ap.add_argument("--project", type=int, default=48)
    ap.add_argument("--repo", default="DACH-NY/cn-test-failures")
    ap.add_argument("--status", default="Inbox")
    ap.add_argument("--out", default="ci-triage/export/inbox.jsonl")
    args = ap.parse_args()
    out = pathlib.Path(args.out)

    ids: list[str] = []
    seen = 0
    for nodes in pages(args.org, args.project):
        for n in nodes:
            seen += 1
            c = n.get("content") or {}
            if (n.get("status") or {}).get("name") != args.status:
                continue
            if (c.get("repository") or {}).get("nameWithOwner") != args.repo:
                continue
            if c.get("state") != "OPEN":
                continue
            ids.append(c["id"])

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
            })

    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as f:
        for r in sorted(rows, key=lambda r: r["number"]):
            f.write(json.dumps(r) + "\n")
    print(f"{len(rows)} open {args.status} issues of {seen} board items -> {out}")


if __name__ == "__main__":
    main()
