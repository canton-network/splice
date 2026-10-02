#!/usr/bin/env python3
"""Report the tracker state of every cn-test-failures ref recorded in this ci-triage directory.

Run on a host whose `gh` login can read DACH-NY/cn-test-failures, from the splice repo root:

    python3 ci-triage/cn-test-failures-status.py [extra refs...]

from a checkout of the triage branch, or from any other checkout of the repo:

    python3 <(git show app-dev/ci-triage:ci-triage/cn-test-failures-status.py)

Refs come from the README.md table rows, the packet file names and known-families.md, read from the directory
of this script when it holds them, else from `git show <branch>:ci-triage/...`. Writes a TSV (ref, state, state
reason, closed at, updated at, labels, title) and prints the open refs. Needs `gh` and Python 3.
"""

import argparse
import json
import os
import re
import subprocess
import sys

REF_RE = re.compile(r"(?<![0-9])(?:8[0-9]{3}|9[0-9]{3}|10[0-9]{3})(?![0-9])")
BATCH = 50


def triage_sources(branch):
    here = os.path.dirname(os.path.abspath(__file__))
    if os.path.exists(os.path.join(here, "README.md")):
        names = os.listdir(here)
        read = lambda f: open(os.path.join(here, f)).read()
    else:
        ls = subprocess.run(["git", "ls-tree", "--name-only", f"{branch}:ci-triage"],
                            capture_output=True, text=True, check=True)
        names = ls.stdout.split()
        read = lambda f: subprocess.run(["git", "show", f"{branch}:ci-triage/{f}"],
                                        capture_output=True, text=True, check=True).stdout
    return names, read


def recorded_refs(branch):
    names, read = triage_sources(branch)
    refs = set()
    for line in read("README.md").splitlines():
        if re.match(r"^\| *[0-9]", line):
            refs |= {int(m) for m in REF_RE.findall(line.split("|")[1])}
    for name in names:
        if name[:1].isdigit() and name.endswith(".md"):
            refs |= {int(m) for m in REF_RE.findall(name.split("-")[0])}
    if "known-families.md" in names:
        refs |= {int(m) for m in REF_RE.findall(read("known-families.md"))}
    return refs


def query(owner, name, numbers):
    fields = " ".join(
        f"r{n}: issue(number: {n}) {{ number state stateReason closedAt updatedAt title labels(first: 10) {{ nodes {{ name }} }} }}"
        for n in numbers)
    q = f'query {{ repository(owner: "{owner}", name: "{name}") {{ {fields} }} }}'
    # Missing numbers produce GraphQL errors next to partial data: keep the data, report the misses.
    p = subprocess.run(["gh", "api", "graphql", "-f", f"query={q}"], capture_output=True, text=True)
    try:
        repo = json.loads(p.stdout)["data"]["repository"]
    except (ValueError, KeyError, TypeError):
        sys.exit(f"query failed:\n{p.stderr}\n{p.stdout[:2000]}")
    if repo is None:
        sys.exit(f"cannot read {owner}/{name} with this gh login:\n{p.stdout[:2000]}")
    rows = []
    for n in numbers:
        it = repo.get(f"r{n}")
        if it is None:
            rows.append([str(n), "NOT_FOUND", "", "", "", "", ""])
        else:
            rows.append([str(it["number"]), it["state"], it.get("stateReason") or "", it.get("closedAt") or "",
                         it["updatedAt"], ",".join(l["name"] for l in it["labels"]["nodes"]),
                         it["title"].replace("\t", " ")])
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("refs", nargs="*", type=int, help="extra refs to check")
    ap.add_argument("--repo", default="DACH-NY/cn-test-failures")
    ap.add_argument("--branch", default="app-dev/ci-triage",
                    help="triage branch to read refs from when not run from inside its ci-triage directory")
    ap.add_argument("--out", default="log/cn-test-failures-status.tsv")
    args = ap.parse_args()

    owner, name = args.repo.split("/")
    refs = sorted(recorded_refs(args.branch) | set(args.refs))
    print(f"checking {len(refs)} refs in {args.repo}", file=sys.stderr)
    rows = []
    for i in range(0, len(refs), BATCH):
        rows += query(owner, name, refs[i:i + BATCH])

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w") as f:
        f.write("ref\tstate\tstate_reason\tclosed_at\tupdated_at\tlabels\ttitle\n")
        f.writelines("\t".join(r) + "\n" for r in rows)
    counts = {}
    for r in rows:
        key = r[1] + (f"/{r[2]}" if r[2] else "")
        counts[key] = counts.get(key, 0) + 1
    print(f"wrote {args.out}: " + " ".join(f"{k}={v}" for k, v in sorted(counts.items())), file=sys.stderr)
    print("open refs:", file=sys.stderr)
    for r in rows:
        if r[1] == "OPEN":
            print(f"  {r[0]}  {r[6][:100]}", file=sys.stderr)


if __name__ == "__main__":
    main()
