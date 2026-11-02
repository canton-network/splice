#!/usr/bin/env python3
"""Export the Status = Inbox issues of the cn-test-failures board, with bodies and comments, one JSON object per line.

Runs on the host (needs a gh token with the project scope, SSO-authorized for the organization). Usage, from the
repo root:
  direnv exec . ci-triage/scripts/export_inbox.py
Writes ci-triage/export/inbox.jsonl unless --out is given.
"""
import argparse
import json
import pathlib
import subprocess
from collections.abc import Iterator
from typing import Any

QUERY = """
query($org: String!, $number: Int!, $endCursor: String) {
  organization(login: $org) {
    projectV2(number: $number) {
      items(first: 50, after: $endCursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          status: fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
          content {
            ... on Issue {
              number title url state createdAt
              repository { nameWithOwner }
              author { login }
              assignees(first: 10) { nodes { login } }
              labels(first: 20) { nodes { name } }
              body
              comments(first: 100) { totalCount nodes { author { login } createdAt body } }
            }
          }
        }
      }
    }
  }
}
"""


def pages(org: str, number: int) -> Iterator[list[Any]]:
    cursor = None
    while True:
        cmd = ["gh", "api", "graphql", "-f", f"query={QUERY}", "-f", f"org={org}", "-F", f"number={number}"]
        if cursor:
            cmd += ["-f", f"endCursor={cursor}"]
        data = json.loads(subprocess.run(cmd, capture_output=True, text=True, check=True).stdout)
        project = data["data"]["organization"]["projectV2"]
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

    rows: list[dict[str, Any]] = []
    seen = 0
    for nodes in pages(args.org, args.project):
        for n in nodes:
            seen += 1
            c = n.get("content") or {}
            if (n.get("status") or {}).get("name") != args.status:
                continue
            if (c.get("repository") or {}).get("nameWithOwner") != args.repo:
                continue
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
    print(f"{len(rows)} {args.status} issues of {seen} board items -> {out}")


if __name__ == "__main__":
    main()
