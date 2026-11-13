#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Save the step output of CircleCI jobs (failed steps by default) for the sandbox to read.

Runs on the host. Needs a CircleCI personal API token in the environment variable CIRCLECI_TOKEN
(https://app.circleci.com/settings/user/tokens); the token is only sent to circleci.com (and its artifact host circle-artifacts.com) and never written to a file.
Usage, from the repo root:
  direnv exec . .claude/skills/ci-triage/scripts/export_circleci.py 11183 https://app.circleci.com/pipelines/github/DACH-NY/canton-network-internal/88081/workflows/.../jobs/528694
  direnv exec . .claude/skills/ci-triage/scripts/export_circleci.py 11183 528694 --all-steps
  direnv exec . .claude/skills/ci-triage/scripts/export_circleci.py 11383 531501 --artifacts
Writes ci-triage/export/<ref>/<job>-<step>.txt per step and <job>-steps.txt (name and status of every step).
With --artifacts also downloads the job's stored artifacts (test logs) to ci-triage/export/<ref>/<job>-artifacts/,
optionally only those whose path matches --artifact-filter (a regex).
"""
import argparse
import json
import os
import pathlib
import re
import urllib.parse
import urllib.request
from typing import Any

API = "https://circleci.com/api/v1.1/project/github/{slug}/{job}"


def get_json(url: str, token: str | None) -> Any:
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    if token is not None:
        req.add_header("Circle-Token", token)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def download(url: str, token: str, dest: pathlib.Path) -> int:
    host = urllib.parse.urlparse(url).hostname or ""
    req = urllib.request.Request(url)
    if host == "circleci.com" or host.endswith((".circleci.com", ".circle-artifacts.com")):
        req.add_header("Circle-Token", token)
    dest.parent.mkdir(parents=True, exist_ok=True)
    size = 0
    with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as f:
        while chunk := resp.read(1 << 20):
            f.write(chunk)
            size += len(chunk)
    return size


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref", help="cn-test-failures issue number, names the export directory")
    ap.add_argument("jobs", nargs="+", help="CircleCI job URLs or job numbers")
    ap.add_argument("--slug", default="DACH-NY/canton-network-internal")
    ap.add_argument("--all-steps", action="store_true", help="save every step, not only failed ones")
    ap.add_argument("--out", default="ci-triage/export")
    ap.add_argument("--artifacts", action="store_true", help="also download the job's stored artifacts")
    ap.add_argument("--artifact-filter", default="", help="regex on the artifact path (with --artifacts)")
    args = ap.parse_args()

    token = os.environ.get("CIRCLECI_TOKEN")
    if not token:
        raise SystemExit("set CIRCLECI_TOKEN to a CircleCI personal API token")
    out = pathlib.Path(args.out) / args.ref
    out.mkdir(parents=True, exist_ok=True)

    for job_arg in args.jobs:
        m = re.search(r"(\d+)(?:/parallel-runs/\d+)?/?$", job_arg)
        if not m:
            raise SystemExit(f"cannot find a job number in {job_arg}")
        job = m.group(1)
        info = get_json(API.format(slug=args.slug, job=job), token)
        summary = [f"job {job} {info.get('workflows', {}).get('job_name', '')} status={info.get('status')} "
                   f"branch={info.get('branch')} vcs_revision={info.get('vcs_revision')} start={info.get('start_time')}"]
        for i, step in enumerate(info.get("steps", [])):
            name = step.get("name", f"step{i}")
            for action in step.get("actions", []):
                status = action.get("status")
                summary.append(f"{i:03d} {status} {name}")
                if not (args.all_steps or status in ("failed", "timedout", "infrastructure_fail")):
                    continue
                url = action.get("output_url")
                if not url:
                    continue
                messages = get_json(url, None)  # presigned URL, no token
                text = "".join(msg.get("message", "") for msg in messages)
                safe = re.sub(r"[^A-Za-z0-9._-]+", "_", name)[:60]
                path = out / f"{job}-{i:03d}-{safe}.txt"
                path.write_text(text, encoding="utf-8", errors="replace")
                print(f"{path} ({len(text)} chars, {status})")
        if args.artifacts:
            arts = get_json(API.format(slug=args.slug, job=job) + "/artifacts", token)
            adir = out / f"{job}-artifacts"
            for art in arts:
                apath = str(art.get("path", ""))
                if args.artifact_filter and not re.search(args.artifact_filter, apath):
                    continue
                rel = pathlib.PurePosixPath(apath.lstrip("/"))
                if ".." in rel.parts:
                    continue
                n = download(str(art["url"]), token, adir.joinpath(*rel.parts))
                print(f"{adir.joinpath(*rel.parts)} ({n} bytes)")
        steps_path = out / f"{job}-steps.txt"
        steps_path.write_text("\n".join(summary) + "\n")
        print(f"{steps_path}")


if __name__ == "__main__":
    main()
