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
ci-triage/export/<ref>/gcp/<name>.json (gcloud --format json, ascending), and the archive
ci-triage/export/<ref>/<ref>-gcp-logs.tar.gz with every file in that gcp directory (also from earlier runs).
"""
import argparse
import json
import os
import shutil
import subprocess
import sys


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref", help="cn-test-failures issue number")
    ap.add_argument("--project", required=True)
    ap.add_argument("--cluster", help="resource.labels.cluster_name, e.g. cn-ciperiodicnet")
    ap.add_argument("--start", required=True, help="UTC, e.g. 2026-11-12T00:45:00Z")
    ap.add_argument("--end", required=True, help="UTC, e.g. 2026-11-12T01:38:00Z")
    ap.add_argument("--query", nargs=2, action="append", metavar=("NAME", "FILTER"), required=True,
                    help="output name and Logging query; repeat for several files")
    ap.add_argument("--limit", type=int, default=200000, help="max entries per query")
    args = ap.parse_args()

    if shutil.which("gcloud") is None:
        sys.exit("gcloud not found; install the Google Cloud CLI and run gcloud auth login")
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True,
                          text=True).stdout.strip()
    ref_dir = os.path.join(root, "ci-triage", "export", args.ref)
    out_dir = os.path.join(ref_dir, "gcp")
    os.makedirs(out_dir, exist_ok=True)

    base = [f'timestamp>="{args.start}"', f'timestamp<="{args.end}"', 'resource.type="k8s_container"']
    if args.cluster:
        base.append(f'resource.labels.cluster_name="{args.cluster}"')
    queries: list[list[str]] = args.query
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

    tar = os.path.join(ref_dir, f"{args.ref}-gcp-logs.tar.gz")
    tar_cmd = ["tar", "-czf", tar, "-C", ref_dir, "gcp"]
    print(" ".join(tar_cmd), flush=True)
    subprocess.run(tar_cmd, check=True)
    print(f"wrote {tar} ({os.path.getsize(tar)} bytes)")


if __name__ == "__main__":
    main()
