#!/usr/bin/env python3
"""Close cn-test-failures issues as duplicates of one parent issue, each with the same closing comment.

Runs on the host (needs a gh login that can write issues). Only open issues are closed, so running a command twice
is harmless; the parent itself is never closed. Usage, from the repo root:
  direnv exec . ci-triage/scripts/close_duplicates.py 11128 11138 11139 --comment 'Same assertion at ScanPartyMonitorScriptIntegrationTest.scala:273.'
  direnv exec . ci-triage/scripts/close_duplicates.py --dry-run 11128 11138 11139 --comment '...'
"""
import argparse
import pathlib
import json
import subprocess
import sys


def state(repo: str, ref: int) -> str:
    out = subprocess.run(["gh", "issue", "view", str(ref), "-R", repo, "--json", "state"],
                         capture_output=True, text=True, check=True).stdout
    value: str = json.loads(out)["state"]
    return value


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("parent", type=int)
    ap.add_argument("refs", nargs="+", type=int)
    body = ap.add_mutually_exclusive_group(required=True)
    body.add_argument("--comment", help="closing comment, the same for every issue")
    body.add_argument("--comment-file", help="file with the closing comment (any characters, Markdown)")
    ap.add_argument("--repo", default="DACH-NY/cn-test-failures")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    comment = args.comment if args.comment is not None else pathlib.Path(args.comment_file).read_text()

    failed = False
    for ref in dict.fromkeys(args.refs):
        try:
            if ref == args.parent:
                print(f"#{ref}: is the parent, left as is")
                continue
            current = state(args.repo, ref)
            if current != "OPEN":
                print(f"#{ref}: {current}, left as is")
                continue
            if args.dry_run:
                print(f"#{ref}: would close as duplicate of #{args.parent}")
                continue
            subprocess.run(["gh", "issue", "close", str(ref), "-R", args.repo, "--duplicate-of", str(args.parent),
                            "--comment", comment], capture_output=True, text=True, check=True)
            print(f"#{ref}: closed as duplicate of #{args.parent}")
        except subprocess.CalledProcessError as e:
            failed = True
            print(f"#{ref}: gh failed: {e.stderr.strip()}", file=sys.stderr)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
