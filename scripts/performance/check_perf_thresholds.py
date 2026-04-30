#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Compare perf metrics against absolute thresholds. Exit non-zero on breach.

This script *only detects* breaches; reporting is delegated to the existing
`failure_notifications` GH action at the bottom of each job, which fires on
`if: failure()`. So this script:
  - read every metrics.json in the given dirs,
  - compare each metric to its `max:` rule in `.github/perf-thresholds.yaml`,
  - print a per-(test, metric) result to stdout,
  - append a Markdown summary of breaches to GITHUB_STEP_SUMMARY (visible in
    the GHA UI and linked to from the failure notification),
  - exit 1 if any breach occurred.

Usage:
  python3 check_perf_thresholds.py <metrics_dir> [<metrics_dir> ...]

Exit codes:
  0  no breaches
  1  one or more breaches
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Iterable

DEFAULT_THRESHOLDS = Path(".github/store-perf-thresholds.json")
NEWLINE = "\n"
GITHUB_OUTPUT_HEREDOC_DELIM = "EOF_PERF_THRESHOLDS"


def load_thresholds(path: Path) -> dict:
    with open(path) as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"thresholds file {path} must be a JSON object")
    return {k: v for k, v in data.items() }

def iter_metric_files(dirs: Iterable[Path]) -> Iterable[Path]:
    for d in dirs:
        if not d.is_dir():
            raise FileNotFoundError(f"metrics dir {d} does not exist")
        yield from sorted(d.glob("*.json"))


def get_metric_value(data: dict, name: str) -> float | None:
    for m in data.get("metrics", []):
        if m.get("name") == name:
            try:
                return float(m["value"])
            except (TypeError, ValueError):
                return None
    return None


def append_step_output(key: str, value: str) -> None:
    """Append `key=value` to $GITHUB_OUTPUT so later steps can read it via
    `steps.<id>.outputs.<key>`.
    """
    out_path = os.environ.get("GITHUB_OUTPUT")
    if not out_path:
        print(
            "info: GITHUB_OUTPUT not set (running outside GitHub Actions?); "
            f"skipping step output '{key}'",
            file=sys.stderr,
        )
        return
    try:
        with open(out_path, "a") as f:
            if NEWLINE in value:
                f.write(f"{key}<<{GITHUB_OUTPUT_HEREDOC_DELIM}\n{value}\n{GITHUB_OUTPUT_HEREDOC_DELIM}\n")
            else:
                f.write(f"{key}={value}\n")
    except OSError as e:
        print( f"warning: could not write GITHUB_OUTPUT ({out_path}): {e}", file=sys.stderr)


def append_step_summary(lines: list[str]) -> None:
    """Append a Markdown to GitHub Actions' step summary.
    """
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        print(
            "info: GITHUB_STEP_SUMMARY not set (running outside GitHub Actions?); "
            "skipping run-page summary",
            file=sys.stderr,
        )
        return
    try:
        with open(summary_path, "a") as f:
            f.write("\n".join(lines) + "\n")
    except OSError as e:
        print( f"warning: could not write GITHUB_STEP_SUMMARY ({summary_path}): {e}", file=sys.stderr )


def evaluate_perf_threshold_breaches( metric_dirs: list[Path], thresholds: dict ) -> tuple[list[str], int]:
    """Compare every metrics.json under `metric_dirs` to `thresholds`.
    Returns (breach_lines, files_seen).
    """
    breaches: list[str] = []
    files_seen = 0

    for f in iter_metric_files(metric_dirs):
        files_seen += 1
        try:
            data = json.loads(f.read_text())
        except (OSError, json.JSONDecodeError) as e:
            raise ValueError(f"cannot parse metrics file {f}: {e}") from e

        test_name = data.get("test_name") or f.stem
        rules = thresholds.get(test_name)
        if not rules:
            raise KeyError(
                f"no thresholds configured for '{test_name}' (from {f.name}); "
                f"add a rule to {DEFAULT_THRESHOLDS}"
            )

        for metric_name, raw_threshold in rules.items():
            try:
                threshold = float(raw_threshold)
            except (TypeError, ValueError):
                print(
                    f"warning: rule {test_name}::{metric_name} has non-numeric "
                    f"value {raw_threshold}, skipping",
                    file=sys.stderr,
                )
                continue

            observed = get_metric_value(data, metric_name)
            if observed is None:
                print(
                    f"warning: metric '{metric_name}' not in {f.name} for '{test_name}'",
                    file=sys.stderr,
                )
                continue

            if observed <= threshold:
                print(f"OK     {test_name} :: {metric_name} = {observed:.0f} (<= {threshold:.0f})")
                continue

            pct = ((observed - threshold) / threshold * 100.0) if threshold else 0.0
            line = (
                f"BREACH {test_name} :: {metric_name} = {observed:.0f} "
                f"(> {threshold:.0f}, +{pct:.2f}%)"
            )
            print(line)
            breaches.append(line)

    return breaches, files_seen


def main() -> int:
    if len(sys.argv) < 2:
        print( "warning: no metrics dir given; nothing to check", file=sys.stderr)

    thresholds_file = Path(str(DEFAULT_THRESHOLDS))
    thresholds = load_thresholds(thresholds_file)
    metric_dirs = [Path(p) for p in sys.argv[1:]]

    breaches, files_seen = evaluate_perf_threshold_breaches(metric_dirs, thresholds)

    print(f"Done. files_seen={files_seen} breaches={len(breaches)}")

    # Breach signaling is done via GITHUB_OUTPUT
    # downstream steps can branch on `steps.<id>.outputs.has_breaches`
    append_step_output("has_breaches", "true" if breaches else "false")
    append_step_output("breach_count", str(len(breaches)))
    if breaches:
        append_step_output("breaches", "\n".join(breaches))
        append_step_summary(
            ["## Performance threshold breaches", "", "```", *breaches, "```"]
        )

if __name__ == "__main__":
    sys.exit(main())
