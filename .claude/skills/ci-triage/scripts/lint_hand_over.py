#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Check a hand-over script (ci-<ref>.sh) before it is given to the user.

For every issue that the script posts a root-cause comment on (`post <ref> ...`, not duplicates closed with `dup`):
- the live title must not still start with `GHA Run` or `Pipeline` unless the script retitles it (`gh issue edit <ref> ... --title`);
- the comment file must exist and end with the hidden marker `<!-- ci-triage:<ref> -->`;
- the script must set the status (`set_status.py Inbox Triaged ... <ref>`) unless the issue is closed in the script.
Usage: lint_hand_over.py /home/agent/ci-11789.sh [--dir <dataset checkout>]
Exit code 1 when something is missing.
"""
import argparse
import glob
import json
import re
import subprocess
import sys

REPO = "DACH-NY/cn-test-failures"


def live_title(ref: str) -> str:
    out = subprocess.run(
        ["gh", "issue", "view", ref, "-R", REPO, "--json", "title"], capture_output=True, text=True
    )
    return json.loads(out.stdout)["title"] if out.returncode == 0 else ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("script")
    ap.add_argument("--dir", default="/Users/jagathweerasinghe/Work/canton/ai_workshop/cn-test-failures")
    args = ap.parse_args()
    text = open(args.script).read()
    refs = sorted(set(re.findall(r"^post (\d+) ", text, re.M)))
    problems = []
    for ref in refs:
        title = live_title(ref)
        retitled = re.search(rf"gh issue edit {ref} .*--title", text) is not None
        if re.match(r"(GHA Run|Pipeline)", title) and not retitled:
            problems.append(f"{ref}: title still '{title[:50]}' and the script does not retitle it")
        files = sorted(glob.glob(f"{args.dir}/ci-triage/rounds/*/comments/{ref}.md"))
        if not files:
            problems.append(f"{ref}: no comment file")
        elif f"<!-- ci-triage:{ref} -->" not in open(files[-1]).read():
            problems.append(f"{ref}: comment file lacks the hidden marker")
        closes = re.search(rf"gh issue close {ref}\b", text) or re.search(rf"^dup \d+ {ref} ", text, re.M)
        if not closes and not re.search(rf"set_status\.py\"? Inbox \S+ .*\b{ref}\b", text):
            problems.append(f"{ref}: no set_status.py and no close")
    for p in problems:
        print("LINT:", p)
    print(f"checked {len(refs)} issue(s): {'OK' if not problems else str(len(problems)) + ' problem(s)'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
