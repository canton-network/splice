#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Export GCP log entries for a cn-test-failures ref with gcloud, one JSON file per named query, then tar them.

Runs on the host (the sandbox has no GCP access; recipe 13). Needs `gcloud auth login` with read access to the
project's logs. From the repo root:
  direnv exec . .claude/skills/ci-triage/scripts/export_gcp_logs.py 11379 --project da-cn-ci \
      --start 2026-11-12T00:45:00Z --end 2026-11-12T01:38:00Z \
      --query seq-p2p 'resource.labels.container_name="sequencer"' \
      --query sv-ns 'resource.labels.namespace_name="sv"'
Each query's filter lines are ANDed with the time window and, with --cluster, the cluster name. Output:
log/ci-triage-export/<ref>/gcp/<name>.json (gcloud --format json, ascending), and the archive
log/ci-triage-export/<ref>/<ref>-gcp-logs.tar.gz with every file in that gcp directory (also from earlier runs).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

from export_dir import ref_dir
from gcp_logs import project_id, utc_time

CLUSTER = re.compile(r"[a-z]([-a-z0-9]{0,38}[a-z0-9])?")
QUERY_NAME = re.compile(r"[A-Za-z0-9_-]{1,60}")


def cluster_name(s: str) -> str:
    if not CLUSTER.fullmatch(s):
        raise argparse.ArgumentTypeError(f"not a GKE cluster name: {s!r}")
    return s


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref", type=int, help="cn-test-failures issue number")
    ap.add_argument("--project", required=True, type=project_id)
    ap.add_argument("--cluster", type=cluster_name, help="resource.labels.cluster_name, e.g. cn-ciperiodicnet")
    ap.add_argument("--start", required=True, type=utc_time, help="UTC, e.g. 2026-11-12T00:45:00Z")
    ap.add_argument("--end", required=True, type=utc_time, help="UTC, e.g. 2026-11-12T01:38:00Z")
    ap.add_argument("--query", nargs=2, action="append", metavar=("NAME", "FILTER"), required=True,
                    help="output name and Logging query; repeat for several files")
    ap.add_argument("--limit", type=int, default=200000, help="max entries per query")
    args = ap.parse_args()
    queries: list[list[str]] = args.query
    for name, _ in queries:
        if not QUERY_NAME.fullmatch(name):
            ap.error(f"query name must be 1 to 60 letters, digits, - or _: {name!r}")

    if shutil.which("gcloud") is None:
        sys.exit("gcloud not found; install the Google Cloud CLI and run gcloud auth login")
    rdir = str(ref_dir(args.ref))
    out_dir = os.path.join(rdir, "gcp")
    os.makedirs(out_dir, exist_ok=True)

    base = [f'timestamp>="{args.start}"', f'timestamp<="{args.end}"', 'resource.type="k8s_container"']
    if args.cluster:
        base.append(f'resource.labels.cluster_name="{args.cluster}"')
    for name, flt in queries:
        full = " AND ".join(base + [f"({flt})"])
        path = os.path.join(out_dir, f"{name}.json")
        cmd = ["gcloud", "logging", "read", full, "--project", args.project, "--order", "asc",
               "--limit", str(args.limit), "--format", "json"]
        print(f"{name}: {full}", flush=True)
        with open(path, "w") as f:
            subprocess.run(cmd, check=True, stdout=f)
        with open(path) as f:
            entries: list[object] = json.load(f)
        n = len(entries)
        print(f"  {n} entries -> {path}" + ("  (hit --limit)" if n >= args.limit else ""), flush=True)

    tar = os.path.join(rdir, f"{args.ref}-gcp-logs.tar.gz")
    tar_cmd = ["tar", "-czf", tar, "-C", rdir, "gcp"]
    print(" ".join(tar_cmd), flush=True)
    subprocess.run(tar_cmd, check=True)
    print(f"wrote {tar} ({os.path.getsize(tar)} bytes)")


if __name__ == "__main__":
    main()
