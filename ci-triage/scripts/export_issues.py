#!/usr/bin/env python3
"""Export recent cn-test-failures issues with bodies and comments, one JSON object per line.

Runs on the host (needs a gh login that can read the repository). Usage, from the repo root:
  direnv exec . ci-triage/scripts/export_issues.py --since 2026-10-19
Writes ci-triage/export/issues-since-<date>.jsonl unless --out is given.
"""
import argparse
import json
import pathlib
import subprocess

FIELDS = "number,title,state,stateReason,createdAt,closedAt,assignees,labels,body,comments"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", required=True, help="creation date, YYYY-MM-DD")
    ap.add_argument("--repo", default="DACH-NY/cn-test-failures")
    ap.add_argument("--limit", type=int, default=2000)
    ap.add_argument("--out")
    args = ap.parse_args()
    out = pathlib.Path(args.out or f"ci-triage/export/issues-since-{args.since}.jsonl")

    raw = subprocess.run(
        ["gh", "issue", "list", "-R", args.repo, "--state", "all", "--search", f"created:>={args.since}",
         "--limit", str(args.limit), "--json", FIELDS],
        capture_output=True, text=True, check=True,
    ).stdout
    issues = json.loads(raw)

    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as f:
        for i in sorted(issues, key=lambda i: i["number"]):
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
    if len(issues) >= args.limit:
        print(f"warning: hit --limit {args.limit}, the export may be incomplete")


if __name__ == "__main__":
    main()
