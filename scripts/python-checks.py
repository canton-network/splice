#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Shared, configuration-isolated Python checks for Splice and its consumers."""

import argparse
from dataclasses import dataclass
import glob
import json
import os
from pathlib import Path
import subprocess
import sys
from collections.abc import Sequence

from git import Repo
from git.exc import GitError


RULES = {
    "warn-unreachable": ("--warn-unreachable",),
    "strict-equality-for-none": ("--strict-equality-for-none",),
    "disallow-any-decorated": ("--disallow-any-decorated",),
    **{
        code: ("--enable-error-code", code)
        for code in (
            "explicit-override",
            "ignore-without-code",
            "possibly-undefined",
            "redundant-expr",
            "unused-awaitable",
            "mutable-override",
            "redundant-self",
            "unimported-reveal",
            "exhaustive-match",
        )
    },
}


@dataclass(frozen=True)
class Config:
    exclusions: tuple[str, ...]
    extra_mypypath: dict[str, tuple[str, ...]]
    disabled_rules: dict[str, str]


def strings(value: object, name: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not all(isinstance(item, str) and item for item in value):
        raise ValueError(f"{name} must be an array of nonempty strings")
    return tuple(value)


def relative_path(root: Path, value: str) -> Path:
    """Resolve a repository-relative path, rejecting paths that escape the repository."""
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"expected a repository-relative path: {value}")
    resolved = (root / path).resolve()
    if not resolved.is_relative_to(root):
        raise ValueError(f"path is outside the repository: {value}")
    return resolved


def load_config(root: Path, path: Path) -> Config:
    raw: object = json.loads(path.read_text())
    if not isinstance(raw, dict):
        raise ValueError("configuration must be a JSON object")
    unknown = raw.keys() - {"exclusions", "extra_mypypath", "disabled_rules"}
    if unknown:
        raise ValueError(f"unknown configuration keys: {sorted(unknown)}")
    exclusions = strings(raw.get("exclusions", []), "exclusions")
    for pattern in exclusions:
        if pattern.startswith(("/", ":")) or ".." in pattern.split("/"):
            raise ValueError(f"expected a root-relative Git exclusion pattern: {pattern}")

    extra = raw.get("extra_mypypath", {})
    if not isinstance(extra, dict):
        raise ValueError("extra_mypypath must map directories to arrays of import paths")
    imports: dict[str, tuple[str, ...]] = {}
    for directory, paths in extra.items():
        if not isinstance(directory, str) or not directory:
            raise ValueError("extra_mypypath keys must be nonempty directory names")
        key = relative_path(root, directory).relative_to(root).as_posix()
        imports[key] = strings(paths, f"extra_mypypath[{directory}]")
        for import_path in imports[key]:
            if not relative_path(root, import_path).is_dir():
                raise ValueError(f"import directory does not exist: {import_path}")

    disabled = raw.get("disabled_rules", {})
    if not isinstance(disabled, dict):
        raise ValueError("disabled_rules must map known rules to reasons")
    reasons: dict[str, str] = {}
    for rule, reason in disabled.items():
        if not isinstance(rule, str) or rule not in RULES:
            raise ValueError(f"unknown disabled rule: {rule}")
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError(f"disabled rule {rule} needs a nonempty reason")
        reasons[rule] = reason
    return Config(exclusions, imports, reasons)


def repository_root(start: Path) -> Path:
    with Repo(start, search_parent_directories=True) as repo:
        if repo.working_tree_dir is None:
            raise ValueError(f"{start} has no Git working tree")
        return Path(repo.working_tree_dir).resolve()


def python_files(root: Path, config: Config, target: Path | None) -> list[str]:
    """Return sorted tracked Python files selected by the coverage policy and optional target."""
    selected = target.resolve() if target is not None else root
    if not selected.is_relative_to(root):
        raise ValueError(f"{target} is outside the repository")
    with Repo(root) as repo:
        output: str = repo.git.ls_files(
            "-z", "--stage", "--",
            *(f":(top,exclude){pattern}" for pattern in config.exclusions),
        )
    files: set[str] = set()
    for entry in output.split("\0"):
        if not entry:
            continue
        metadata, name = entry.split("\t", 1)
        path = root / name
        if not path.is_relative_to(selected):
            continue
        if name.endswith(".py"):
            files.add(name)
        elif (
            not path.suffix
            and metadata.startswith(("100644 ", "100755 "))
            and not path.is_symlink()
        ):
            try:
                with path.open("rb") as source:
                    header = source.readline(128).rstrip(b"\r\n")
            except FileNotFoundError:
                continue
            if header in (b"#!/usr/bin/env python", b"#!/usr/bin/env python3"):
                files.add(name)
    if not files:
        raise ValueError(f"no covered Python files found under {selected}")
    return sorted(files)


def grouped(files: Sequence[str]) -> dict[str, list[str]]:
    """Group repository-relative file paths by their parent directory."""
    directories: dict[str, list[str]] = {}
    for file in files:
        directories.setdefault(Path(file).parent.as_posix(), []).append(file)
    return directories


def check(root: Path, config: Config, files: Sequence[str]) -> int:
    """Run mypy separately for each directory and return a nonzero status if any check fails."""
    failed = False
    for directory, targets in grouped(files).items():
        print(f"mypy: {directory}", flush=True)
        env = dict(os.environ)
        env["MYPYPATH"] = os.pathsep.join(
            [directory, *config.extra_mypypath.get(directory, ())]
        )
        modules = [target for target in targets if target.endswith(".py")]
        # Extensionless scripts share the __main__ module name; check them separately.
        batches = ([modules] if modules else []) + [
            [target] for target in targets if not target.endswith(".py")
        ]
        for batch in batches:
            # Never load repository mypy configs: their plugins can execute target code.
            command = [
                "mypy", "--config-file=", "--python-version", "3.14", "--strict",
                "--explicit-package-bases",
                *(arg for rule, args in RULES.items() if rule not in config.disabled_rules for arg in args),
                "--",
                *batch,
            ]
            if subprocess.run(command, cwd=root, env=env).returncode != 0:
                failed = True
    return int(failed)


def test(root: Path, files: Sequence[str]) -> int:
    """Run selected test modules in isolated processes and return a nonzero status if any test fails."""
    tests = [file for file in files if Path(file).match("test_*.py")]
    if not tests:
        raise ValueError("no tests found in the selected covered files")
    failed = False
    for directory, targets in grouped(tests).items():
        print(f"unittest: {directory}", flush=True)
        for file in targets:
            # Restrict discovery to selected tracked tests, including exclusions.
            command = [
                sys.executable, "-m", "unittest", "discover",
                f"--start-directory={directory}", f"--pattern={glob.escape(Path(file).name)}",
            ]
            if subprocess.run(command, cwd=root).returncode != 0:
                failed = True
    return int(failed)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, help="repository to check (default: current Git checkout)")
    parser.add_argument("--config", type=Path, help="JSON config, relative to the repository root")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("check", "test"):
        command = commands.add_parser(name, help=f"{name} covered Python files")
        command.add_argument("path", nargs="?", type=Path, help="file or directory relative to the current directory")
    args = parser.parse_args(argv)
    try:
        root = repository_root(args.root or Path.cwd())
        config = load_config(root, root / (args.config or "scripts/python-checks.json"))
        files = python_files(root, config, args.path)
        if args.command == "check":
            return check(root, config, files)
        return test(root, files)
    except (GitError, OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
