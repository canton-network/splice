#!/usr/bin/env python

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Fails on git fetches that download full history (#7831), i.e. in CI workflows and scripts:
# - `fetch-depth: 0` in .github
# - `git fetch` / `git clone` without --depth, --deepen, --shallow-* or --filter
# A finding is allowed if the line or the line above contains `# full-history-ok: <reason>`.
# Calls through wrapper functions are not checked; annotate the wrapper's own `git fetch`.
# Usable as a library: another repo's script can `run()` this with its own pathspecs.

import argparse
import os
import re
import sys
from collections.abc import Sequence

import git

# Default pathspecs for the splice layout. Other repositories can import this file and call
# `run(pathspecs)` with their own list, or pass `--pathspec` (repeatable) on the command line.
PATHSPECS = [
    ".github",
    "scripts",
    "build-tools",
    "*.sh",
    "*.mk",
    "Makefile",
    "**/Makefile",
    ":!canton/*",
    ":!*.md",
    ":!*.mdx",
    ":!*.rst",
    ":!*.scala",
    ":!scripts/check-git-fetch-depth.py",
]

ALLOW_MARKER = "# full-history-ok:"
COMMENT_RE = re.compile(r"^\s*#")
FETCH_DEPTH_0_RE = re.compile(r"fetch-depth:\s*0\b")
FETCH_OR_CLONE_RE = re.compile(r"\bgit\s+(fetch|clone)\b(?![-_])")
BOUNDED_RE = re.compile(r"--(depth|deepen|shallow-|filter)")


def check_file(path: str) -> list[str]:
    findings: list[str] = []
    with open(path, encoding="utf-8", errors="replace") as f:
        prev = ""
        for lineno, line in enumerate(f, start=1):
            line = line.rstrip("\n")
            allowed = ALLOW_MARKER in line or ALLOW_MARKER in prev
            prev = line
            if allowed or COMMENT_RE.match(line):
                continue
            if path.startswith(".github/") and FETCH_DEPTH_0_RE.search(line):
                findings.append(f"{path}:{lineno}: fetch-depth: 0: {line}")
            elif FETCH_OR_CLONE_RE.search(line) and not BOUNDED_RE.search(line):
                findings.append(f"{path}:{lineno}: unbounded git fetch/clone: {line}")
    return findings


def run(pathspecs: Sequence[str]) -> None:
    """Checks the git repository containing the current directory (not the one containing this
    file), restricted to `pathspecs` (as in `git ls-files`). Exits non-zero on findings."""
    repo = git.Repo(search_parent_directories=True)
    if repo.working_tree_dir is None:
        sys.exit("ERROR: not in a git working tree")
    os.chdir(repo.working_tree_dir)
    paths: list[str] = repo.git.ls_files("--", *pathspecs).splitlines()
    findings = [
        finding
        for path in paths
        # skips submodules
        if os.path.isfile(path)
        for finding in check_file(path)
    ]

    if findings:
        print("ERROR: found git fetches that download full history:")
        print("\n".join(findings))
        print(
            "Bound them with --depth, --deepen, --shallow-* or --filter=blob:none, or annotate the line\n"
            f"or the line above with '{ALLOW_MARKER} <reason>'. See \"Git history in CI\" in TESTING.md."
        )
        sys.exit(1)
    else:
        print("No unbounded git fetches found.")


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--pathspec",
        action="append",
        help="git pathspec to check, repeatable; replaces the default PATHSPECS",
    )
    args = parser.parse_args(argv)
    run(args.pathspec or PATHSPECS)


if __name__ == "__main__":
    main()
