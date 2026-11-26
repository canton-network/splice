#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

STATIC_GROUPS = ("python", "daml", "gha", "grafana", "base_images", "npm_names")
FULL_SBT_CMD = "lintStatic"
ALL_NPM_LINTS = ("apps-frontends", "pulumi", "load-tester", "party-allocator")

RUN_ALL = re.compile(
    r"^(nix/|\.github/actions/scripts/select_checks\.py$"
    r"|\.github/workflows/build\.(static_tests|compile_checks)\.yml$|build\.sbt$|project/)"
)
STATIC_FLAGS = {
    "python": re.compile(r"(\.py$|^scripts/python-checks)"),
    "daml": re.compile(r"(\.daml$|(^|/)daml\.yaml$|^scripts/check-daml-)"),
    "gha": re.compile(r"^(\.github/|gha-scripts/|scripts/(actionlint|zizmor)\.sh$)"),
    "grafana": re.compile(
        r"^(cluster/pulumi/observability/grafana-dashboards/|scripts/check-grafana-dashboards\.sh$)"
    ),
    "base_images": re.compile(r"((^|/)Dockerfile$|^scripts/check-base-images\.sh$)"),
    "npm_names": re.compile(r"((^|/)package\.json$|^scripts/check-npm-package-names\.py$)"),
}
SCALAFMT = re.compile(r"(\.(scala|sbt)$|^\.scalafmt\.conf$)")
SHELL = re.compile(r"(\.sh$|(^|/)\.envrc|^\.pre-commit-config\.yaml$)")
SHELL_SHEBANG = re.compile(r"^#!.*\b(ba|z|da|k)?sh\b")
PYTHON_SHEBANGS = ("#!/usr/bin/env python", "#!/usr/bin/env python3")
PACKAGE_JSON = re.compile(r"(^|/)package\.json$")
DAML_DIR = re.compile(r"^daml/")
HEADERS = re.compile(r"\.(scala|daml|rst|ts|tsx|js|py|sh)$")
TEST_SOURCES = re.compile(r"/src/test/")
TEST_LISTS = re.compile(r"^test-.*\.log$")
TEST_DEFINITION = re.compile(
    r"^[+-](?![+-])\s*(?:(?:final|abstract|sealed|private|protected|case)\s+)*(?:class|object)\s+\w"
)
ROOT_JS_OR_JSON = re.compile(r"^[^/]+\.(json|js|cjs|mjs)$")
NPM_LINT_SOURCES = {
    "apps-frontends": re.compile(
        r"^apps/(.+/)?frontend/|^apps/[^/]+$|^apps/.*\.ya?ml$|^daml/|^token-standard/"
    ),
    "pulumi": re.compile(r"^cluster/"),
    "load-tester": re.compile(r"^load-tester/"),
    "party-allocator": re.compile(r"^party-allocator/|^daml/"),
}
KNOWN = re.compile(
    r"\.(scala|sbt|java|py|daml|sh|md|mdx|rst|txt)$"
    r"|(^|/)(daml\.yaml|Dockerfile|package\.json|\.envrc[^/]*|\.gitignore|\.gitattributes|LICENSE)$"
    r"|^\.(scalafmt\.conf|pre-commit-config\.yaml)$|^test-.*\.log$"
    r"|^(\.github|gha-scripts|cluster|daml|token-standard|docs|release-notes)/"
    r"|^(load-tester|party-allocator)/"
    r"|^apps/(.+/)?frontend/|^apps/[^/]+$|^apps/.*\.ya?ml$|/src/(main|test)/resources/"
)


@dataclass(frozen=True)
class Change:
    status: str
    path: str


def parse_name_status(output: str) -> list[Change]:
    changes = []
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) >= 2:
            changes.append(Change(status=fields[0], path=fields[-1]))
    return changes


def any_matches(pattern: re.Pattern[str], changes: list[Change]) -> bool:
    return any(pattern.search(c.path) for c in changes)


def is_shell_script(path: str, first_line: Callable[[str], Optional[str]]) -> bool:
    if "." in os.path.basename(path):
        return False
    line = first_line(path)
    return line is not None and SHELL_SHEBANG.search(line) is not None


def is_python_script(path: str, first_line: Callable[[str], Optional[str]]) -> bool:
    if Path(path).suffix:
        return False
    line = first_line(path)
    return line is not None and line.rstrip("\r\n") in PYTHON_SHEBANGS


def sbt_command(parts: list[str]) -> str:
    return " ".join(parts)


def npm_lint_command(projects: tuple[str, ...]) -> str:
    return "".join(f" {p}/npmLint" for p in projects)


def all_static() -> dict[str, str]:
    outputs = {group: "true" for group in STATIC_GROUPS}
    outputs["sbt_cmd"] = FULL_SBT_CMD
    return outputs


def all_build() -> dict[str, str]:
    return {"test_config": "true", "npm_lint": npm_lint_command(ALL_NPM_LINTS)}


def select_static(
    changes: list[Change], first_line: Callable[[str], Optional[str]]
) -> dict[str, str]:
    outputs = {
        group: "true" if any_matches(pattern, changes) else "false"
        for group, pattern in STATIC_FLAGS.items()
    }
    if any(is_python_script(c.path, first_line) for c in changes):
        outputs["python"] = "true"
    shell_changed = any_matches(SHELL, changes) or any(
        is_shell_script(c.path, first_line) for c in changes
    )
    parts = []
    if any_matches(SCALAFMT, changes):
        parts.append("lintStaticFormat")
    if shell_changed:
        parts.append("runShellcheck")
    if any_matches(PACKAGE_JSON, changes):
        parts.append("syncpackCheck")
    if any_matches(DAML_DIR, changes):
        parts.append("illegalDamlReferencesCheck")
    if any_matches(HEADERS, changes) or shell_changed:
        parts.append("headerCheck")
    outputs["sbt_cmd"] = sbt_command(parts)
    return outputs


def test_definitions_changed(test_diff: Optional[str]) -> bool:
    if test_diff is None:
        return True
    return any(TEST_DEFINITION.search(line) for line in test_diff.splitlines())


def select_build(changes: list[Change], test_diff: Optional[str] = "") -> dict[str, str]:
    added_removed_or_renamed = [c for c in changes if c.status[:1] in ("A", "D", "R")]
    test_config = (
        any_matches(TEST_SOURCES, added_removed_or_renamed)
        or any_matches(TEST_LISTS, changes)
        or test_definitions_changed(test_diff)
    )
    lints: tuple[str, ...]
    if any_matches(ROOT_JS_OR_JSON, changes):
        lints = ALL_NPM_LINTS
    else:
        lints = tuple(
            project
            for project in ALL_NPM_LINTS
            if any_matches(NPM_LINT_SOURCES[project], changes)
        )
    return {"test_config": "true" if test_config else "false", "npm_lint": npm_lint_command(lints)}


def unknown_files(
    changes: list[Change], first_line: Callable[[str], Optional[str]]
) -> list[str]:
    return [
        c.path
        for c in changes
        if not KNOWN.search(c.path)
        and not is_shell_script(c.path, first_line)
        and not is_python_script(c.path, first_line)
    ]


def select(
    mode: str,
    changes: list[Change],
    first_line: Callable[[str], Optional[str]],
    test_diff: Optional[str] = "",
) -> dict[str, str]:
    unknown = unknown_files(changes, first_line)
    if any_matches(RUN_ALL, changes) or unknown:
        for path in unknown:
            print(
                f"::notice file={path}::No check selection rule for {path}: running all checks",
                file=sys.stderr,
            )
        return all_static() if mode == "static" else all_build()
    if mode == "static":
        return select_static(changes, first_line)
    return select_build(changes, test_diff)


def git(*args: str) -> Optional[str]:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


def find_base(base_sha: str) -> Optional[str]:
    if git("rev-parse", "--verify", "-q", "origin/main") is not None:
        base = git("merge-base", "origin/main", "HEAD")
        if base:
            return base
    fetch = ("fetch", "-q", "--no-tags", "--depth=1", "--filter=blob:none", "origin", base_sha)
    if base_sha and git(*fetch) is not None:
        return base_sha
    return None


def read_first_line(path: str) -> Optional[str]:
    try:
        with Path(path).open(encoding="utf-8", errors="replace") as f:
            return f.readline()
    except OSError:
        return None


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[1] not in ("static", "build"):
        print(f"usage: {argv[0]} static|build", file=sys.stderr)
        return 1
    mode = argv[1]
    run_all = all_static() if mode == "static" else all_build()
    event = os.environ.get("EVENT_NAME", "")
    if event != "pull_request":
        print(f"Not a pull request ({event}): running all checks", file=sys.stderr)
        outputs = run_all
    else:
        base = find_base(os.environ.get("BASE_SHA", ""))
        diff = git("diff", "--name-status", "--no-renames", base, "HEAD") if base else None
        if base is None or diff is None:
            print("No base commit found: running all checks", file=sys.stderr)
            outputs = run_all
        else:
            changes = parse_name_status(diff)
            print(f"Changed files relative to {base}:", file=sys.stderr)
            for c in changes:
                print(f"  {c.status} {c.path}", file=sys.stderr)
            test_diff: Optional[str] = ""
            if mode == "build" and any(TEST_SOURCES.search(c.path) for c in changes):
                pathspec = ":(glob)**/src/test/**/*.scala"
                test_diff = git("diff", "-U0", "--no-renames", base, "HEAD", "--", pathspec)
            outputs = select(mode, changes, read_first_line, test_diff)
    for key, value in outputs.items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
