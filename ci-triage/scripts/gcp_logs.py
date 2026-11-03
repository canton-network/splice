#!/usr/bin/env python3
"""Print a GCP Logs Explorer URL and the matching gcloud command for a time window, for the user to open or run.

The sandbox has no GCP access; the user looks at the logs (recipe 13). Usage, in the sandbox:
  ci-triage/scripts/gcp_logs.py --project da-cn-ci --start 2026-11-03T09:15:00Z --end 2026-11-03T09:45:00Z \
      --filter 'resource.type=("cloud_run_revision" OR "cloud_function")' --filter 'severity>=ERROR'
Filters are Logging query language lines, ANDed.
"""
import argparse
import urllib.parse


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--start", required=True, help="UTC, e.g. 2026-11-03T09:15:00Z")
    ap.add_argument("--end", required=True, help="UTC, e.g. 2026-11-03T09:45:00Z")
    ap.add_argument("--filter", action="append", default=[], help="query line; repeat to AND several")
    ap.add_argument("--limit", type=int, default=200, help="entries for the gcloud command")
    args = ap.parse_args()

    query = "\n".join(args.filter)
    url = ("https://console.cloud.google.com/logs/query;query=" + urllib.parse.quote(query, safe="")
           + f";startTime={args.start};endTime={args.end}?project={urllib.parse.quote(args.project)}")
    gcloud_filter = " AND ".join([f'timestamp>="{args.start}"', f'timestamp<="{args.end}"'] + [f"({f})" for f in args.filter])
    if "'" in gcloud_filter or "\\" in gcloud_filter:
        raise SystemExit("filter must not contain single quotes or backslashes (shell-neutral command)")
    print(url)
    print()
    print(f"gcloud logging read '{gcloud_filter}' --project {args.project} --order asc --limit {args.limit}"
          " --format 'value(timestamp,severity,resource.labels.service_name,resource.labels.function_name,"
          "httpRequest.status,textPayload,jsonPayload.message)'")


if __name__ == "__main__":
    main()
