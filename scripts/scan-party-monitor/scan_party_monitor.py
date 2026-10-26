#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Monitors a Scan instance for transactions in which any of a given set of parties
acts as a confirming party, i.e. the party
  - is an acting party of an exercise,
  - is a signatory of a created contract, or
  - is a signatory of the contract that is exercised.

Matching transactions are printed to stdout as JSON lines and, optionally, posted
to a Slack incoming webhook.

Scan's update stream does not include the signatories of exercised contracts, so the
script keeps a map from contract id to signatories for all contracts known to Scan in
a local SQLite database. It is seeded from a Scan ACS snapshot and then kept up to date
from the update stream. Both phases are resumable: stopping the script and restarting it
with the same --db continues where it left off.

Uses only the Python standard library. See README.md next to this file for details.
Run with `-h` for supported options.
"""

import argparse
import json
import logging
import os
import random
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

VERSION = "0.1.0"
USER_AGENT = f"splice-scan-party-monitor/{VERSION}"
SCHEMA_VERSION = "1"
MAX_PAGE_SIZE = 1000
HTTP_TIMEOUT_SECONDS = 120
BACKOFF_INITIAL_SECONDS = 2
BACKOFF_MAX_SECONDS = 60
RATE_LIMIT_PENALTY_SECONDS = 300
SLACK_MAX_REASON_LINES = 20

LOG = logging.getLogger("scan-party-monitor")


class FatalError(Exception):
    pass


class NotFound(Exception):
    pass


def utc_now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


_FRACTION_RE = re.compile(r"\.(\d+)")


def parse_time(s):
    """Parses the ISO-8601 timestamps returned by Scan, on any Python 3 version."""
    s = s.replace("Z", "+00:00")
    s = _FRACTION_RE.sub(lambda m: "." + (m.group(1) + "000000")[:6], s, count=1)
    return datetime.fromisoformat(s)


def lag_seconds(record_time):
    try:
        return int((datetime.now(timezone.utc) - parse_time(record_time)).total_seconds())
    except ValueError:
        return None


class Pacer:
    """Spaces out requests to at most `rate` per second, without bursts.

    Scan's rate limits per endpoint are shared by all of its clients, so we pace
    proactively rather than relying on HTTP 429s.
    """

    def __init__(self, name, rate):
        self.name = name
        self.rate = rate
        self.next_time = time.monotonic()
        self.penalty_until = 0.0

    def acquire(self):
        if self.rate <= 0:
            return
        now = time.monotonic()
        rate = self.rate / 2 if now < self.penalty_until else self.rate
        if self.next_time > now:
            time.sleep(self.next_time - now)
            now = self.next_time
        self.next_time = now + 1.0 / rate

    def penalize(self):
        if time.monotonic() >= self.penalty_until:
            LOG.warning(
                f"Rate limited by scan on {self.name}; halving the request rate "
                f"for {RATE_LIMIT_PENALTY_SECONDS}s"
            )
        self.penalty_until = time.monotonic() + RATE_LIMIT_PENALTY_SECONDS


def backoff_delay(attempt):
    delay = min(BACKOFF_MAX_SECONDS, BACKOFF_INITIAL_SECONDS * (2**attempt))
    return delay * random.uniform(0.5, 1.0)


def http_request(method, url, body=None, headers=None):
    """Returns (status, response headers, response body); raises only on connection errors."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("User-Agent", USER_AGENT)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        request.add_header(k, v)
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


class ScanClient:
    def __init__(self, url, acs_rate, updates_rate):
        self.url = url.rstrip("/")
        self.acs_pacer = Pacer("/v2/state/acs", acs_rate)
        self.updates_pacer = Pacer("/v2/updates", updates_rate)
        self.other_pacer = Pacer("other scan endpoints", 1)

    def _call(self, pacer, method, path, body=None, query=None):
        url = f"{self.url}/api/scan{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)
        attempt = 0
        while True:
            pacer.acquire()
            try:
                status, _, content = http_request(method, url, body)
            except (OSError, urllib.error.URLError) as e:
                status, content = None, str(e).encode()
            if status == 200:
                return json.loads(content)
            if status == 404:
                raise NotFound(content.decode(errors="replace"))
            if status is not None and 400 <= status < 500 and status != 429:
                raise FatalError(
                    f"{method} {path} failed with HTTP {status}: {content.decode(errors='replace')}"
                )
            if status == 429:
                pacer.penalize()
            delay = backoff_delay(attempt)
            LOG.warning(
                f"{method} {path} failed ({status or 'connection error'}: "
                f"{content.decode(errors='replace')[:200]}); retrying in {delay:.0f}s"
            )
            time.sleep(delay)
            attempt += 1

    def get_migration_id(self):
        return self._call(self.other_pacer, "GET", "/v0/migrations/last")["migration_id"]

    def get_snapshot_before(self, before, migration_id):
        return self._call(
            self.other_pacer,
            "GET",
            "/v0/state/acs/snapshot-timestamp",
            query={"before": before, "migration_id": migration_id},
        )["record_time"]

    def get_acs_page(self, migration_id, record_time, page_size, after):
        body = {"migration_id": migration_id, "record_time": record_time, "page_size": page_size}
        if after is not None:
            body["after"] = after
        return self._call(self.acs_pacer, "POST", "/v2/state/acs", body)

    def get_updates(self, after_migration_id, after_record_time, page_size):
        body = {
            "after": {
                "after_migration_id": after_migration_id,
                "after_record_time": after_record_time,
            },
            "page_size": page_size,
        }
        return self._call(self.updates_pacer, "POST", "/v2/updates", body)["transactions"]


class Store:
    """SQLite store for the contract map and progress.

    The caller controls transactions with begin()/commit(), so that the contract map and
    the progress markers are always updated atomically.
    """

    def __init__(self, path):
        self.conn = sqlite3.connect(path, isolation_level=None)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        self.conn.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS parties (id INTEGER PRIMARY KEY, party TEXT UNIQUE NOT NULL)"
        )
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS contracts "
            "(cid TEXT PRIMARY KEY, signatories TEXT NOT NULL) WITHOUT ROWID"
        )
        version = self.get_meta("schema_version")
        if version is None:
            self.set_meta("schema_version", SCHEMA_VERSION)
        elif version != SCHEMA_VERSION:
            raise FatalError(f"Database schema version {version} is not supported, expected {SCHEMA_VERSION}")
        self.party_ids = {p: i for i, p in self.conn.execute("SELECT id, party FROM parties")}

    def begin(self):
        self.conn.execute("BEGIN")

    def commit(self):
        self.conn.execute("COMMIT")

    def get_meta(self, key):
        row = self.conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    def set_meta(self, key, value):
        self.conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
            (key, None if value is None else str(value)),
        )

    def party_id(self, party):
        pid = self.party_ids.get(party)
        if pid is None:
            pid = self.conn.execute("INSERT INTO parties (party) VALUES (?)", (party,)).lastrowid
            self.party_ids[party] = pid
        return pid

    def _encode(self, signatories):
        return " ".join(str(self.party_id(p)) for p in signatories)

    def put_contracts(self, contracts):
        """contracts: iterable of (contract id, list of signatory party ids)."""
        self.conn.executemany(
            "INSERT OR REPLACE INTO contracts (cid, signatories) VALUES (?, ?)",
            [(cid, self._encode(sigs)) for cid, sigs in contracts],
        )

    def signatory_ids(self, cid):
        row = self.conn.execute("SELECT signatories FROM contracts WHERE cid = ?", (cid,)).fetchone()
        return None if row is None else {int(i) for i in row[0].split()}

    def delete_contract(self, cid):
        self.conn.execute("DELETE FROM contracts WHERE cid = ?", (cid,))


class SlackNotifier:
    def __init__(self, webhook_url):
        self.webhook_url = webhook_url

    def notify(self, match):
        text = format_slack_message(match)
        attempt = 0
        while True:
            try:
                status, headers, content = http_request(
                    "POST", self.webhook_url, {"text": text}
                )
            except (OSError, urllib.error.URLError) as e:
                status, headers, content = None, {}, str(e).encode()
            if status == 200:
                return
            if status is not None and 400 <= status < 500 and status != 429:
                raise FatalError(
                    f"Slack webhook rejected the message with HTTP {status}: "
                    f"{content.decode(errors='replace')}"
                )
            delay = backoff_delay(attempt)
            if status == 429 and headers.get("Retry-After", "").isdigit():
                delay = max(delay, int(headers["Retry-After"]))
            LOG.warning(
                f"Posting to Slack failed ({status or 'connection error'}); retrying in {delay:.0f}s"
            )
            time.sleep(delay)
            attempt += 1


def format_slack_message(match):
    lines = [
        f":rotating_light: Watched part{'y' if len(match['parties']) == 1 else 'ies'} "
        f"confirmed a transaction on {match['scan_url']}",
        f"update `{match['update_id']}` at record time `{match['record_time']}` "
        f"(migration {match['migration_id']})",
    ]
    reason_lines = []
    for party, reasons in match["parties"].items():
        for r in reasons:
            what = r["template_id"].split(":", 1)[-1]
            if "choice" in r:
                what += f" / {r['choice']}"
            reason_lines.append(f"• `{party}`: {r['reason']} on {what}")
    lines += reason_lines[:SLACK_MAX_REASON_LINES]
    if len(reason_lines) > SLACK_MAX_REASON_LINES:
        lines.append(f"… and {len(reason_lines) - SLACK_MAX_REASON_LINES} more")
    return "\n".join(lines)


class Monitor:
    def __init__(self, args, scan, store, slack, watched):
        self.args = args
        self.scan = scan
        self.store = store
        self.slack = slack
        self.watched = watched
        self.watched_ids = {store.party_id(p): p for p in watched}
        self.stats = {"transactions": 0, "matches": 0, "unknown_contracts": 0}

    # ---- ACS seed --------------------------------------------------------------

    def seed(self):
        store = self.store
        if store.get_meta("acs_complete") == "1":
            return
        migration_id = store.get_meta("snapshot_migration_id")
        record_time = store.get_meta("snapshot_record_time")
        if migration_id is None:
            migration_id = self.args.migration_id
            if migration_id is None:
                migration_id = self.scan.get_migration_id()
            try:
                record_time = self.scan.get_snapshot_before(utc_now_iso(), migration_id)
            except NotFound:
                raise FatalError(
                    f"Scan has no ACS snapshot for migration id {migration_id} yet. Scan takes snapshots "
                    "every few hours; retry later, or pass an older --migration-id."
                )
            store.begin()
            store.set_meta("snapshot_migration_id", migration_id)
            store.set_meta("snapshot_record_time", record_time)
            store.set_meta("acs_pages", 0)
            store.set_meta("acs_contracts", 0)
            store.commit()
            LOG.info(f"Seeding contracts from the ACS snapshot at {record_time} (migration id {migration_id})")
        else:
            migration_id = int(migration_id)
            LOG.info(
                f"Resuming the seed from the ACS snapshot at {record_time} (migration id {migration_id}) "
                f"after {store.get_meta('acs_pages')} pages and {store.get_meta('acs_contracts')} contracts"
            )

        token = store.get_meta("acs_next_page_token")
        pages = int(store.get_meta("acs_pages"))
        contracts = int(store.get_meta("acs_contracts"))
        pages_this_run = 0
        contracts_this_run = 0
        started = time.monotonic()
        last_report = 0.0
        while True:
            try:
                page = self.scan.get_acs_page(migration_id, record_time, self.args.acs_page_size, token)
            except NotFound:
                raise FatalError(
                    f"The ACS snapshot at {record_time} (migration id {migration_id}) is no longer "
                    f"available on {self.scan.url}. Delete {self.args.db} and start over."
                )
            events = page["created_events"]
            token = page.get("next_page_token")
            pages += 1
            contracts += len(events)
            pages_this_run += 1
            contracts_this_run += len(events)
            store.begin()
            store.put_contracts((e["contract_id"], e["signatories"]) for e in events)
            store.set_meta("acs_next_page_token", token)
            store.set_meta("acs_pages", pages)
            store.set_meta("acs_contracts", contracts)
            if token is None:
                store.set_meta("acs_complete", 1)
                store.set_meta("cursor_migration_id", migration_id)
                store.set_meta("cursor_record_time", record_time)
            store.commit()

            now = time.monotonic()
            if token is None or now - last_report >= self.args.progress_interval_seconds:
                last_report = now
                rate = contracts_this_run / max(now - started, 1e-6)
                created_at = events[-1]["created_at"] if events else "-"
                LOG.info(
                    f"ACS seed: {pages} pages, {contracts} contracts stored "
                    f"({rate:.0f} contracts/s), reached contracts created at {created_at}"
                )
            if token is None:
                LOG.info(f"ACS seed complete: {contracts} contracts")
                return
            if self.args.stop_after_acs_pages is not None and pages_this_run >= self.args.stop_after_acs_pages:
                LOG.info(f"Stopping after {pages_this_run} ACS pages as requested; rerun to resume")
                raise StopRequested()

    # ---- update stream -----------------------------------------------------------

    def stream(self):
        store = self.store
        migration_id = int(store.get_meta("cursor_migration_id"))
        record_time = store.get_meta("cursor_record_time")
        LOG.info(
            f"Streaming updates after {record_time} (migration id {migration_id}), watching "
            f"{len(self.watched)} part{'y' if len(self.watched) == 1 else 'ies'}"
        )
        last_heartbeat = time.monotonic()
        while True:
            updates = self.scan.get_updates(migration_id, record_time, self.args.updates_page_size)
            if updates:
                store.begin()
                matches = [m for m in (self.process_update(u) for u in updates) if m]
                for m in matches:
                    self.report(m)
                last = updates[-1]
                # Reassignments carry the migration id in their event.
                migration_id = last.get("migration_id", last.get("event", {}).get("migration_id"))
                record_time = last["record_time"]
                store.set_meta("cursor_migration_id", migration_id)
                store.set_meta("cursor_record_time", record_time)
                store.commit()
                self.stats["transactions"] += len(updates)
                self.stats["matches"] += len(matches)

            now = time.monotonic()
            if now - last_heartbeat >= self.args.heartbeat_interval_seconds:
                last_heartbeat = now
                self.heartbeat(migration_id, record_time)
            if len(updates) < self.args.updates_page_size:
                if self.args.exit_when_caught_up:
                    self.heartbeat(migration_id, record_time)
                    LOG.info("Caught up with scan; exiting as requested")
                    return
                time.sleep(self.args.poll_interval_seconds)

    def heartbeat(self, migration_id, record_time):
        lag = lag_seconds(record_time)
        LOG.info(
            f"At record time {record_time} (migration id {migration_id}, "
            f"{'unknown' if lag is None else f'{lag}s'} behind); this run: "
            f"{self.stats['transactions']} updates, {self.stats['matches']} matching, "
            f"{self.stats['unknown_contracts']} exercises on unknown contracts"
        )

    def process_update(self, update):
        if "events_by_id" not in update:
            # A reassignment; no matching, but keep the contract map complete.
            created = update["event"].get("created_event")
            if created is not None:
                self.store.put_contracts([(created["contract_id"], created["signatories"])])
            return None

        events = update["events_by_id"]
        parties = {}

        def add(pid, reason, event):
            r = {"reason": reason, "event_id": event["event_id"], "template_id": event["template_id"]}
            if "choice" in event:
                r["choice"] = event["choice"]
            parties.setdefault(self.watched_ids[pid], []).append(r)

        # Pre-order traversal is execution order, so contracts created earlier in this
        # transaction are in the map before they can be exercised.
        stack = list(reversed(update["root_event_ids"]))
        while stack:
            event = events.get(stack.pop())
            if event is None:
                continue
            if event["event_type"] == "created_event":
                for p in event["signatories"]:
                    pid = self.store.party_ids.get(p)
                    if pid in self.watched_ids:
                        add(pid, "created_signatory", event)
                self.store.put_contracts([(event["contract_id"], event["signatories"])])
            else:
                for p in event["acting_parties"]:
                    pid = self.store.party_ids.get(p)
                    if pid in self.watched_ids:
                        add(pid, "acting", event)
                signatories = self.store.signatory_ids(event["contract_id"])
                if signatories is None:
                    self.stats["unknown_contracts"] += 1
                    LOG.debug(f"Exercise {event['event_id']} on unknown contract {event['contract_id']}")
                else:
                    for pid in signatories & self.watched_ids.keys():
                        add(pid, "exercised_contract_signatory", event)
                if event["consuming"]:
                    self.store.delete_contract(event["contract_id"])
                stack.extend(reversed(event["child_event_ids"]))

        if not parties:
            return None
        return {
            "update_id": update["update_id"],
            "migration_id": update["migration_id"],
            "record_time": update["record_time"],
            "scan_url": self.scan.url,
            "parties": parties,
        }

    def report(self, match):
        print(json.dumps(match), flush=True)
        if self.slack is not None:
            self.slack.notify(match)


class StopRequested(Exception):
    pass


def read_parties(args):
    parties = list(args.party or [])
    if args.parties_file:
        with open(args.parties_file) as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if line:
                    parties.append(line)
    parties = list(dict.fromkeys(parties))
    if not parties:
        raise FatalError("No parties to watch; pass --party or --parties-file")
    for p in parties:
        if "::" not in p:
            LOG.warning(f"'{p}' does not look like a full party id (expected <hint>::<fingerprint>)")
    return parties


def page_size(value):
    n = int(value)
    if not 1 <= n <= MAX_PAGE_SIZE:
        raise argparse.ArgumentTypeError(f"must be between 1 and {MAX_PAGE_SIZE}")
    return n


def parse_cli_args(argv):
    parser = argparse.ArgumentParser(
        description="Alert on Scan transactions in which watched parties are confirming parties"
    )
    parser.add_argument("--scan-url", required=True, help="Base URL of the Scan instance, e.g. https://scan.sv-1.example.com")
    parser.add_argument("--db", required=True, help="SQLite database file for the contract map and progress; created if missing")
    parser.add_argument("--party", action="append", help="Party id to watch; may be repeated")
    parser.add_argument("--parties-file", help="File with one party id per line to watch ('#' starts a comment)")
    parser.add_argument(
        "--slack-webhook-url",
        default=os.environ.get("SLACK_WEBHOOK_URL"),
        help="Slack incoming webhook URL to post alerts to (default: $SLACK_WEBHOOK_URL)",
    )
    parser.add_argument("--migration-id", type=int, help="Migration id of the ACS snapshot to seed from (default: the latest)")
    parser.add_argument("--acs-page-size", type=page_size, default=MAX_PAGE_SIZE, help="Contracts per ACS request (default: %(default)s)")
    parser.add_argument(
        "--acs-requests-per-second",
        type=float,
        default=1.0,
        help="Pace of ACS requests while seeding. Scan's ACS rate limit is shared by all of its clients; "
        "please don't raise this (default: %(default)s)",
    )
    parser.add_argument("--updates-page-size", type=page_size, default=MAX_PAGE_SIZE, help="Updates per request (default: %(default)s)")
    parser.add_argument(
        "--updates-requests-per-second",
        type=float,
        default=2.0,
        help="Pace of update requests while catching up; shared rate limit as above (default: %(default)s)",
    )
    parser.add_argument("--poll-interval-seconds", type=float, default=10, help="Wait between polls once caught up (default: %(default)s)")
    parser.add_argument("--progress-interval-seconds", type=float, default=10, help="Interval between ACS seed progress reports (default: %(default)s)")
    parser.add_argument("--heartbeat-interval-seconds", type=float, default=300, help="Interval between status logs while streaming (default: %(default)s)")
    parser.add_argument("--exit-when-caught-up", action="store_true", help="Exit once all updates currently in Scan have been processed")
    parser.add_argument("--stop-after-acs-pages", type=int, help="Stop after seeding this many ACS pages in this run (for testing resumption)")
    parser.add_argument("--loglevel", default="INFO", help="Log level (default: %(default)s)")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_cli_args(argv)
    logging.basicConfig(
        stream=sys.stderr,
        level=args.loglevel.upper(),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        parties = read_parties(args)
        store = Store(args.db)
        stored_url = store.get_meta("scan_url")
        scan_url = args.scan_url.rstrip("/")
        if stored_url is None:
            store.set_meta("scan_url", scan_url)
        elif stored_url != scan_url:
            LOG.warning(f"{args.db} was created against {stored_url}; continuing with {scan_url}")
        scan = ScanClient(scan_url, args.acs_requests_per_second, args.updates_requests_per_second)
        slack = SlackNotifier(args.slack_webhook_url) if args.slack_webhook_url else None
        monitor = Monitor(args, scan, store, slack, parties)
        monitor.seed()
        monitor.stream()
    except StopRequested:
        pass
    except FatalError as e:
        LOG.error(str(e))
        return 1
    except KeyboardInterrupt:
        LOG.info("Interrupted; progress up to the last completed page is saved")
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
