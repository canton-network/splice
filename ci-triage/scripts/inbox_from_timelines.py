#!/usr/bin/env python3
"""List the probable Status=Inbox items of DACH-NY project 48 (cn-test-failures) without GraphQL access.

The sandbox token cannot read Projects v2, but the REST issue timeline shows `added_to_project_v2` and
`project_v2_item_status_changed` events with their actor. An open issue whose status was only ever changed by
github-project-automation[bot] (or never) still has the default status, Inbox.

Runs in the sandbox through ghx or gh api, whichever reads cn-test-failures (see ci-triage/CLAUDE.md, Access).
Usage, from the repo root:
  ci-triage/scripts/inbox_from_timelines.py [--min-number N] [--triage-dir DIR] [--json OUT]
  --min-number   ignore older issues (old bulk-filed items are archived but still match the heuristic)
  --triage-dir   ci-triage/ dir; issues mentioned in any file there are flagged as already triaged
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
from concurrent.futures import ThreadPoolExecutor
from typing import Any

REPO = "/repos/DACH-NY/cn-test-failures"


VIA = ["ghx"]


def get(path: str) -> Any:
    out = subprocess.run([*VIA, path], capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def open_issues() -> list[Any]:
    issues, page = [], 1
    while True:
        batch = get(f"{REPO}/issues?state=open&per_page=100&page={page}")
        issues += [i for i in batch if "pull_request" not in i]
        if len(batch) < 100:
            return issues
        page += 1


def status_events(issue: Any) -> tuple[Any, bool, list[str]]:
    events = get(f"{REPO}/issues/{issue['number']}/timeline?per_page=100")
    added = any(e.get("event") == "added_to_project_v2" for e in events)
    changers = [
        (e.get("actor") or {}).get("login", "")
        for e in events
        if e.get("event") == "project_v2_item_status_changed"
    ]
    return issue, added, changers


def triaged_numbers(triage_dir: str | None) -> set[int]:
    if not triage_dir:
        return set()
    seen: set[int] = set()
    for f in pathlib.Path(triage_dir).rglob("*.md"):
        seen.update(int(n) for n in re.findall(r"\b(1\d{4})\b", f.read_text(errors="ignore")))
    return seen


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-number", type=int, default=0)
    ap.add_argument("--triage-dir")
    ap.add_argument("--json")
    ap.add_argument("--via", choices=["ghx", "gh"], default="ghx" if shutil.which("ghx") else "gh",
                    help="ghx (credential router) or gh (gh api with the sandbox token); default ghx if installed")
    args = ap.parse_args()
    VIA[:] = ["ghx"] if args.via == "ghx" else ["gh", "api"]

    issues = [i for i in open_issues() if i["number"] >= args.min_number]
    with ThreadPoolExecutor(8) as ex:
        results = list(ex.map(status_events, issues))
    seen = triaged_numbers(args.triage_dir)

    inbox = []
    for issue, added, changers in results:
        humans = [c for c in changers if c and not c.endswith("[bot]")]
        if added and not humans:
            inbox.append(
                {
                    "number": issue["number"],
                    "created": issue["created_at"],
                    "assignees": [a["login"] for a in issue["assignees"]],
                    "comments": issue["comments"],
                    "title": issue["title"],
                    "triaged": issue["number"] in seen,
                }
            )
    inbox.sort(key=lambda x: x["number"])
    if args.json:
        json.dump(inbox, open(args.json, "w"), indent=1)
    for x in inbox:
        flag = "T" if x["triaged"] else "-"
        print(f"{x['number']} {flag} {x['created'][5:16]} c={x['comments']} {x['assignees']} {x['title'][:95]}")
    print(f"# {len(inbox)} inbox items, {sum(not x['triaged'] for x in inbox)} not yet in the triage notes")


if __name__ == "__main__":
    main()
