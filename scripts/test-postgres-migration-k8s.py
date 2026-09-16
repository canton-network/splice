#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Replay the Kubernetes section of the PostgreSQL 14 migration guide
(https://docs.canton.network/global-synchronizer/production-operations/validator-postgres-migration)
against a scratch cluster, verifying that the documented procedure works end-to-end.

Adds the augmentations a scratch cluster needs on top of the documented commands:
tolerations copied from a running pod, service-mesh sidecar injection disabled on
client pods, the source password read from the secret, and values recovered from
the live releases.

Prerequisites: kubectl context on the cluster, helm; a provisioned target
PostgreSQL (cnadmin user with CREATEDB, cantonnet database, reachable from pods).
The source instance is left untouched (decommissioning stays a manual step), and
wallet-level verification (balances, a fresh transfer) stays manual.

usage: NAMESPACE=validator1 TARGET_HOST=10.0.0.5 TARGET_PASSWORD=... \
         scripts/test-postgres-migration-k8s.py
"""

import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time


def fail(msg: str) -> None:
    print(f"FAIL: {msg}")
    sys.exit(1)


def require_env(name: str, hint: str) -> str:
    value = os.environ.get(name)
    if not value:
        fail(f"set {name} to {hint}")
    return value


NAMESPACE = require_env("NAMESPACE", "the validator namespace")
TARGET_HOST = require_env("TARGET_HOST", "the target PostgreSQL host or IP")
TARGET_PASSWORD = require_env("TARGET_PASSWORD", "the cnadmin password on the target")
SOURCE_HOST = os.environ.get("SOURCE_HOST", "postgres")  # splice-postgres release/service name
SECRET_NAME = os.environ.get("SECRET_NAME", "postgres-secrets")
HELM_REPO = os.environ.get("HELM_REPO", "oci://ghcr.io/digital-asset/decentralized-canton-sync/helm")
PG_CLIENT_IMAGE = os.environ.get("PG_CLIENT_IMAGE", "postgres:17")


def run(args: list[str], capture: bool = False, check: bool = True,
        stdin_data: str | None = None) -> subprocess.CompletedProcess:
    # stdin defaults to /dev/null so attached pods can never consume anything
    return subprocess.run(
        args,
        text=True,
        input=stdin_data,
        stdin=None if stdin_data is not None else subprocess.DEVNULL,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        check=check,
    )


def kubectl_json(*args: str):
    out = run(["kubectl", *args, "-o", "json"], capture=True).stdout
    return json.loads(out)


for tool in ("kubectl", "helm"):
    from shutil import which
    if which(tool) is None:
        fail(f"{tool} not found")

WORK = tempfile.mkdtemp(prefix="pgmig-k8s-")
print(f"work dir: {WORK}")

secret = kubectl_json("get", "secret", SECRET_NAME, "-n", NAMESPACE)
POSTGRES_PASSWORD = base64.b64decode(secret["data"]["postgresPassword"]).decode()

# Tolerations from the running postgres pod; sidecar injection off for client pods.
source_pod = kubectl_json("get", "pod", f"{SOURCE_HOST}-0", "-n", NAMESPACE)
OVERRIDES = json.dumps({
    "metadata": {"annotations": {"sidecar.istio.io/inject": "false"}},
    "spec": {"tolerations": source_pod["spec"].get("tolerations", [])},
})


def pgpod(name: str, password: str, *cmd: str, env: dict[str, str] | None = None,
          check: bool = True) -> subprocess.CompletedProcess:
    """Run a one-off client pod; returns the completed process with output captured."""
    env_args = [f"--env=PGPASSWORD={password}"]
    for key, value in (env or {}).items():
        env_args.append(f"--env={key}={value}")
    result = run(
        ["kubectl", "run", name, "--rm", "-i", "--restart=Never", "-n", NAMESPACE,
         f"--image={PG_CLIENT_IMAGE}", *env_args, f"--overrides={OVERRIDES}", "--", *cmd],
        capture=True, check=False,
    )
    if check and result.returncode != 0:
        print(result.stdout)
        print(result.stderr, file=sys.stderr)
        fail(f"pod {name} failed running: {' '.join(cmd[:3])} ...")
    return result


def pod_lines(result: subprocess.CompletedProcess) -> list[str]:
    """Pod stdout minus kubectl chatter and blanks."""
    return [
        line for line in result.stdout.splitlines()
        if line.strip() and not line.startswith("pod ")
    ]


print("### 0. Probe the target (reachability, CREATEDB, connection limit)")
probe = pgpod("pg-client", TARGET_PASSWORD,
              "psql", "-h", TARGET_HOST, "-U", "cnadmin", "-d", "cantonnet",
              "-c", "CREATE DATABASE probe", "-c", "DROP DATABASE probe",
              "-c", "SHOW max_connections")
print(probe.stdout, end="")

print("### 1. Enumerate the databases")
enum = pgpod("pg-client", POSTGRES_PASSWORD,
             "psql", "-h", SOURCE_HOST, "-U", "cnadmin", "-d", "cantonnet", "-tA",
             "-c", "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres'")
databases = pod_lines(enum)
print("\n".join(databases))
if not databases:
    fail("no databases enumerated")

print("### 2. Stop the applications")
downtime_start = time.monotonic()
run(["kubectl", "scale", "deployment", "--all", "--replicas=0", "-n", NAMESPACE])
for _ in range(60):
    pods = kubectl_json("get", "pods", "-n", NAMESPACE)["items"]
    still_running = [
        p["metadata"]["name"] for p in pods
        if p["status"].get("phase") == "Running"
        and not p["metadata"]["name"].startswith(f"{SOURCE_HOST}-")
    ]
    if not still_running:
        break
    time.sleep(5)
else:
    fail(f"pods still running after quiesce: {still_running}")

print("### 3. Create the databases on the target")
for db in databases:
    if db == "cantonnet":
        continue
    result = pgpod("pg-client", TARGET_PASSWORD,
                   "psql", "-h", TARGET_HOST, "-U", "cnadmin", "-d", "cantonnet",
                   "-c", f'DROP DATABASE IF EXISTS "{db}" WITH (FORCE)',
                   "-c", f'CREATE DATABASE "{db}"')
    print(result.stdout, end="")

print("### 4. Copy each database")
for db in databases:
    copy_cmd = (
        f"PGPASSWORD=\"$SOURCE_PGPASSWORD\" pg_dump -h {SOURCE_HOST} -U cnadmin -Fc '{db}'"
        f" | PGPASSWORD=\"$TARGET_PGPASSWORD\" pg_restore -h {TARGET_HOST} -U cnadmin"
        f" --no-owner --no-privileges --exit-on-error -d '{db}'"
    )
    result = pgpod("pg-migrate", TARGET_PASSWORD, "bash", "-c", copy_cmd,
                   env={"SOURCE_PGPASSWORD": POSTGRES_PASSWORD,
                        "TARGET_PGPASSWORD": TARGET_PASSWORD},
                   check=False)
    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr, file=sys.stderr)
        fail(f"copy of {db}")
    print(f"copied: {db}")

print("### 4b. Compare table counts per database (source vs target)")
COUNT_SQL = ("SELECT count(*) FROM pg_tables"
             " WHERE schemaname NOT IN ('pg_catalog','information_schema')")


def count_tables(host: str, password: str, db: str) -> int:
    result = pgpod("pg-client", password,
                   "psql", "-h", host, "-U", "cnadmin", "-d", db, "-tA", "-c", COUNT_SQL)
    return int(pod_lines(result)[0])


for db in databases:
    src = count_tables(SOURCE_HOST, POSTGRES_PASSWORD, db)
    tgt = count_tables(TARGET_HOST, TARGET_PASSWORD, db)
    print(f"tables in {db}: source={src} target={tgt}")
    if src != tgt:
        fail(f"table count mismatch in {db}")

print("### 5. Point the applications at the target")
# Decide which releases to repoint BEFORE touching the secret: a release
# qualifies when its persistence.host is the source instance, as a bare
# service name or a cluster FQDN (e.g. <name>.<namespace>.svc.cluster.local).
releases = json.loads(run(["helm", "list", "-n", NAMESPACE, "-o", "json"], capture=True).stdout)
to_repoint = []
for release in releases:
    name, chart = release["name"], release["chart"]
    if chart.startswith("splice-postgres-"):
        continue
    values_raw = run(["helm", "get", "values", name, "-n", NAMESPACE, "-o", "json"],
                     capture=True).stdout
    values = json.loads(values_raw) or {}
    host = (values.get("persistence") or {}).get("host", "")
    if host == SOURCE_HOST or host.startswith(f"{SOURCE_HOST}."):
        to_repoint.append((name, chart, values))
if not to_repoint:
    fail(f"no release has persistence.host pointing at {SOURCE_HOST}")
print("releases to repoint:")
for name, chart, _ in to_repoint:
    print(f"{name} {chart}")

secret_yaml = run(["kubectl", "create", "secret", "generic", SECRET_NAME,
                   f"--from-literal=postgresPassword={TARGET_PASSWORD}",
                   "-n", NAMESPACE, "--dry-run=client", "-o", "yaml"], capture=True).stdout
run(["kubectl", "apply", "-f", "-"], stdin_data=secret_yaml)


def repoint(name: str, chart: str, values: dict) -> None:
    match = re.match(r"^(?P<chart>.+)-(?P<version>\d+\.\d+\..+)$", chart)
    if not match:
        fail(f"cannot parse chart/version from {chart}")
    values.setdefault("persistence", {})
    values["persistence"]["host"] = TARGET_HOST
    values["persistence"]["port"] = 5432
    values_file = os.path.join(WORK, f"{name}-values.json")  # helm accepts JSON values
    with open(values_file, "w") as f:
        json.dump(values, f, indent=2)
    print(f"upgrading {name} ({match['chart']} {match['version']})")
    run(["helm", "upgrade", name, f"{HELM_REPO}/{match['chart']}",
         "--version", match["version"], "-f", values_file,
         "-n", NAMESPACE, "--wait", "--timeout", "10m"])


# participants first, mirroring install order
for name, chart, values in to_repoint:
    if chart.startswith("splice-participant-"):
        repoint(name, chart, values)
for name, chart, values in to_repoint:
    if not chart.startswith("splice-participant-"):
        repoint(name, chart, values)

print("### 6. Verify")
run(["kubectl", "wait", "deployment", "--all", "--for=condition=Available",
     "-n", NAMESPACE, "--timeout=600s"])
print(f"quiesce -> available: {int(time.monotonic() - downtime_start)}s")

activity = pgpod("pg-client", TARGET_PASSWORD,
                 "psql", "-h", TARGET_HOST, "-U", "cnadmin", "-d", "cantonnet",
                 "-c", "SHOW server_version",
                 "-c", "SELECT datname, count(*) FROM pg_stat_activity"
                       " WHERE datname <> 'cantonnet' GROUP BY 1")
print(activity.stdout, end="")
if "participant" not in activity.stdout:
    fail("no application connections on the target")

idle = pgpod("pg-client", POSTGRES_PASSWORD,
             "psql", "-h", SOURCE_HOST, "-U", "cnadmin", "-d", "cantonnet", "-tA",
             "-c", "SELECT count(*) FROM pg_stat_activity"
                   " WHERE datname NOT IN ('cantonnet','postgres') AND datname IS NOT NULL")
if pod_lines(idle)[0] != "0":
    fail(f"{pod_lines(idle)[0]} connection(s) still on the source instance")

print(f"PASS: applications migrated to {TARGET_HOST} (source instance idle)")
print("manual follow-ups: wallet balance + fresh transfer; scale non-helm deployments")
print("back up; decommission the source per step 7 of the guide once verified.")
