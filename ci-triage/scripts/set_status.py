#!/usr/bin/env python3
"""Move cn-test-failures issues on the board from one Status to another.

Runs on the host (needs a gh token with the project scope, SSO-authorized for the organization). An issue is only
changed when its current Status is FROM, so running a command twice is harmless. Usage, from the repo root:
  direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11140 10495
  direnv exec . ci-triage/scripts/set_status.py --dry-run Inbox Triaged 11140
"""
import argparse
import json
import subprocess
import sys
from typing import Any

LOOKUP = """
query($owner: String!, $name: String!, $n: Int!) {
  repository(owner: $owner, name: $name) {
    issue(number: $n) {
      projectItems(first: 20) {
        nodes {
          id
          project { id number owner { ... on Organization { login } } field(name: "Status") { ... on ProjectV2SingleSelectField { id options { id name } } } }
          status: fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
        }
      }
    }
  }
}
"""

UPDATE = """
mutation($p: ID!, $i: ID!, $f: ID!, $o: String!) {
  updateProjectV2ItemFieldValue(input: {projectId: $p, itemId: $i, fieldId: $f, value: {singleSelectOptionId: $o}}) {
    projectV2Item { id }
  }
}
"""


def gql(query: str, **variables: str | int) -> Any:
    cmd = ["gh", "api", "graphql", "-f", f"query={query}"]
    for k, v in variables.items():
        cmd += ["-F" if isinstance(v, int) else "-f", f"{k}={v}"]
    return json.loads(subprocess.run(cmd, capture_output=True, text=True, check=True).stdout)


def move(repo: str, project: int, ref: int, src: str, dst: str, dry_run: bool) -> str:
    owner, name = repo.split("/")
    nodes = gql(LOOKUP, owner=owner, name=name, n=ref)["data"]["repository"]["issue"]["projectItems"]["nodes"]
    item = next((n for n in nodes if n["project"]["number"] == project), None)
    if item is None:
        return f"#{ref}: not on board {project}"
    current = (item.get("status") or {}).get("name") or "<none>"
    if current != src:
        return f"#{ref}: Status is '{current}', not '{src}'; left as is"
    field = item["project"]["field"]
    option = next((o["id"] for o in field["options"] if o["name"] == dst), None)
    if option is None:
        return f"#{ref}: board {project} has no Status '{dst}'"
    if dry_run:
        return f"#{ref}: would move {src} -> {dst}"
    gql(UPDATE, p=item["project"]["id"], i=item["id"], f=field["id"], o=option)
    return f"#{ref}: {src} -> {dst}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", metavar="FROM")
    ap.add_argument("dst", metavar="TO")
    ap.add_argument("refs", nargs="+", type=int)
    ap.add_argument("--repo", default="DACH-NY/cn-test-failures")
    ap.add_argument("--project", type=int, default=48)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    failed = False
    for ref in args.refs:
        try:
            print(move(args.repo, args.project, ref, args.src, args.dst, args.dry_run))
        except subprocess.CalledProcessError as e:
            failed = True
            print(f"#{ref}: gh failed: {e.stderr.strip()}", file=sys.stderr)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
