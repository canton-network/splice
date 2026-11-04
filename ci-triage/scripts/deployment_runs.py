#!/usr/bin/env python3
"""Recent CircleCI workflow runs of canton-network-internal as JSON lines, for the sandbox to read.

Machine-readable variant of scripts/deployments.py (swim-lane HTML, splice 2492dd0f53): the same pipeline ->
workflow -> job walk over the CircleCI v2 API, one JSON object per workflow run, passed runs included.
Runs on the host and needs a CircleCI personal API token in CIRCLECI_TOKEN; the token is only sent to circleci.com
and never written to a file. Usage, from the repo root:
  direnv exec . ci-triage/scripts/deployment_runs.py                       # all workflows, last 7 days
  direnv exec . ci-triage/scripts/deployment_runs.py --days 3 --workflow 'deploy_.*' --excerpts
Writes ci-triage/export/circleci-runs-<UTC date>.jsonl unless --out is given.

Per run: pipeline (number, branch, revision, commit subject, actor), workflow (name, id, status, created, stopped,
url) and, for runs that did not succeed, their jobs (name, number, status, started, stopped, url). With
--excerpts, each failed job also gets, from the v1.1 job API: the failed steps with the last lines of their output
(ANSI codes stripped), the cn-test-failures issue URL printed by its notification step, and the splice commit if one
of the fetched steps logs `Set Splice commit to`. Step output URLs are presigned and are not written.
"""
import argparse
import datetime
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

PROJECT_SLUG = "github/DACH-NY/canton-network-internal"
API_V2 = "https://circleci.com/api/v2"
API_V11 = "https://circleci.com/api/v1.1/project/" + PROJECT_SLUG
FAILED = ("failed", "error", "timedout", "infrastructure_fail", "canceled", "cancelled")
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
ISSUE_URL = re.compile(r"https://github\.com/DACH-NY/cn-test-failures/issues/\d+")
SPLICE_COMMIT = re.compile(r"Set Splice commit to:?\s*([0-9a-f]{7,40})")


def get_json(url: str, token: str | None) -> Any:
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Circle-Token"] = token
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} for {url.split('?')[0]}")


def parse_time(s: str | None) -> datetime.datetime | None:
    if not s:
        return None
    try:
        dt = datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=datetime.timezone.utc)


def recent_pipelines(token: str, cutoff: datetime.datetime) -> list[dict[str, Any]]:
    pipelines: list[dict[str, Any]] = []
    page_token: str | None = None
    for _ in range(200):
        query = "?" + urllib.parse.urlencode({"page-token": page_token}) if page_token else ""
        data = get_json(f"{API_V2}/project/{PROJECT_SLUG}/pipeline{query}", token)
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


def job_details(token: str, job_number: int, lines: int) -> dict[str, Any]:
    info = get_json(f"{API_V11}/{job_number}", token)
    failed_steps: list[dict[str, Any]] = []
    issue_url: str | None = None
    splice_commit: str | None = None
    for step in info.get("steps", []):
        name = step.get("name", "")
        for action in step.get("actions", []):
            status = action.get("status")
            notify = "notify" in name.lower() or "issue" in name.lower()
            if status not in FAILED and not notify:
                continue
            url = action.get("output_url")
            text = ""
            if url:
                text = ANSI.sub("", "".join(m.get("message", "") for m in get_json(url, None)))
            if notify and issue_url is None:
                m = ISSUE_URL.search(text)
                issue_url = m.group(0) if m else None
            if splice_commit is None:
                c = SPLICE_COMMIT.search(text)
                splice_commit = c.group(1) if c else None
            if status in FAILED:
                tail = [ln[:300] for ln in text.replace("\r", "\n").split("\n") if ln.strip()][-lines:]
                failed_steps.append({"name": name, "status": status, "excerpt": tail})
    return {"failed_steps": failed_steps, "issue_url": issue_url, "splice_commit": splice_commit}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=float, default=7.0)
    ap.add_argument("--workflow", default=".*", help="regex the whole workflow name must match")
    ap.add_argument("--excerpts", action="store_true", help="add failed steps, issue URL and splice commit per failed job")
    ap.add_argument("--lines", type=int, default=40, help="excerpt length in lines")
    ap.add_argument("--out")
    args = ap.parse_args()

    token = os.environ.get("CIRCLECI_TOKEN")
    if not token:
        raise SystemExit("set CIRCLECI_TOKEN to a CircleCI personal API token")
    now = datetime.datetime.now(datetime.timezone.utc)
    out = pathlib.Path(args.out or f"ci-triage/export/circleci-runs-{now:%Y-%m-%d}.jsonl")
    name_re = re.compile(args.workflow)

    pipelines = recent_pipelines(token, now - datetime.timedelta(days=args.days))
    print(f"{len(pipelines)} pipelines in the last {args.days} days", file=sys.stderr)
    runs: list[dict[str, Any]] = []
    for p in pipelines:
        vcs = p.get("vcs") or {}
        for wf in get_json(f"{API_V2}/pipeline/{p['id']}/workflow", token).get("items", []):
            name = wf.get("name") or ""
            if not name_re.fullmatch(name):
                continue
            wf_url = f"https://app.circleci.com/pipelines/{PROJECT_SLUG}/{p.get('number')}/workflows/{wf.get('id')}"
            run: dict[str, Any] = {
                "pipeline": p.get("number"),
                "branch": vcs.get("branch") or vcs.get("tag"),
                "revision": vcs.get("revision"),
                "commit_subject": (vcs.get("commit") or {}).get("subject"),
                "actor": ((p.get("trigger") or {}).get("actor") or {}).get("login"),
                "workflow": name,
                "workflow_id": wf.get("id"),
                "status": wf.get("status"),
                "created_at": wf.get("created_at"),
                "stopped_at": wf.get("stopped_at"),
                "url": wf_url,
            }
            if (wf.get("status") or "").lower() != "success":
                jobs = []
                for j in get_json(f"{API_V2}/workflow/{wf.get('id')}/job", token).get("items", []):
                    job: dict[str, Any] = {
                        "name": j.get("name"),
                        "job_number": j.get("job_number"),
                        "status": j.get("status"),
                        "started_at": j.get("started_at"),
                        "stopped_at": j.get("stopped_at"),
                        "url": f"{wf_url}/jobs/{j.get('job_number')}" if j.get("job_number") else None,
                    }
                    if args.excerpts and j.get("job_number") and (j.get("status") or "").lower() in FAILED:
                        job.update(job_details(token, int(j["job_number"]), args.lines))
                    jobs.append(job)
                run["jobs"] = jobs
            runs.append(run)

    runs.sort(key=lambda r: (r["workflow"], r["created_at"] or ""))
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        for r in runs:
            f.write(json.dumps(r, ensure_ascii=True) + "\n")
    failed = sum(1 for r in runs if (r["status"] or "").lower() != "success")
    print(f"{len(runs)} workflow runs ({failed} not successful) -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
