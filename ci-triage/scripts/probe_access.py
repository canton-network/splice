#!/usr/bin/env python3
"""Probe which method can read each kind of private data from the sandbox, and print the best one per capability.

Runs in the sandbox, read-only, one cheap request per capability and method. Methods, best first: ghx (the
host-side credential router, per-repo tokens), then gh api (the single token the sandbox proxy injects). See
ci-triage/CLAUDE.md, Access. Usage, from the repo root:
  ci-triage/scripts/probe_access.py
"""
import shutil
import subprocess

ROUTER = "http://host.docker.internal:18555/api/repos/DACH-NY/canton"

CAPABILITIES = [
    ("cn-test-failures issues", "/repos/DACH-NY/cn-test-failures/issues?per_page=1&state=all"),
    ("canton-network-internal contents", "/repos/DACH-NY/canton-network-internal/commits?per_page=1"),
    ("canton-network-internal actions", "/repos/DACH-NY/canton-network-internal/actions/runs?per_page=1"),
    ("canton-network-internal pulls", "/repos/DACH-NY/canton-network-internal/pulls?per_page=1&state=all"),
    ("canton contents", "/repos/DACH-NY/canton/commits?per_page=1"),
    ("splice (public)", "/repos/canton-network/splice/actions/runs?per_page=1"),
]

WORKAROUND = {
    "cn-test-failures issues": "exports (recipes 9, 10)",
    "canton-network-internal contents": "ask the user for the file or commit",
    "canton-network-internal actions": "user saves logs to export/<ref>/",
    "canton-network-internal pulls": "ask the user, or the commit message",
    "canton contents": "digital-asset/canton mirror, release jars (recipe 6)",
    "splice (public)": "-",
}


def works(cmd: list[str]) -> bool:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=30).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def router_status() -> str:
    try:
        out = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10", ROUTER],
                             capture_output=True, text=True, timeout=20).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return "curl failed"
    meaning = {
        "401": "router up; run its sandbox setup if ghx is missing",
        "403": "sandbox network policy missing (or unexpected Host); ask the user",
        "500": "router not running; ask the user to start it",
    }
    return f"{out}: {meaning.get(out, 'unexpected answer')}"


def main() -> None:
    have_ghx = shutil.which("ghx") is not None
    print(f"ghx installed: {'yes' if have_ghx else 'no'}; router: {router_status()}")
    print()
    print(f"{'capability':34} {'ghx':4} {'gh':4} method")
    for name, path in CAPABILITIES:
        via_ghx = have_ghx and works(["ghx", path])
        via_gh = works(["gh", "api", path])
        method = "ghx" if via_ghx else "gh api" if via_gh else f"workaround: {WORKAROUND[name]}"
        print(f"{name:34} {'yes' if via_ghx else 'no':4} {'yes' if via_gh else 'no':4} {method}")


if __name__ == "__main__":
    main()
