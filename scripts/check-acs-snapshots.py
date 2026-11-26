#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Checks data consistency across all scans of a network.

Checks:
  updates         - /v2/updates (within a record time window)
  import_updates  - /v0/backfilling/import-updates (limited by number of contracts)
  acs             - /v2/state/acs on the latest snapshot available on all scans (limited by number of contracts)
  votes           - /v0/admin/sv/voteresults and /v1/admin/sv/voteresults: all vote results completed before a
                    cutoff, compared across scans for each version, and between the two versions on each scan.
                    Scans that do not serve v1 yet (older version, or still backfilling) are skipped for v1.

Guarantees at most one in-flight request per scan.

Examples:
  check-acs-snapshots.py mainnet 4
  check-acs-snapshots.py testnet 5 --profile balanced --acs-limit 200000
  check-acs-snapshots.py https://scan.sv-2.dev.global.canton.network.digitalasset.com 3 --checks acs

Exit codes: 0 = consistent, 1 = inconsistencies found, 2 = some scans failed.

Requires: pip install requests
"""

import argparse
import hashlib
import json
import logging
import random
import sys
import time
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Mapping, Optional, TypeVar

import requests

log = logging.getLogger("scan-consistency")

NETWORKS = {
    "cilr": "https://scan.sv-2.cilr.global.canton.network.digitalasset.com",
    "devnet": "https://scan.sv-2.dev.global.canton.network.digitalasset.com",
    "testnet": "https://scan.sv-2.test.global.canton.network.digitalasset.com",
    "mainnet": "https://scan.sv-2.global.canton.network.digitalasset.com",
}

PROFILES = {
    "fast":     dict(update_window="1m",  import_limit=1_000,  acs_limit=1_000,  max_tracked_ids=100_000),
    "balanced": dict(update_window="30m", import_limit=20_000, acs_limit=50_000, max_tracked_ids=500_000),
    "full":     dict(update_window="24h", import_limit=0,      acs_limit=0,      max_tracked_ids=1_000_000),
}

ALL_CHECKS = ["updates", "import_updates", "acs", "votes"]
UNITS = {"updates": "updates", "import_updates": "import updates", "acs": "contracts",
         "votes_v0": "vote results", "votes_v1": "vote results"}
MAX_EXAMPLES = 5
MASK256 = (1 << 256) - 1


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

def parse_duration(s: str) -> timedelta:
    units = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    return timedelta(seconds=float(s[:-1]) * units[s[-1]]) if s[-1] in units else timedelta(seconds=float(s))


def parse_ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def fmt_ts(d: datetime) -> str:
    return d.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def sha256_int(data: bytes) -> int:
    return int.from_bytes(hashlib.sha256(data).digest(), "big")


def short(h: int) -> str:
    return format(h, "064x")[:16]


def examples(items: Iterable[str]) -> str:
    items = sorted(items)
    s = ", ".join(items[:MAX_EXAMPLES])
    return s + (f", ... ({len(items) - MAX_EXAMPLES} more)" if len(items) > MAX_EXAMPLES else "")


class ScanError(Exception):
    pass


class ScanUnavailable(ScanError):
    """The scan cannot serve the request yet, e.g., because it is still backfilling."""


class ScanClient:
    """HTTP client for a single scan. Only ever used from one thread at a time."""

    def __init__(self, url: str, args: argparse.Namespace):
        self.url = url.rstrip("/")
        self.args = args
        self.session = requests.Session()

    def request(self, method: str, path: str, fail_fast: Iterable[int] = (), **kwargs: Any) -> Optional[dict[str, Any]]:
        """Returns parsed JSON, None on 404, raises ScanError on permanent failure.
        Status codes in `fail_fast` raise ScanUnavailable right away instead of being retried."""
        full = f"{self.url}/api/scan{path}"
        for attempt in range(self.args.max_retries + 1):
            try:
                r = self.session.request(method, full, timeout=self.args.request_timeout, **kwargs)
                if r.status_code == 200:
                    result: dict[str, Any] = r.json()
                    return result
                if r.status_code == 404:
                    return None
                if r.status_code in fail_fast:
                    raise ScanUnavailable(f"{method} {full} -> HTTP {r.status_code}: {r.text[:300]}")
                if r.status_code != 429 and r.status_code < 500:
                    raise ScanError(f"{method} {full} -> HTTP {r.status_code}: {r.text[:300]}")
                reason = f"HTTP {r.status_code}"
            except (requests.ConnectionError, requests.Timeout,
                    requests.exceptions.ChunkedEncodingError, ValueError) as e:
                reason = type(e).__name__
            if attempt == self.args.max_retries:
                raise ScanError(f"{method} {full} failed after {attempt + 1} attempts ({reason})")
            delay = min(60.0, 2 ** attempt) * (0.5 + random.random() / 2)
            log.warning("%s: %s %s failed (%s), retry %d/%d in %.1fs",
                        self.url, method, path, reason, attempt + 1, self.args.max_retries, delay)
            time.sleep(delay)
        raise AssertionError("unreachable: the last attempt raises")


@dataclass
class Digest:
    """Bounded-memory summary of an ordered collection of (id, item) pairs."""
    max_tracked: int
    count: int = 0
    id_agg: int = 0        # order-independent hash over ids (set equality)
    content_agg: int = 0   # order-independent hash over canonical item contents (semantic equality)
    seq_ids: "hashlib._Hash" = field(default_factory=hashlib.sha256)  # ordered ids
    seq_raw: "hashlib._Hash" = field(default_factory=hashlib.sha256)  # ordered items, as received
    ids: set[str] = field(default_factory=set)  # first `max_tracked` ids, for set diffs
    truncated: bool = False
    error: Optional[str] = None

    def add(self, item_id: str, item: Any) -> None:
        self.count += 1
        id_bytes = item_id.encode()
        self.id_agg = (self.id_agg + sha256_int(id_bytes)) & MASK256
        canonical = json.dumps(item, sort_keys=True, separators=(",", ":")).encode()
        self.content_agg = (self.content_agg + sha256_int(canonical)) & MASK256
        # Length-prefix each entry so concatenation is unambiguous.
        self.seq_ids.update(len(id_bytes).to_bytes(4, "big") + id_bytes)
        raw = json.dumps(item, separators=(",", ":"), ensure_ascii=False).encode()  # preserves key order
        self.seq_raw.update(len(raw).to_bytes(8, "big") + raw)
        if len(self.ids) < self.max_tracked:
            self.ids.add(item_id)
        else:
            self.truncated = True

    def id_key(self) -> tuple[int, int]:
        return (self.count, self.id_agg)

    def summary(self) -> str:
        if self.error:
            return f"error: {self.error}"
        return (f"count={self.count} ids={short(self.id_agg)} content={short(self.content_agg)} "
                f"seq={self.seq_raw.hexdigest()[:16]}")


class Progress:
    def __init__(self, url: str, what: str, interval: float):
        self.url, self.what, self.interval = url, what, interval
        self.last = time.monotonic()
        self.start = self.last

    def tick(self, msg: str, force: bool = False) -> None:
        now = time.monotonic()
        if force or now - self.last >= self.interval:
            self.last = now
            log.info("%s: [%s] %s (%.0fs)", self.url, self.what, msg, now - self.start)


T = TypeVar("T")


def run_per_scan(clients: List[ScanClient], fn: Callable[[ScanClient], T]) -> Dict[str, T | Exception]:
    """Run fn once per scan, one thread per scan => at most one request per scan in flight."""
    with ThreadPoolExecutor(max_workers=max(1, len(clients))) as ex:
        futures = {c.url: ex.submit(fn, c) for c in clients}
    out: Dict[str, T | Exception] = {}
    for url, f in futures.items():
        try:
            out[url] = f.result()
        except Exception as e:  # noqa: BLE001
            log.error("%s: unexpected error: %s", url, e)
            out[url] = e
    return out


# ---------------------------------------------------------------------------
# Fetchers
# ---------------------------------------------------------------------------

def fetch_latest_record_time(c: ScanClient) -> Optional[str]:
    response = c.request("GET", "/v0/events/latest-record-time")
    return response.get("record_time") if response else None


def fetch_latest_snapshot(c: ScanClient, mid: int, before: str) -> Optional[str]:
    r = c.request("GET", "/v0/state/acs/snapshot-timestamp", params={"before": before, "migration_id": mid})
    return r.get("record_time") if r else None


def fetch_updates(c: ScanClient, args: argparse.Namespace, start: str, end: datetime) -> Digest:
    """All updates with start < record_time <= end in the given migration. Record times are unique."""
    d = Digest(args.max_tracked_ids)
    prog = Progress(c.url, "updates", args.progress_interval)
    after = {"after_migration_id": args.migration_id, "after_record_time": start}
    try:
        while True:
            body = c.request("POST", "/v2/updates", json={"page_size": args.page_size, "after": after}) or {}
            txs = body.get("transactions", [])
            if not txs:
                break
            done = False
            for tx in txs:
                tx_mid = tx.get("migration_id", tx.get("event", {}).get("migration_id"))
                if tx_mid != args.migration_id or parse_ts(tx["record_time"]) > end:
                    done = True
                    break
                d.add(tx["update_id"], tx)
            if done:
                break
            last = txs[-1]
            after = {"after_migration_id": args.migration_id, "after_record_time": last["record_time"]}
            prog.tick(f"{d.count} updates, at {last['record_time']}")
    except ScanError as e:
        d.error = str(e)
    prog.tick(f"finished: {d.summary()}", force=True)
    return d


def fetch_import_updates(c: ScanClient, args: argparse.Namespace) -> Digest:
    d = Digest(args.max_tracked_ids)
    prog = Progress(c.url, "import_updates", args.progress_interval)
    after, contracts = "", 0
    try:
        while True:
            body = c.request("POST", "/v0/backfilling/import-updates",
                             json={"migration_id": args.migration_id, "after_update_id": after,
                                   "limit": args.page_size}) or {}
            txs = body.get("transactions", [])
            if not txs:
                break
            stop = False
            for tx in txs:
                d.add(tx["update_id"], tx)
                contracts += sum(1 for e in tx.get("events_by_id", {}).values()
                                 if e.get("event_type") == "created_event")
                if args.import_limit and contracts >= args.import_limit:
                    stop = True
                    break
            if stop:
                break
            after = txs[-1]["update_id"]
            prog.tick(f"{d.count} updates, {contracts} contracts")
    except ScanError as e:
        d.error = str(e)
    prog.tick(f"finished: {d.summary()} contracts={contracts}", force=True)
    return d


def fetch_acs(c: ScanClient, args: argparse.Namespace, record_time: str) -> Digest:
    d = Digest(args.max_tracked_ids)
    prog = Progress(c.url, "acs", args.progress_interval)
    token: Optional[str] = None
    try:
        while True:
            payload: dict[str, Any] = {"migration_id": args.migration_id, "record_time": record_time, "page_size": args.page_size}
            if token is not None:
                payload["after"] = token
            body = c.request("POST", "/v2/state/acs", json=payload) or {}
            events = body.get("created_events", [])
            for ev in events:
                d.add(ev["contract_id"], ev)
                if args.acs_limit and d.count >= args.acs_limit:
                    break
            token = body.get("next_page_token")
            if token is None or not events or (args.acs_limit and d.count >= args.acs_limit):
                break
            prog.tick(f"{d.count} contracts")
    except ScanError as e:
        d.error = str(e)
    prog.tick(f"finished: {d.summary()}", force=True)
    return d


@dataclass
class Votes:
    """The vote results of one scan, from both versions of the endpoint."""
    v0: Digest
    v1: Optional[Digest]  # None if the scan does not serve v1 (yet)
    v1_unavailable: Optional[str] = None
    # Vote results by the hash of their canonical JSON without null fields, for comparing the two versions.
    v0_by_hash: dict[int, str] = field(default_factory=dict)
    v1_by_hash: dict[int, str] = field(default_factory=dict)


def vote_id(result: dict[str, Any]) -> str:
    """The id of a vote result: the contract id of its vote request."""
    cid = result.get("request", {}).get("trackingCid")
    return cid if isinstance(cid, str) and cid else f"<no tracking cid, hash {short(canonical_hash(result))}>"


def without_nulls(item: Any) -> Any:
    """Drops the fields with a null value. Optional fields without a value can be null or omitted, depending on
    the version of the app that encoded them."""
    if isinstance(item, dict):
        return {k: without_nulls(v) for k, v in item.items() if v is not None}
    if isinstance(item, list):
        return [without_nulls(v) for v in item]
    return item


def canonical_hash(item: Any) -> int:
    return sha256_int(json.dumps(item, sort_keys=True, separators=(",", ":")).encode())


def completed_before(result: dict[str, Any], cutoff: datetime) -> bool:
    return parse_ts(result["completedAt"]) < cutoff


def fetch_votes(c: ScanClient, args: argparse.Namespace, cutoff: datetime) -> Votes:
    """All vote results completed before `cutoff`, from /v0/admin/sv/voteresults and /v1/admin/sv/voteresults.
    Votes completed later are ignored, so that votes closed while this script runs do not cause differences."""
    prog = Progress(c.url, "votes", args.progress_interval)
    votes = Votes(v0=Digest(args.max_tracked_ids), v1=Digest(args.max_tracked_ids))
    v0_token: Optional[int] = None
    try:
        while True:
            body: dict[str, Any] = {"limit": args.page_size}
            if v0_token is not None:
                body["pageToken"] = v0_token
            v0_page = c.request("POST", "/v0/admin/sv/voteresults", json=body) or {}
            results = v0_page.get("dso_rules_vote_results", [])
            for result in results:
                if completed_before(result, cutoff):
                    votes.v0.add(vote_id(result), result)
                    votes.v0_by_hash[canonical_hash(without_nulls(result))] = vote_id(result)
            v0_token = v0_page.get("next_page_token")
            if not results or v0_token is None:
                break
            prog.tick(f"v0: {votes.v0.count} vote results")
    except ScanError as e:
        votes.v0.error = str(e)

    assert votes.v1 is not None
    v1_token: Optional[int] = None
    try:
        while True:
            body = {"page_size": args.page_size}
            if v1_token is not None:
                body["page_token"] = v1_token
            # Fail fast while the scan is still backfilling its votes, instead of retrying.
            page = c.request("POST", "/v1/admin/sv/voteresults", fail_fast=[503], json=body)
            if page is None:
                raise ScanUnavailable("/v1/admin/sv/voteresults not found, the scan is probably on an older version")
            items = page.get("vote_results", [])
            for item in items:
                if completed_before(item["vote_result"], cutoff):
                    votes.v1.add(f"{item['update_id']}:{item['event_number']}", item)
                    votes.v1_by_hash[canonical_hash(without_nulls(item["vote_result"]))] = vote_id(item["vote_result"])
            v1_token = page.get("next_page_token")
            if not items or v1_token is None:
                break
            prog.tick(f"v1: {votes.v1.count} vote results")
    except ScanUnavailable as e:
        votes.v1, votes.v1_unavailable, votes.v1_by_hash = None, str(e), {}
    except ScanError as e:
        votes.v1.error = str(e)

    v1 = votes.v1.summary() if votes.v1 else f"unavailable: {votes.v1_unavailable}"
    prog.tick(f"finished: v0 {votes.v0.summary()}, v1 {v1}", force=True)
    return votes


# ---------------------------------------------------------------------------
# Comparison / reporting
# ---------------------------------------------------------------------------

def compare_digests(name: str, unit: str, results: Dict[str, Digest], max_tracked: int) -> bool:
    log.info("=" * 100)
    log.info("Result [%s]", name)

    failed = [u for u, d in results.items() if d.error]
    for url in failed:
        log.error("  ✖ %s: failed: %s", url, results[url].error)

    # Group scans by their set of ids.
    id_groups: Dict[object, List[str]] = {}
    for url, d in results.items():
        if not d.error:
            id_groups.setdefault(d.id_key(), []).append(url)
    if not id_groups:
        log.error("  no scan returned data")
        return False

    ref_key = max(id_groups, key=lambda k: len(id_groups[k]))
    ref_url = id_groups[ref_key][0]
    ref = results[ref_url]
    log.info("  reference: %s (%d of %d scans have the same set of %d %s)",
             ref_url, len(id_groups[ref_key]), len(results), ref.count, unit)

    consistent = not failed and len(id_groups) == 1

    for key, urls in id_groups.items():
        for url in urls:
            d = results[url]
            if key == ref_key:
                if d.seq_raw.digest() == ref.seq_raw.digest():
                    log.info("  ✔ %s: identical %s (same set, content, order)", url, unit)
                elif d.content_agg != ref.content_agg:
                    consistent = False
                    log.error("  ✖ %s: same set of %d %s, but their content differs", url, d.count, unit)
                elif d.seq_ids.digest() != ref.seq_ids.digest():
                    consistent = False
                    log.error("  ✖ %s: same set of %d %s with the same content, but in a different order",
                              url, d.count, unit)
                else:
                    consistent = False
                    log.error("  ✖ %s: same %d %s in the same order with the same content, "
                              "but serialized differently (e.g. JSON field order)", url, d.count, unit)
                continue

            log.error("  ✖ %s: different set of %s (%d here vs %d in reference)", url, unit, d.count, ref.count)
            missing = ref.ids - d.ids
            extra = d.ids - ref.ids
            if d.truncated or ref.truncated:
                log.error("    note: only the first %d %s per scan are tracked; "
                          "differences near that boundary may be spurious", max_tracked, unit)
            if missing:
                log.error("    %d %s missing: %s", len(missing), unit, examples(missing))
            if extra:
                log.error("    %d extra %s: %s", len(extra), unit, examples(extra))
            if not missing and not extra:
                log.error("    the first %d %s agree; the difference is beyond the tracked range",
                          max_tracked, unit)
    return consistent


def compare_vote_versions(results: Mapping[str, Votes]) -> bool:
    """Checks that each scan serves the same vote results from v0 (txlog) and v1 (votes store).
    Ignores the order: v0 orders ties by insertion order, and compares timestamps as text.
    Ignores null fields: v1 serves the JSON as stored, v0 re-encodes it with the codegen of the serving app."""
    log.info("=" * 100)
    log.info("Result [votes_v0_vs_v1] (each scan serves the same vote results from v0 and v1)")
    consistent = True
    for url, votes in results.items():
        if votes.v1 is None:
            log.warning("  - %s: skipped, v1 is unavailable: %s", url, votes.v1_unavailable)
            continue
        if votes.v0.error or votes.v1.error:
            consistent = False
            log.error("  ✖ %s: failed: %s", url, votes.v0.error or votes.v1.error)
            continue
        missing = votes.v0_by_hash.keys() - votes.v1_by_hash.keys()
        extra = votes.v1_by_hash.keys() - votes.v0_by_hash.keys()
        if not missing and not extra:
            log.info("  ✔ %s: the same %d vote results", url, len(votes.v0_by_hash))
            continue
        consistent = False
        log.error("  ✖ %s: %d vote results in v0, %d in v1", url, len(votes.v0_by_hash), len(votes.v1_by_hash))
        if missing:
            log.error("    %d only in v0: %s", len(missing), examples(votes.v0_by_hash[h] for h in missing))
        if extra:
            log.error("    %d only in v1: %s", len(extra), examples(votes.v1_by_hash[h] for h in extra))
    return consistent


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("network", help=f"One of {list(NETWORKS)} or a scan URL used to discover all scans")
    p.add_argument("migration_id", type=int)
    p.add_argument("--profile", choices=list(PROFILES), default="fast")
    p.add_argument("--checks", default=",".join(ALL_CHECKS), help=f"Comma-separated subset of {ALL_CHECKS}")
    p.add_argument("--scans", help="Comma-separated scan URLs (skips DSO discovery)")
    p.add_argument("--exclude-scans", default="", help="Comma-separated scan URLs to skip")
    # Profile overrides
    p.add_argument("--update-window", help="Window length, e.g. 30s, 10m, 2h, 1d")
    p.add_argument("--updates-from", help="Window start (ISO 8601). Default: end - window")
    p.add_argument("--updates-to", help="Window end (ISO 8601). Default: min over scans of the latest record time")
    p.add_argument("--import-limit", type=int, help="Max import contracts to compare (0 = all)")
    p.add_argument("--acs-limit", type=int, help="Max ACS contracts to compare (0 = all)")
    p.add_argument("--acs-before", default=None, help="Use latest common snapshot before this time (default: now)")
    p.add_argument("--max-tracked-ids", type=int, help="Max ids per scan kept in memory for set diffs")
    p.add_argument("--votes-before", default=None,
                   help="Only compare vote results completed before this time (ISO 8601). Default: 10 minutes ago")
    # Transport
    p.add_argument("--page-size", type=int, default=100)
    p.add_argument("--max-retries", type=int, default=8)
    p.add_argument("--request-timeout", type=float, default=60)
    p.add_argument("--progress-interval", type=float, default=30)
    a = p.parse_args()
    for k, v in PROFILES[a.profile].items():
        if getattr(a, k) is None:
            setattr(a, k, v)
    a.checks = [c.strip() for c in a.checks.split(",") if c.strip()]
    unknown = set(a.checks) - set(ALL_CHECKS)
    if unknown:
        p.error(f"unknown checks: {unknown}")
    return a


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-7s %(message)s", stream=sys.stdout)
    args = parse_args()
    mid = args.migration_id
    log.info("Parameters: %s", vars(args))

    # --- discover scans ---
    if args.scans:
        urls = [u.strip().rstrip("/") for u in args.scans.split(",") if u.strip()]
    else:
        seed = ScanClient(NETWORKS.get(args.network, args.network), args)
        dso = seed.request("GET", "/v0/dso")
        if dso is None:
            log.error("%s: no DSO info found", seed.url)
            return 1
        urls = sorted({n[1]["scan"]["publicUrl"].rstrip("/")
                       for s in dso["sv_node_states"]
                       for n in s["contract"]["payload"]["state"]["synchronizerNodes"]
                       if n[1].get("scan")})
    excluded = {u.strip().rstrip("/") for u in args.exclude_scans.split(",") if u.strip()}
    urls = [u for u in urls if u not in excluded]
    log.info("Scans (%d): %s", len(urls), ", ".join(urls))
    clients = [ScanClient(u, args) for u in urls]

    ok = True
    had_errors = False

    # --- phase 1: latest snapshot, per scan ---
    acs_before = args.acs_before or fmt_ts(datetime.now(timezone.utc))

    def phase1(c: ScanClient) -> tuple[Optional[str], Optional[str]]:
        snap = fetch_latest_snapshot(c, mid, acs_before) if "acs" in args.checks else None
        latest = fetch_latest_record_time(c) if "updates" in args.checks and not args.updates_to else None
        log.info("%s: latest_snapshot=%s latest_record_time=%s", c.url, snap, latest)
        return snap, latest

    p1 = run_per_scan(clients, phase1)
    if "updates" in args.checks:
        latest = {url: result[1] for url, result in p1.items() if not isinstance(result, Exception) and result[1]}
        end = None
        if args.updates_to:
            end = parse_ts(args.updates_to)
        elif latest:
            slowest = min(latest, key=lambda url: parse_ts(latest[url]))
            end = parse_ts(latest[slowest])
            log.info("Update window end is the slowest scan's latest record time: %s (%s)", latest[slowest], slowest)
        if end is None:
            log.error("No latest record time available; skipping updates check")
            had_errors = True
            window = None
        else:
            start = parse_ts(args.updates_from) if args.updates_from else end - parse_duration(args.update_window)
            window = (fmt_ts(start), end)
            secs = (end - start).total_seconds()
            log.info("Update window: (%s, %s] (%.0fs, expect ~%d-%d updates)",
                     window[0], fmt_ts(end), secs, secs, 100 * secs)
    else:
        window = None

    # --- choose common snapshot ---
    snapshot = None
    if "acs" in args.checks:
        snaps = {url: result[0] for url, result in p1.items() if not isinstance(result, Exception)}
        missing = [u for u, s in snaps.items() if s is None]
        if missing:
            log.warning("Scans without snapshot for migration %d: %s", mid, examples(missing))
        candidates = [s for s in snaps.values() if s]
        if candidates:
            snapshot = min(candidates, key=parse_ts)
            for _ in range(5):  # converge on a snapshot that exists on all scans
                probe = fmt_ts(parse_ts(snapshot) + timedelta(microseconds=1))
                got = run_per_scan(clients, lambda c: fetch_latest_snapshot(c, mid, probe))
                found = [g for g in got.values() if isinstance(g, str)]
                if found and all(g == snapshot for g in found):
                    break
                snapshot = min(found, key=parse_ts) if found else None
                if snapshot is None:
                    break
        if snapshot:
            log.info("Using common ACS snapshot %s", snapshot)
        else:
            log.error("No common ACS snapshot found; skipping acs check")
            had_errors = True

    # --- choose the cutoff for vote results ---
    votes_cutoff = (parse_ts(args.votes_before) if args.votes_before
                    else datetime.now(timezone.utc) - timedelta(minutes=10))
    if "votes" in args.checks:
        log.info("Comparing vote results completed before %s", fmt_ts(votes_cutoff))

    # --- phase 2: heavy checks, sequential per scan ---
    def phase2(c: ScanClient) -> Dict[str, Digest | Votes]:
        res: Dict[str, Digest | Votes] = {}
        if window:
            res["updates"] = fetch_updates(c, args, window[0], window[1])
        if "import_updates" in args.checks:
            res["import_updates"] = fetch_import_updates(c, args)
        if snapshot:
            res["acs"] = fetch_acs(c, args, snapshot)
        if "votes" in args.checks:
            votes = fetch_votes(c, args, votes_cutoff)
            res["votes_v0"] = votes.v0
            if votes.v1:
                res["votes_v1"] = votes.v1
            res["votes"] = votes
        return res

    p2 = run_per_scan(clients, phase2)
    for check in ["updates", "import_updates", "acs", "votes_v0", "votes_v1"]:
        per_scan: Dict[str, Digest] = {}
        for u, r in p2.items():
            if isinstance(r, Exception):
                per_scan[u] = Digest(0, error=str(r))
            elif check in r:
                d = r[check]
                assert isinstance(d, Digest)
                per_scan[u] = d
        if per_scan:
            ok &= compare_digests(check, UNITS[check], per_scan, args.max_tracked_ids)
            had_errors |= any(d.error for d in per_scan.values())
            fetched = [digest for digest in per_scan.values() if not digest.error]
            if check == "updates" and fetched and all(digest.count == 0 for digest in fetched):
                log.error("No scan returned updates of migration %d in the update window; "
                          "for a past migration pass --updates-from and --updates-to", mid)
                ok = False
                had_errors = True
    if "votes" in args.checks:
        all_votes = {u: r["votes"] for u, r in p2.items() if not isinstance(r, Exception)}
        ok &= compare_vote_versions({u: v for u, v in all_votes.items() if isinstance(v, Votes)})

    log.info("=" * 100)
    if ok:
        log.info("RESULT: all checks consistent")
        return 0
    log.error("RESULT: inconsistencies found%s", " (some scans failed)" if had_errors else "")
    return 2 if had_errors else 1


if __name__ == "__main__":
    sys.exit(main())
