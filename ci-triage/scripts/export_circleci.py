#!/usr/bin/env python3
"""Save the step output of CircleCI jobs (failed steps by default) for the sandbox to read.

Runs on the host. Needs a CircleCI personal API token in the environment variable CIRCLECI_TOKEN
(https://app.circleci.com/settings/user/tokens); the token is only sent to circleci.com and never written to a file.
Usage, from the repo root:
  direnv exec . ci-triage/scripts/export_circleci.py 11183 https://app.circleci.com/pipelines/github/DACH-NY/canton-network-internal/88081/workflows/.../jobs/528694
  direnv exec . ci-triage/scripts/export_circleci.py 11183 528694 --all-steps
Writes ci-triage/export/<ref>/<job>-<step>.txt per step and <job>-steps.txt (name and status of every step).
"""
import argparse
import json
import os
import pathlib
import re
import urllib.request
from typing import Any

API = "https://circleci.com/api/v1.1/project/github/{slug}/{job}"


def get_json(url: str, token: str | None) -> Any:
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    if token is not None:
        req.add_header("Circle-Token", token)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref", help="cn-test-failures issue number, names the export directory")
    ap.add_argument("jobs", nargs="+", help="CircleCI job URLs or job numbers")
    ap.add_argument("--slug", default="DACH-NY/canton-network-internal")
    ap.add_argument("--all-steps", action="store_true", help="save every step, not only failed ones")
    ap.add_argument("--out", default="ci-triage/export")
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
        steps_path = out / f"{job}-steps.txt"
        steps_path.write_text("\n".join(summary) + "\n")
        print(f"{steps_path}")


if __name__ == "__main__":
    main()
