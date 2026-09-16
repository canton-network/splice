#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""End-to-end test of the PostgreSQL major-version migration documented at
https://docs.canton.network/global-synchronizer/production-operations/validator-postgres-migration,
run against LocalNet.

Flow: LocalNet on postgres:SRC_PG -> seed wallet data (tap + cross-participant
transfer) -> stop applications -> pg_dump every database with the target-version
client -> fresh postgres:TGT_PG -> pg_restore -> restart -> assert that balances
are preserved and a new transfer succeeds.

WARNING: tears down any running LocalNet compose project (down -v) at start.

usage: [SRC_PG=14] [TGT_PG=17] [IMAGE_TAG=0.6.11] scripts/test-postgres-migration.py
"""

import base64
import hashlib
import hmac
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

SRC_PG = os.environ.get("SRC_PG", "14")
TGT_PG = os.environ.get("TGT_PG", "17")
IMAGE_TAG = os.environ.get("IMAGE_TAG", "0.6.11")
DB_PASSWORD = "supersafe"

REPO_ROOT = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                           capture_output=True, text=True, check=True).stdout.strip()
LOCALNET_DIR = os.environ.get("LOCALNET_DIR", f"{REPO_ROOT}/cluster/compose/localnet")
DUMPS = tempfile.mkdtemp(prefix="pgmig-dumps-")


def fail(msg: str) -> None:
    print(f"FAIL: {msg}")
    sys.exit(1)


def run(args: list[str], capture: bool = False, check: bool = True,
        env: dict[str, str] | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        args, text=True, check=check,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        env={**os.environ, **(env or {})},
    )


def compose(*args: str, postgres_version: str, check: bool = True) -> None:
    run(["docker", "compose",
         "--env-file", f"{LOCALNET_DIR}/compose.env",
         "--env-file", f"{LOCALNET_DIR}/env/common.env",
         "-f", f"{LOCALNET_DIR}/compose.yaml",
         "-f", f"{LOCALNET_DIR}/resource-constraints.yaml",
         "--profile", "sv", "--profile", "app-provider", "--profile", "app-user",
         *args],
        check=check,
        env={"IMAGE_TAG": IMAGE_TAG, "LOCALNET_DIR": LOCALNET_DIR,
             "POSTGRES_VERSION": postgres_version})


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def wallet(port: int, user: str, method: str, path: str, body: dict | None = None):
    """Call a LocalNet wallet API through nginx with an unsafe-auth HS256 JWT."""
    header = b64url(b'{"alg":"HS256","typ":"JWT"}')
    payload = b64url(json.dumps(
        {"sub": user, "aud": "https://canton.network.global", "exp": 4102444800}).encode())
    sig = b64url(hmac.new(b"unsafe", f"{header}.{payload}".encode(), hashlib.sha256).digest())
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Host": "wallet.localhost",
                 "Authorization": f"Bearer {header}.{payload}.{sig}",
                 **({"Content-Type": "application/json"} if body is not None else {})},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def retry(what: str, attempts: int, delay: int, fn):
    for _ in range(attempts):
        try:
            result = fn()
            if result is not None:
                return result
        except (urllib.error.URLError, OSError, json.JSONDecodeError, KeyError):
            pass
        time.sleep(delay)
    fail(f"{what} did not succeed after {attempts} attempts")


def balance(port: int, user: str) -> str:
    return wallet(port, user, "GET", "/api/validator/v0/wallet/balance")["effective_unlocked_qty"]


def wait_healthy() -> None:
    def all_healthy():
        out = run(["docker", "ps", "--format", "{{.Names}} {{.Status}}"], capture=True).stdout
        lines = [ln for ln in out.splitlines() if ln.strip()]
        unhealthy = [ln for ln in lines if "(healthy)" not in ln]
        return True if len(unhealthy) <= 1 else None  # nginx has no healthcheck
    retry("containers becoming healthy", 60, 5, all_healthy)


def wait_wallet(port: int, user: str) -> None:
    # healthy containers do not imply onboarded wallets; wait for the wallet API too
    retry(f"wallet for {user}", 60, 5,
          lambda: True if wallet(port, user, "GET", "/api/validator/v0/wallet/user-status")
          .get("user_wallet_installed") else None)


def wait_pg_healthy() -> None:
    def healthy():
        out = run(["docker", "inspect", "-f", "{{.State.Health.Status}}", "postgres"],
                  capture=True, check=False).stdout.strip()
        return True if out == "healthy" else None
    retry("postgres becoming healthy", 30, 2, healthy)


def tap() -> None:
    # right after bootstrap taps fail until the first open mining round exists; retry
    retry("tap", 30, 10,
          lambda: True if "contract_id" in wallet(
              2000, "app-user", "POST", "/api/validator/v0/wallet/tap", {"amount": "1000.0"})
          else None)


def transfer(amount: str, tracking_id: str) -> None:
    provider = wallet(3000, "app-provider", "GET",
                      "/api/validator/v0/wallet/user-status")["party_id"]
    offer = wallet(2000, "app-user", "POST", "/api/validator/v0/wallet/transfer-offers",
                   {"receiver_party_id": provider, "amount": amount,
                    "description": tracking_id, "expires_at": 4102444800000000,
                    "tracking_id": tracking_id})
    if "offer_contract_id" not in offer:
        fail(f"transfer offer {tracking_id} not created: {offer}")

    def find_offer():  # receiver sees the offer only after ingestion
        offers = wallet(3000, "app-provider", "GET",
                        "/api/validator/v0/wallet/transfer-offers")["offers"]
        return offers[0]["contract_id"] if offers else None
    cid = retry(f"offer visibility for {tracking_id}", 30, 2, find_offer)
    accepted = wallet(3000, "app-provider", "POST",
                      f"/api/validator/v0/wallet/transfer-offers/{cid}/accept", {})
    if "accepted_offer_contract_id" not in accepted:
        fail(f"transfer {tracking_id} not accepted: {accepted}")


print(f"migrating postgres:{SRC_PG} -> postgres:{TGT_PG} (splice {IMAGE_TAG}); dumps in {DUMPS}")

print(f"### 0. Clean slate, start LocalNet on postgres:{SRC_PG}")
compose("down", "-v", "--remove-orphans", postgres_version=SRC_PG, check=False)
run(["docker", "volume", "rm", "-f", f"localnet_postgres_pg{SRC_PG}_backup"], check=False)
compose("up", "-d", postgres_version=SRC_PG)
wait_healthy()
run(["docker", "exec", "postgres", "postgres", "--version"])

print("### 1. Seed data: tap 1000 USD on app-user, transfer 12345 CC to app-provider")
wait_wallet(2000, "app-user")
wait_wallet(3000, "app-provider")
tap()
time.sleep(3)
transfer("12345.0", "pgmig-pre")
time.sleep(8)
user_bal_before = balance(2000, "app-user")
prov_bal_before = balance(3000, "app-provider")
print(f"pre-migration balances: app-user={user_bal_before} app-provider={prov_bal_before}")

print("### 2. Quiesce: stop everything except postgres")
compose("stop", postgres_version=SRC_PG)
compose("start", "postgres", postgres_version=SRC_PG)
wait_pg_healthy()
running = run(["docker", "ps", "--format", "{{.Names}}"], capture=True).stdout.split()
if running != ["postgres"]:
    fail(f"quiesce incomplete, still running: {running}")

print(f"### 3. Dump all databases with the postgres:{TGT_PG} client")
databases = run(
    ["docker", "exec", "postgres", "psql", "-U", "cnadmin", "-d", "postgres", "-tA",
     "-c", "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres'"],
    capture=True).stdout.split()
for db in databases:
    run(["docker", "run", "--rm", "--network", "localnet", "-e", f"PGPASSWORD={DB_PASSWORD}",
         "-v", f"{DUMPS}:/dumps", f"postgres:{TGT_PG}",
         "pg_dump", "-h", "postgres", "-U", "cnadmin", "-Fc",
         "-f", f"/dumps/{db}.dump", db])
run(["ls", "-lh", DUMPS])

print(f"### 4. Keep the pg{SRC_PG} volume as rollback point, remove original")
compose("stop", "postgres", postgres_version=SRC_PG)
run(["docker", "rm", "-f", "postgres"])
run(["docker", "volume", "create", f"localnet_postgres_pg{SRC_PG}_backup"], capture=True)
run(["docker", "run", "--rm", "-v", "localnet_postgres:/from:ro",
     "-v", f"localnet_postgres_pg{SRC_PG}_backup:/to",
     "alpine", "sh", "-c", "cp -a /from/. /to/"])
run(["docker", "volume", "rm", "localnet_postgres"])

print(f"### 5. Fresh postgres:{TGT_PG} (entrypoint pre-creates empty databases)")
compose("up", "-d", "postgres", postgres_version=TGT_PG)
wait_pg_healthy()
run(["docker", "exec", "postgres", "postgres", "--version"])
# create databases the entrypoint did not pre-create (all exist on LocalNet;
# mirrors the migration guide, where start.sh-injected names are missing)
for db in databases:
    run(["docker", "exec", "postgres", "psql", "-U", "cnadmin", "-d", "postgres",
         "-c", f'CREATE DATABASE "{db}"'], capture=True, check=False)

print("### 6. Restore every dump")
for db in databases:
    run(["docker", "run", "--rm", "--network", "localnet", "-e", f"PGPASSWORD={DB_PASSWORD}",
         "-v", f"{DUMPS}:/dumps:ro", f"postgres:{TGT_PG}",
         "pg_restore", "-h", "postgres", "-U", "cnadmin",
         "--no-owner", "--no-privileges", "--exit-on-error", "-d", db, f"/dumps/{db}.dump"])
    print(f"restored: {db}")

print(f"### 7. Restart the full stack on postgres:{TGT_PG}")
compose("up", "-d", postgres_version=TGT_PG)
wait_healthy()
wait_wallet(2000, "app-user")
wait_wallet(3000, "app-provider")

print("### 8. Assertions")
user_bal_after = balance(2000, "app-user")
prov_bal_after = balance(3000, "app-provider")
print(f"post-migration balances: app-user={user_bal_after} app-provider={prov_bal_after}")
# balances only grow between the two checks (devnet reward issuance)
if float(user_bal_after) < float(user_bal_before):
    fail("app-user balance shrank across migration")
if float(prov_bal_after) < float(prov_bal_before):
    fail("app-provider balance shrank across migration")

transfer("55.0", "pgmig-post")

errors = run(["docker", "logs", "splice", "--since", "10m"], capture=True).stdout
error_count = errors.count('"level":"ERROR"')
if error_count:
    fail(f"{error_count} ERROR lines in splice logs")

print(f"PASS: migration postgres:{SRC_PG} -> postgres:{TGT_PG} verified")
