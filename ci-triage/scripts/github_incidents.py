#!/usr/bin/env python3
"""GitHub status incidents overlapping given times (githubstatus.com, last 50 incidents, about 2 months).

Runs in the sandbox (needs network access to www.githubstatus.com) or on the host. Usage:
  ci-triage/scripts/github_incidents.py 2026-11-04T10:06:58Z [2026-09-23T15:15Z ...] [--margin-min 30]
Prints, per time, every incident whose window (created to resolved, widened by the margin) contains it.
"""
import argparse
import datetime
import json
import urllib.request
from typing import Any

URL = "https://www.githubstatus.com/api/v2/incidents.json"


def parse(s: str) -> datetime.datetime:
    dt = datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))
    return dt if dt.tzinfo else dt.replace(tzinfo=datetime.timezone.utc)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("times", nargs="+", help="UTC times, e.g. 2026-11-04T10:06:58Z")
    ap.add_argument("--margin-min", type=int, default=30, help="widen each incident window by this much")
    args = ap.parse_args()

    with urllib.request.urlopen(URL, timeout=30) as resp:
        incidents: list[dict[str, Any]] = json.load(resp)["incidents"]
    margin = datetime.timedelta(minutes=args.margin_min)
    oldest = min(parse(i["created_at"]) for i in incidents)
    for t_arg in args.times:
        t = parse(t_arg)
        hits = []
        for i in incidents:
            start = parse(i["created_at"])
            end = parse(i["resolved_at"]) if i.get("resolved_at") else datetime.datetime.now(datetime.timezone.utc)
            if start - margin <= t <= end + margin:
                comps = ",".join(c["name"] for c in i.get("components", []))
                hits.append(f"  {start:%Y-%m-%d %H:%M} to {end:%Y-%m-%d %H:%M} UTC {i['impact']} | {i['name']} | {comps} | {i['shortlink']}")
        note = " (before the oldest listed incident, no data)" if t < oldest else ""
        print(f"{t:%Y-%m-%d %H:%M:%S} UTC: {len(hits)} incident(s){note}")
        for h in hits:
            print(h)


if __name__ == "__main__":
    main()
