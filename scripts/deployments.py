#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Generate a static swim-lane diagram of recent CircleCI workflow runs.

Fetches all workflows for a project over the last N days, filters by a
workflow-name whitelist, and writes a self-contained HTML file.

Usage:
    export CIRCLECI_TOKEN=...        # required
    python circleci_swimlane.py [output.html]

All data fetching happens here in Python, so the resulting HTML is fully
static (no API calls, no CORS, no token embedded).
"""

import html
import json
import os
import sys
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone, timedelta

# ============================================================
# HARDCODED CONFIGURATION
# ============================================================

# project slug: <vcs>/<org>/<repo>, e.g. "gh/myorg/myrepo"
PROJECT_SLUG = "github/DACH-NY/canton-network-internal"

# how many days back to look
DAYS_BACK = 3

# only workflows whose name matches this regex are shown (one lane each)
WORKFLOW_NAME_REGEX = r"deploy_.*"

# horizontal scale: 1 hour === 100px
PX_PER_HOUR = 100

# status -> CSS class colors (see STATUS_COLORS below)
STATUS_COLORS = {
    "success": "#2ecc71",
    "running": "#3498db",
    "failed": "#e74c3c",
    "error": "#e74c3c",
    "failing": "#e67e22",
    "canceled": "#95a5a6",
    "cancelled": "#95a5a6",
    "on_hold": "#9b59b6",
    "unauthorized": "#7f8c8d",
    "not_run": "#bdc3c7",
    "unknown": "#34495e",
}

API_BASE = "https://circleci.com/api/v2"

# ============================================================
# API HELPERS
# ============================================================


def api_get(path, token):
    """Perform an authenticated GET against the CircleCI v2 API."""
    req = urllib.request.Request(
        API_BASE + path,
        headers={"Circle-Token": token, "Accept": "application/json"},
        )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise SystemExit(f"HTTP {e.code} for {path}: {body}")
    except urllib.error.URLError as e:
        raise SystemExit(f"Network error for {path}: {e.reason}")


def fetch_recent_pipelines(token, cutoff):
    """Page through pipelines until they are older than `cutoff` (a datetime)."""
    pipelines = []
    page_token = None
    safety = 200  # avoid runaway paging

    while safety > 0:
        safety -= 1
        query = ""
        if page_token:
            query = "?" + urllib.parse.urlencode({"page-token": page_token})
        data = api_get(f"/project/{PROJECT_SLUG}/pipeline{query}", token)

        stop = False
        for p in data.get("items", []):
            created = parse_time(p.get("created_at"))
            if created is None:
                continue
            if created < cutoff:
                stop = True
            else:
                pipelines.append(p)

        page_token = data.get("next_page_token")
        if stop or not page_token:
            break

    return pipelines


def fetch_workflows(token, pipeline_id):
    data = api_get(f"/pipeline/{pipeline_id}/workflow", token)
    return data.get("items", [])

def fetch_workflow_jobs(token, workflow_id):
    """Return the jobs of a workflow (name, status, timing, job_number)."""
    data = api_get(f"/workflow/{workflow_id}/job", token)
    return data.get("items", [])

# ============================================================
# TIME HELPERS
# ============================================================


def parse_time(s):
    """Parse an ISO-8601 timestamp (with trailing Z) into an aware datetime."""
    if not s:
        return None
    s = s.replace("Z", "+00:00")
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def fmt_time(dt):
    return dt.astimezone().strftime("%b %d %H:%M")


def fmt_duration(delta):
    total = int(delta.total_seconds())
    m, s = divmod(total, 60)
    if m == 0:
        return f"{s}s"
    return f"{m}m {s}s"


# ============================================================
# DATA COLLECTION
# ============================================================


def collect_runs(token, window_start, window_end):
    """Return (lane_names, {workflow_name: [run, ...]}) for matching workflows.

    Workflow names are discovered dynamically: any workflow whose name matches
    WORKFLOW_NAME_REGEX becomes a lane. Lane names are returned sorted
    alphabetically for a stable row order.
    """
    name_re = re.compile(WORKFLOW_NAME_REGEX)
    runs_by_lane = {}

    pipelines = fetch_recent_pipelines(token, window_start)
    print(f"Found {len(pipelines)} pipelines in the last {DAYS_BACK} days.",
          file=sys.stderr)

    for i, p in enumerate(pipelines, 1):
        try:
            workflows = fetch_workflows(token, p["id"])
        except SystemExit:
            raise
        except Exception as e:  # noqa: BLE001
            print(f"  warn: workflow fetch failed for {p['id']}: {e}",
                  file=sys.stderr)
            continue

        for wf in workflows:
            name = wf.get("name")
            if not name or not name_re.fullmatch(name):
                continue
            start = parse_time(wf.get("created_at"))
            if start is None:
                continue
            end = parse_time(wf.get("stopped_at")) or window_end  # still running

            status = wf.get("status", "unknown")
            wf_id = wf.get("id")
            pipeline_number = p.get("number")
            wf_url = (
                f"https://app.circleci.com/pipelines/{PROJECT_SLUG}"
                f"/{pipeline_number}/workflows/{wf_id}"
            )

            # --- enrich only for non-successful workflows ---
            failed_jobs = []
            job_counts = {}
            if (status or "").lower() != "success":
                try:
                    jobs = fetch_workflow_jobs(token, wf_id)
                except SystemExit:
                    raise
                except Exception as e:  # noqa: BLE001
                    print(f"  warn: job fetch failed for {wf_id}: {e}",
                          file=sys.stderr)
                    jobs = []

                for j in jobs:
                    jstatus = (j.get("status") or "unknown").lower()
                    job_counts[jstatus] = job_counts.get(jstatus, 0) + 1
                    if jstatus in ("failed", "error", "timedout",
                                   "canceled", "cancelled"):
                        job_number = j.get("job_number")
                        job_url = (
                            f"{wf_url}/jobs/{job_number}"
                            if job_number is not None else wf_url
                        )
                        failed_jobs.append({
                            "name": j.get("name", "?"),
                            "status": jstatus,
                            "url": job_url,
                        })

            # pipeline context
            trigger = (p.get("trigger") or {})
            actor = (trigger.get("actor") or {}).get("login")
            vcs = (p.get("vcs") or {})
            branch = vcs.get("branch") or vcs.get("tag")
            commit = (vcs.get("commit") or {})
            commit_subject = commit.get("subject")
            short_sha = (vcs.get("revision") or "")[:8]

            runs_by_lane.setdefault(name, []).append({
                "name": name,
                "status": status,
                "start": start,
                "end": end,
                "url": wf_url,
                "failed_jobs": failed_jobs,
                "job_counts": job_counts,
                "actor": actor,
                "branch": branch,
                "commit_subject": commit_subject,
                "short_sha": short_sha,
            })

        if i % 10 == 0:
            print(f"  processed {i}/{len(pipelines)} pipelines…", file=sys.stderr)

    for runs in runs_by_lane.values():
        runs.sort(key=lambda r: r["start"])

    # sort lane names alphabetically for a stable row order
    lane_names = sorted(runs_by_lane.keys())
    return lane_names, runs_by_lane

# ============================================================
# HTML GENERATION
# ============================================================


def status_color(status):
    return STATUS_COLORS.get((status or "unknown").lower(), STATUS_COLORS["unknown"])


def px_from_ms(ms):
    return ms * PX_PER_HOUR / (60 * 60 * 1000)


def render_html(lane_names, runs_by_lane, window_start, window_end, generated_at):
    px_per_ms = PX_PER_HOUR / (60 * 60 * 1000)
    total_ms = (window_end - window_start).total_seconds() * 1000
    track_width = total_ms * px_per_ms
    now_left = px_from_ms((generated_at - window_start).total_seconds() * 1000)

    # --- time axis ticks (one per hour, bold on day boundaries) ---
    ticks = []
    first = window_start.astimezone().replace(minute=0, second=0, microsecond=0)
    if first < window_start.astimezone():
        first += timedelta(hours=1)
    t = first
    while t <= window_end.astimezone():
        offset_ms = (t - window_start.astimezone()).total_seconds() * 1000
        left = px_from_ms(offset_ms)
        is_day = t.hour == 0
        label = t.strftime("%b %d") if is_day else f"{t.hour}:00"
        cls = "tick day" if is_day else "tick"
        ticks.append(
            f'<div class="{cls}" style="left:{left:.1f}px">{html.escape(label)}</div>'
        )
        t += timedelta(hours=1)

    # --- lanes ---
    label_items = []
    track_items = []
    total_runs = 0
    for name in lane_names:
        runs = runs_by_lane.get(name, [])
        total_runs += len(runs)
        boxes = []
        for r in runs:
            start = max(r["start"], window_start)
            end = min(r["end"], window_end)
            left = px_from_ms((start - window_start).total_seconds() * 1000)
            width = max(2.0, px_from_ms((end - start).total_seconds() * 1000))
            color = status_color(r["status"])

            # ---- tooltip content (HTML) ----
            tip_rows = [
                f'<div class="tip-title">{html.escape(r["name"])}</div>',
                f'<div><span class="tip-k">status</span> '
                f'{html.escape(r["status"])}</div>',
                f'<div><span class="tip-k">start</span> '
                f'{html.escape(fmt_time(r["start"]))}</div>',
                f'<div><span class="tip-k">end</span> '
                f'{html.escape(fmt_time(r["end"]))}</div>',
                f'<div><span class="tip-k">duration</span> '
                f'{html.escape(fmt_duration(r["end"] - r["start"]))}</div>',
            ]

            if r.get("branch"):
                sha = f' ({html.escape(r["short_sha"])})' if r.get("short_sha") else ""
                tip_rows.append(
                    f'<div><span class="tip-k">branch</span> '
                    f'{html.escape(r["branch"])}{sha}</div>'
                )
            if r.get("commit_subject"):
                subj = r["commit_subject"]
                if len(subj) > 70:
                    subj = subj[:67] + "…"
                tip_rows.append(
                    f'<div><span class="tip-k">commit</span> '
                    f'{html.escape(subj)}</div>'
                )
            if r.get("actor"):
                tip_rows.append(
                    f'<div><span class="tip-k">by</span> '
                    f'{html.escape(r["actor"])}</div>'
                )

            counts = r.get("job_counts") or {}
            if counts:
                total_jobs = sum(counts.values())
                parts = ", ".join(
                    f"{v} {html.escape(k)}" for k, v in sorted(counts.items())
                )
                tip_rows.append(
                    f'<div><span class="tip-k">jobs</span> '
                    f'{total_jobs} ({parts})</div>'
                )

            failed = r.get("failed_jobs") or []
            if failed:
                tip_rows.append('<div class="tip-failed-h">failed jobs</div>')
                for fj in failed:
                    tip_rows.append(
                        f'<a class="tip-job" '
                        f'href="{html.escape(fj["url"], quote=True)}" '
                        f'target="_blank" rel="noopener">'
                        f'{html.escape(fj["name"])} '
                        f'<span class="tip-job-status">[{html.escape(fj["status"])}]</span>'
                        f'</a>'
                    )

            tooltip_html = (
                f'<div class="tooltip"><div class="tooltip-inner">'
                f'{"".join(tip_rows)}'
                f'</div></div>'
            )

            boxes.append(
                f'<span class="run-wrap" style="left:{left:.1f}px;'
                f'width:{width:.1f}px">'
                f'<a class="run" style="background:{color}" '
                f'href="{html.escape(r["url"], quote=True)}" '
                f'target="_blank" rel="noopener">'
                f'{html.escape(r["status"])}</a>'
                f'{tooltip_html}'
                f'</span>'
            )
        label_items.append(
            f'<div class="lane-label">{html.escape(name)}</div>'
        )
        track_items.append(
            f'<div class="lane">'
            f'<div class="lane-track" style="width:{track_width:.1f}px;'
            f'flex:0 0 {track_width:.1f}px">{"".join(boxes)}</div>'
            f'</div>'
        )

    # --- legend ---
    legend_entries = [
        ("success", "success"),
        ("running", "running"),
        ("failed", "failed/error"),
        ("failing", "failing"),
        ("on_hold", "on hold"),
        ("canceled", "canceled"),
        ("not_run", "not run"),
    ]
    legend_html = "".join(
        f'<div class="item"><span class="swatch" '
        f'style="background:{STATUS_COLORS[key]}"></span>{html.escape(label)}</div>'
        for key, label in legend_entries
    )

    meta = (
        f"Project: {html.escape(PROJECT_SLUG)} · last {DAYS_BACK} days · "
        f"scale: {PX_PER_HOUR}px/hour · {total_runs} runs · "
        f"generated {generated_at.astimezone().strftime('%Y-%m-%d %H:%M')}"
    )

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>CircleCI Workflow Swim Lanes</title>
<style>
  :root {{
    --lane-height: 40px;
    --lane-gap: 8px;
    --label-width: 220px;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    margin: 0; background: #f5f6f8; color: #222;
  }}
  header {{ padding: 16px 24px; background: #fff; border-bottom: 1px solid #e2e4e8; }}
  header h1 {{ margin: 0 0 4px; font-size: 18px; }}
  header .meta {{ font-size: 13px; color: #666; }}
  .legend {{ display: flex; flex-wrap: wrap; gap: 12px; padding: 8px 24px 0; font-size: 12px; }}
  .legend .item {{ display: flex; align-items: center; gap: 4px; }}
  .legend .swatch {{ width: 14px; height: 14px; border-radius: 3px; display: inline-block; }}
  .diagram-wrapper {{ overflow-x: auto; padding: 16px 24px 200px; flex: 1 1 auto; min-width: 0; }}
  .diagram {{ position: relative; min-width: 100%; }}
  .diagram-row {{ display: flex; align-items: flex-start; }}
  .label-col {{
    flex: 0 0 var(--label-width); padding: 16px 0 200px 24px;
    background: #f5f6f8; z-index: 3;
  }}
  .label-col .axis-spacer {{ height: 24px; }}
  .label-col .lane-label {{
    height: var(--lane-height); margin-bottom: var(--lane-gap);
    display: flex; align-items: center;
    font-size: 13px; font-weight: 600; padding-right: 12px;
  }}
  .axis {{
    position: relative; height: 24px; /* removed: margin-left: var(--label-width) */
    border-bottom: 1px solid #ccc;
  }}
  .tick {{
    position: absolute; top: 0; height: 100%; border-left: 1px solid #ddd;
    font-size: 11px; color: #888; padding-left: 4px; white-space: nowrap;
  }}
  .tick.day {{ border-left: 2px solid #999; font-weight: 600; color: #444; }}
  .lane {{
    position: relative; height: var(--lane-height); margin-bottom: var(--lane-gap);
    /* display:flex / align-items no longer needed, but harmless if kept */
  }}
  .lane-track {{
    position: relative; background: repeating-linear-gradient(
      to right, rgba(0,0,0,0.02) 0, rgba(0,0,0,0.02) 99px,
      rgba(0,0,0,0.06) 99px, rgba(0,0,0,0.06) 100px);
    border-radius: 4px;
  }}
  .run-wrap {{
    position: absolute; top: 4px; height: calc(var(--lane-height) - 8px);
  }}
  .run {{
    display: block; width: 100%; height: 100%;
    border-radius: 8px; cursor: pointer; color: #fff; font-size: 11px;
    line-height: calc(var(--lane-height) - 8px); padding: 0 6px; overflow: hidden;
    white-space: nowrap; text-overflow: ellipsis; box-shadow: 0 1px 2px rgba(0,0,0,0.2);
    text-decoration: none; transition: transform 0.08s ease, box-shadow 0.08s ease;
  }}
  .run-wrap:hover .run {{
    transform: translateY(-1px); box-shadow: 0 3px 6px rgba(0,0,0,0.3);
  }}
  .run-wrap:hover {{ z-index: 50; }}
  .tooltip {{
    display: none; position: absolute; top: 100%; left: 0;
    margin-top: 0; padding-top: 16px; /* 16px top pad acts as the bridge */
    min-width: 240px; max-width: 380px;
    box-shadow: 0 6px 20px rgba(0,0,0,0.35); z-index: 100; white-space: normal;
    /* NOTE: move the visible background onto an inner element (see below) */
    background: transparent; color: #e6e6e6; border-radius: 6px;
  }}
  .tooltip-inner {{
    background: #1f2430; border-radius: 6px; padding: 10px 12px;
    font-size: 12px; line-height: 1.5;
  }}
  .run-wrap:hover .tooltip {{ display: block; }}
  .tooltip:hover {{ display: block; }}
  .tooltip .tip-title {{ font-weight: 700; margin-bottom: 4px; font-size: 13px; }}
  .tooltip .tip-k {{
    display: inline-block; min-width: 64px; color: #9aa4b2; font-weight: 600;
  }}
  .tooltip .tip-failed-h {{
    margin-top: 6px; font-weight: 700; color: #ff8a80;
    border-top: 1px solid #3a4152; padding-top: 6px;
  }}
  .tooltip a.tip-job {{
    display: block; color: #80c8ff; text-decoration: none; padding: 1px 0;
  }}
  .tooltip a.tip-job:hover {{ text-decoration: underline; }}
  .tooltip .tip-job-status {{ color: #ff8a80; }}
  .now-line {{
    position: absolute; top: 0; bottom: 0; width: 0;
    border-left: 2px solid #e74c3c; z-index: 40; pointer-events: none;
  }}
  .now-label {{
    position: absolute; top: 0; left: 2px; transform: translateY(-100%);
    font-size: 10px; font-weight: 700; color: #e74c3c;
    background: rgba(255,255,255,0.85); padding: 0 3px; border-radius: 3px;
    pointer-events: none;
  }}
</style>
</head>
<body>
<header>
  <h1>CircleCI Workflow Swim Lanes</h1>
  <div class="meta">{meta}</div>
</header>
<div class="legend">{legend_html}</div>
<div class="diagram-row">
  <div class="label-col">
    <div class="axis-spacer"></div>
    {"".join(label_items)}
  </div>
  <div class="diagram-wrapper">
    <div class="diagram">
      <div class="axis" style="width:{track_width:.1f}px">{"".join(ticks)}</div>
      <div class="now-line" style="left:{now_left:.1f}px"><span class="now-label">now</span></div>
      {"".join(track_items)}
    </div>
  </div>
</div>
</body>
</html>
"""


# ============================================================
# MAIN
# ============================================================


def main():
    token = os.environ.get("CIRCLECI_TOKEN")
    if not token:
        raise SystemExit("Error: environment variable CIRCLECI_TOKEN is not set.")

    output = sys.argv[1] if len(sys.argv) > 1 else "circleci_swimlane.html"

    now = datetime.now(timezone.utc)
    window_start = now - timedelta(days=DAYS_BACK)
    window_end = now + timedelta(hours=4)   # 4h of future space on the axis

    lane_names, runs_by_lane = collect_runs(token, window_start, window_end)
    page = render_html(lane_names, runs_by_lane, window_start, window_end, generated_at=now)

    with open(output, "w", encoding="utf-8") as f:
        f.write(page)

    print(f"Wrote {output}", file=sys.stderr)


if __name__ == "__main__":
    main()
