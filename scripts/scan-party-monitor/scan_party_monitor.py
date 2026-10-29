#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Monitors a Scan instance for activity of a configured set of tracked parties, and alerts on
  - transfers of Amulet from a tracked party to a party that is not one of its expected
    recipients, and
  - for tracked parties with `alert_confirmations` set, every transaction in which the party
    is a confirming party, i.e. the party
      - is an acting party of an exercise,
      - is a signatory of a created contract, or
      - is a signatory of the contract that is exercised.

Matching transactions are printed to stdout as JSON lines and, optionally, posted to a Slack
incoming webhook.

Scan's update stream does not include the signatories of exercised contracts, so the script
keeps a map from contract id to signatories for the contracts signed by tracked parties in a
local SQLite database. It is seeded from a Scan ACS snapshot and then kept up to date from the
update stream. Both phases are resumable: stopping the script and restarting it with the same
--db continues where it left off. Adding parties to the configuration re-seeds the map.

Uses only the Python standard library. See README.md next to this file for details.
Run with `-h` for supported options.
"""

from __future__ import annotations

import argparse
import contextlib
import http.client
import itertools
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
from dataclasses import dataclass
from enum import Enum
from datetime import datetime, timezone
from typing import Any, Callable, Dict, FrozenSet, Iterable, List, NewType, Optional, Set, Tuple

VERSION = "0.2.0"
USER_AGENT = f"splice-scan-party-monitor/{VERSION}"
SCHEMA_VERSION = "1"
MAX_PAGE_SIZE = 1000
MAX_TRACKED_PARTIES = 1000
# Hex characters of a namespace fingerprint: a multihash prefix (1220) and a SHA-256 digest
MIN_FINGERPRINT_LENGTH = 68
HTTP_TIMEOUT_SECONDS = 120
BACKOFF_INITIAL_SECONDS = 2
BACKOFF_MAX_SECONDS = 60
RATE_LIMIT_PENALTY_SECONDS = 300
SLACK_MAX_LINES = 20
SLACK_MAX_REASONS_PER_UPDATE = 3
# Slack truncates messages at 40000 characters.
SLACK_MAX_CHARS = 35000
AMULET = "Splice.Amulet:Amulet"
LOCKED_AMULET = "Splice.Amulet:LockedAmulet"

LOG = logging.getLogger("scan-party-monitor")

# A JSON object as returned by Scan, or one of our match records.
Json = Dict[str, Any]
# (migration id, record time): the order of updates in Scan.
Position = Tuple[int, datetime]
# A party id from the configuration. Only _check_party_id creates one, so it has passed that
# check; party ids from Scan are plain strs.
PartyId = NewType("PartyId", str)


class Meta(Enum):
    """Keys of the store's meta table."""

    SCHEMA_VERSION = "schema_version"
    SCAN_URL = "scan_url"
    # JSON list of the parties the contract map is built for
    TRACKED_PARTIES = "tracked_parties"
    # JSON object: party -> [migration id, record time] up to which its matches were reported
    REPORTED_UNTIL = "reported_until"
    # The ACS snapshot to seed from, and the progress of the seed
    SNAPSHOT_MIGRATION_ID = "snapshot_migration_id"
    SNAPSHOT_RECORD_TIME = "snapshot_record_time"
    ACS_NEXT_PAGE_TOKEN = "acs_next_page_token"
    ACS_PAGES = "acs_pages"
    ACS_CONTRACTS = "acs_contracts"
    ACS_COMPLETE = "acs_complete"
    # The position in the update stream
    CURSOR_MIGRATION_ID = "cursor_migration_id"
    CURSOR_RECORD_TIME = "cursor_record_time"


class FatalError(Exception):
    pass


class NotFound(Exception):
    pass


class StopRequested(Exception):
    pass


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


_FRACTION_RE = re.compile(r"\.(\d+)")


def parse_time(s: str) -> datetime:
    """Parses the ISO-8601 timestamps returned by Scan, on any Python 3 version."""
    return datetime.fromisoformat(
        _FRACTION_RE.sub(lambda m: "." + (m.group(1) + "000000")[:6], s.replace("Z", "+00:00"), count=1)
    )


def position(migration_id: int, record_time: str) -> Position:
    return (migration_id, parse_time(record_time))


def lag_seconds(record_time: str) -> Optional[int]:
    try:
        return int((datetime.now(timezone.utc) - parse_time(record_time)).total_seconds())
    except ValueError:
        return None


def qualified_name(template_id: str) -> str:
    """`<package id>:<module>:<entity>` -> `<module>:<entity>`"""
    return template_id.split(":", 1)[-1]


def _holding_arguments(created: Json) -> Optional[Json]:
    """The Amulet arguments of a created Amulet or LockedAmulet, None for other templates."""
    name = qualified_name(created["template_id"])
    arguments: Json = created.get("create_arguments") or {}
    if name == AMULET:
        return arguments
    return arguments.get("amulet") or {} if name == LOCKED_AMULET else None


def holding_owner(created: Json) -> Optional[str]:
    """The owner of a created Amulet or LockedAmulet, None for other templates."""
    owner = (_holding_arguments(created) or {}).get("owner")
    return owner if isinstance(owner, str) else None


def holding_amount(created: Json) -> Optional[str]:
    amount = ((_holding_arguments(created) or {}).get("amount") or {}).get("initialAmount")
    return amount if isinstance(amount, str) else None


### configuration


@dataclass(frozen=True)
class PartyConfig:
    # Includes the common expected recipients, the party's additional ones, and the party itself.
    expected_recipients: FrozenSet[PartyId]
    alert_confirmations: bool


def _check_party_id(party: object, where: str) -> PartyId:
    if not isinstance(party, str) or not re.fullmatch(
        rf"\S.*::[0-9a-f]{{{MIN_FINGERPRINT_LENGTH},}}", party
    ):
        raise FatalError(
            f"{where}: {party!r} is not a party id (expected <hint>::<fingerprint>, with a "
            f"fingerprint of at least {MIN_FINGERPRINT_LENGTH} lowercase hex digits)"
        )
    return PartyId(party)


def _party_list(value: object, where: str) -> List[PartyId]:
    if not isinstance(value, list):
        raise FatalError(f"{where} must be a list of party ids")
    return [_check_party_id(p, where) for p in value]


def _check_keys(obj: Json, allowed: Set[str], where: str) -> None:
    unknown = set(obj) - allowed
    if unknown:
        raise FatalError(f"{where}: unknown keys {sorted(unknown)}; expected some of {sorted(allowed)}")


def _party_config(party: PartyId, settings: object, common: List[PartyId]) -> PartyConfig:
    where = f"parties.{party}"
    if not isinstance(settings, dict):
        raise FatalError(f"{where} must be an object")
    _check_keys(settings, {"additional_recipients", "alert_confirmations"}, where)
    additional = _party_list(settings.get("additional_recipients", []), f"{where}.additional_recipients")
    alert_confirmations = settings.get("alert_confirmations", False)
    if not isinstance(alert_confirmations, bool):
        raise FatalError(f"{where}.alert_confirmations must be true or false")
    return PartyConfig(
        expected_recipients=frozenset(common) | frozenset(additional) | {party},
        alert_confirmations=alert_confirmations,
    )


def parse_config(raw: object) -> Dict[PartyId, PartyConfig]:
    if not isinstance(raw, dict):
        raise FatalError("The configuration must be a JSON object")
    _check_keys(raw, {"expected_recipients", "parties"}, "configuration")
    common = _party_list(raw.get("expected_recipients", []), "expected_recipients")
    parties = raw.get("parties")
    if not isinstance(parties, dict) or not parties:
        raise FatalError("The configuration must list the tracked parties in a non-empty 'parties' object")
    if len(parties) > MAX_TRACKED_PARTIES:
        raise FatalError(f"At most {MAX_TRACKED_PARTIES} parties can be tracked, got {len(parties)}")
    checked = {_check_party_id(party, "parties"): settings for party, settings in parties.items()}
    return {party: _party_config(party, settings, common) for party, settings in checked.items()}


def load_config(path: str) -> Dict[PartyId, PartyConfig]:
    try:
        with open(path) as f:
            raw = json.load(f)
    except (OSError, ValueError) as e:
        raise FatalError(f"Cannot read the configuration {path}: {e}")
    return parse_config(raw)


### Scan client


class Pacer:
    """Spaces out requests to at most `rate` per second, without bursts.

    Scan's rate limits per endpoint are shared by all of its clients, so we pace
    proactively rather than relying on HTTP 429s.
    """

    def __init__(self, name: str, rate: float) -> None:
        self.name = name
        self.rate = rate
        self.next_time = time.monotonic()
        self.penalty_until = 0.0

    def acquire(self) -> None:
        if self.rate <= 0:
            return
        now = time.monotonic()
        rate = self.rate / 2 if now < self.penalty_until else self.rate
        if self.next_time > now:
            time.sleep(self.next_time - now)
            now = self.next_time
        self.next_time = now + 1.0 / rate

    def penalize(self) -> None:
        if time.monotonic() >= self.penalty_until:
            LOG.warning(
                f"Rate limited by scan on {self.name}; halving the request rate "
                f"for {RATE_LIMIT_PENALTY_SECONDS}s"
            )
        self.penalty_until = time.monotonic() + RATE_LIMIT_PENALTY_SECONDS


def backoff_delay(attempt: int) -> float:
    # Cap the exponent, so that a long outage doesn't overflow the float.
    delay = min(BACKOFF_MAX_SECONDS, BACKOFF_INITIAL_SECONDS * 2.0 ** min(attempt, 32))
    return delay * random.uniform(0.5, 1.0)


def http_request(
    method: str, url: str, body: Optional[Json] = None, headers: Optional[Dict[str, str]] = None
) -> Tuple[int, Any, bytes]:
    """Returns (status, response headers, response body); raises only OSError, on connection errors."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("User-Agent", USER_AGENT)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        request.add_header(k, v)
    try:
        try:
            with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.headers, e.read()
    except http.client.HTTPException as e:
        # e.g. IncompleteRead, when the connection closes before the whole body was read
        raise ConnectionError(repr(e)) from e


class ScanClient:
    def __init__(self, url: str, acs_rate: float, updates_rate: float) -> None:
        self.url = url.rstrip("/")
        self.acs_pacer = Pacer("/v2/state/acs", acs_rate)
        self.updates_pacer = Pacer("/v2/updates", updates_rate)
        self.other_pacer = Pacer("other scan endpoints", 1)

    def _call(
        self,
        pacer: Pacer,
        method: str,
        path: str,
        body: Optional[Json] = None,
        query: Optional[Dict[str, Any]] = None,
        on_retry: Callable[[], None] = lambda: None,
    ) -> Json:
        """Retries until Scan answers; calls `on_retry` after each backoff, before retrying."""
        url = f"{self.url}/api/scan{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)
        for attempt in itertools.count():
            pacer.acquire()
            status: Optional[int]
            try:
                status, _, content = http_request(method, url, body)
            except (OSError, urllib.error.URLError) as e:
                status, content = None, str(e).encode()
            if status == 200:
                result: Json = json.loads(content)
                return result
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
            on_retry()
        raise AssertionError("unreachable")

    def get_migration_id(self) -> int:
        return int(self._call(self.other_pacer, "GET", "/v0/migrations/last")["migration_id"])

    def get_snapshot_before(self, before: str, migration_id: int) -> str:
        return str(
            self._call(
                self.other_pacer,
                "GET",
                "/v0/state/acs/snapshot-timestamp",
                query={"before": before, "migration_id": migration_id},
            )["record_time"]
        )

    def get_acs_page(
        self,
        migration_id: int,
        record_time: str,
        party_ids: List[PartyId],
        page_size: int,
        after: Optional[str],
    ) -> Json:
        body: Json = {
            "migration_id": migration_id,
            "record_time": record_time,
            "party_ids": party_ids,
            "page_size": page_size,
        }
        if after is not None:
            body["after"] = after
        return self._call(self.acs_pacer, "POST", "/v2/state/acs", body)

    def get_updates(
        self,
        after_migration_id: int,
        after_record_time: str,
        page_size: int,
        on_retry: Callable[[], None] = lambda: None,
    ) -> List[Json]:
        body: Json = {
            "after": {
                "after_migration_id": after_migration_id,
                "after_record_time": after_record_time,
            },
            "page_size": page_size,
        }
        transactions: List[Json] = self._call(
            self.updates_pacer, "POST", "/v2/updates", body, on_retry=on_retry
        )["transactions"]
        return transactions


### local store


@dataclass(frozen=True)
class StoredContract:
    signatory_ids: Set[int]
    holding_owner_id: Optional[int]


class Store:
    """SQLite store for the contract map and progress.

    The caller controls transactions with begin()/commit(), so that the contract map and
    the progress markers are always updated atomically.
    """

    def __init__(self, path: str) -> None:
        self.conn = sqlite3.connect(path, isolation_level=None)
        self.conn.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS parties (id INTEGER PRIMARY KEY, party TEXT UNIQUE NOT NULL)"
        )
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS contracts "
            "(cid TEXT PRIMARY KEY, signatories TEXT NOT NULL, holding_owner INTEGER) WITHOUT ROWID"
        )
        version = self.get_meta(Meta.SCHEMA_VERSION)
        if version is None:
            self.set_meta(Meta.SCHEMA_VERSION, SCHEMA_VERSION)
        elif version != SCHEMA_VERSION:
            self.conn.close()
            raise FatalError(f"Database schema version {version} is not supported, expected {SCHEMA_VERSION}")
        self.party_ids: Dict[str, int] = {p: i for i, p in self.conn.execute("SELECT id, party FROM parties")}

    def close(self) -> None:
        self.conn.close()

    def begin(self) -> None:
        self.conn.execute("BEGIN")

    def commit(self) -> None:
        self.conn.execute("COMMIT")

    def rollback(self) -> None:
        if self.conn.in_transaction:
            self.conn.execute("ROLLBACK")

    def get_meta(self, key: Meta) -> Optional[str]:
        row = self.conn.execute("SELECT value FROM meta WHERE key = ?", (key.value,)).fetchone()
        return row[0] if row else None

    def require_meta(self, key: Meta) -> str:
        value = self.get_meta(key)
        if value is None:
            raise FatalError(f"The database lacks {key.value}; delete it and start over")
        return value

    def set_meta(self, key: Meta, value: object) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
            (key.value, None if value is None else str(value)),
        )

    def party_id(self, party: str) -> int:
        pid = self.party_ids.get(party)
        if pid is None:
            cursor = self.conn.execute("INSERT INTO parties (party) VALUES (?)", (party,))
            assert cursor.lastrowid is not None
            pid = cursor.lastrowid
            self.party_ids[party] = pid
        return pid

    def put_contract(self, cid: str, signatories: Iterable[str], owner: Optional[str]) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO contracts (cid, signatories, holding_owner) VALUES (?, ?, ?)",
            (
                cid,
                " ".join(str(self.party_id(p)) for p in signatories),
                None if owner is None else self.party_id(owner),
            ),
        )

    def lookup(self, cid: str) -> Optional[StoredContract]:
        row = self.conn.execute(
            "SELECT signatories, holding_owner FROM contracts WHERE cid = ?", (cid,)
        ).fetchone()
        if row is None:
            return None
        return StoredContract({int(i) for i in row[0].split()}, row[1])

    def delete_contract(self, cid: str) -> None:
        self.conn.execute("DELETE FROM contracts WHERE cid = ?", (cid,))

    def clear_contracts(self) -> None:
        self.conn.execute("DELETE FROM contracts")


### Slack


class SlackNotifier:
    def __init__(self, webhook_url: str) -> None:
        self.webhook_url = webhook_url

    def post(self, text: str) -> None:
        for attempt in itertools.count():
            status: Optional[int]
            headers: Any
            try:
                status, headers, content = http_request("POST", self.webhook_url, {"text": text})
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


def format_reason(reason: Json) -> str:
    if reason["reason"] == "transfer":
        amount = reason.get("amount") or "an unknown amount"
        return f"transfer of {amount} to `{reason['receiver']}` (not an expected recipient)"
    choice = f" / {reason['choice']}" if "choice" in reason else ""
    return f"{reason['reason']} on {qualified_name(reason['template_id'])}{choice}"


def _plural(n: int, singular: str, plural: str) -> str:
    return singular if n == 1 else plural


def _join_capped(header: List[str], lines: List[str], omitted_what: str) -> str:
    """header + as many lines as fit in Slack's limits, then a count of the omitted ones."""
    candidates = lines[:SLACK_MAX_LINES]
    header_length = sum(len(h) + 1 for h in header)
    lengths = itertools.accumulate(len(line) + 1 for line in candidates)
    kept = [line for line, length in zip(candidates, lengths) if header_length + length <= SLACK_MAX_CHARS]
    omitted = (
        [f"… and {len(lines) - len(kept)} more {omitted_what} (see the script's stdout)"]
        if len(kept) < len(lines)
        else []
    )
    return "\n".join(header + kept + omitted)


def _update_line(match: Json) -> str:
    """One line of a grouped Slack message."""
    reasons = [f"`{party}`: {format_reason(r)}" for party, rs in match["parties"].items() for r in rs]
    more = len(reasons) - SLACK_MAX_REASONS_PER_UPDATE
    shown = "; ".join(reasons[:SLACK_MAX_REASONS_PER_UPDATE] + ([f"+{more} more"] if more > 0 else []))
    return f"• `{match['update_id']}` at `{match['record_time']}`: {shown}"


def format_slack_message(matches: List[Json]) -> str:
    """One Slack message for all of `matches`, which must not be empty."""
    first = matches[0]
    if len(matches) == 1:
        parties = first["parties"]
        header = [
            f":rotating_light: Alert for tracked {_plural(len(parties), 'party', 'parties')} "
            f"on {first['scan_url']}",
            f"update `{first['update_id']}` at record time `{first['record_time']}` "
            f"(migration {first['migration_id']})",
        ]
        lines = [f"• `{party}`: {format_reason(r)}" for party, rs in parties.items() for r in rs]
        return _join_capped(header, lines, "reasons")

    header = [
        f":rotating_light: {len(matches)} alerts for tracked parties on {first['scan_url']}, "
        f"record times `{first['record_time']}` – `{matches[-1]['record_time']}`"
    ]
    return _join_capped(header, [_update_line(m) for m in matches], "updates")


### monitor


class Monitor:
    def __init__(
        self,
        args: argparse.Namespace,
        scan: ScanClient,
        store: Store,
        slack: Optional[SlackNotifier],
        config: Dict[PartyId, PartyConfig],
    ) -> None:
        self.args = args
        self.scan = scan
        self.store = store
        self.slack = slack
        self.config = config
        self.tracked_by_id = {store.party_id(p): p for p in config}
        self.confirmation_ids = {
            pid for pid, p in self.tracked_by_id.items() if config[p].alert_confirmations
        }
        # Per party, the position up to which its matches were already reported before a re-seed.
        reported_until = json.loads(store.get_meta(Meta.REPORTED_UNTIL) or "{}")
        self.reported_until: Dict[PartyId, Position] = {
            p: position(*reported_until[p]) for p in config if p in reported_until
        }
        self.stats: Dict[str, int] = {"transactions": 0, "matches": 0}

    ### configuration changes

    def prepare(self) -> None:
        """Compares the configured parties with the ones the contract map was built for, and
        schedules a re-seed if parties were added."""
        store = self.store
        configured = sorted(self.config)
        stored_json = store.get_meta(Meta.TRACKED_PARTIES)
        stored = set(json.loads(stored_json)) if stored_json is not None else None
        if stored == set(configured):
            return
        seeding = store.get_meta(Meta.ACS_COMPLETE) != "1"
        # While seeding, the parties must not change at all, as the ACS page token is only
        # valid for the same request; afterwards, only added parties need a re-seed.
        if stored is None or (not seeding and set(configured) <= stored):
            store.set_meta(Meta.TRACKED_PARTIES, json.dumps(configured))
            return

        changes = [
            f"{what}: {', '.join(sorted(parties))}"
            for what, parties in [("added", set(configured) - stored), ("removed", stored - set(configured))]
            if parties
        ]
        store.begin()
        if not seeding:
            # Re-seed from the latest snapshot that is not after the cursor, and replay the
            # updates from there without reporting again what was already reported.
            cursor_migration_id = int(store.require_meta(Meta.CURSOR_MIGRATION_ID))
            cursor_record_time = store.require_meta(Meta.CURSOR_RECORD_TIME)
            try:
                snapshot = self.scan.get_snapshot_before(cursor_record_time, cursor_migration_id)
            except NotFound:
                raise FatalError(
                    f"Scan has no ACS snapshot before {cursor_record_time} (migration id "
                    f"{cursor_migration_id}) to re-seed from for the added parties. "
                    f"Delete {self.args.db} and start over."
                )
            cursor = position(cursor_migration_id, cursor_record_time)
            self.reported_until = {
                **self.reported_until,
                **{p: max(self.reported_until.get(p, cursor), cursor) for p in configured if p in stored},
            }
            self._save_reported_until()
            store.set_meta(Meta.SNAPSHOT_MIGRATION_ID, cursor_migration_id)
            store.set_meta(Meta.SNAPSHOT_RECORD_TIME, snapshot)
        # Otherwise a seed is in progress; restart it for the new set of parties, as the ACS
        # page token is only valid for the same request.
        store.clear_contracts()
        store.set_meta(Meta.ACS_COMPLETE, 0)
        store.set_meta(Meta.ACS_NEXT_PAGE_TOKEN, None)
        store.set_meta(Meta.ACS_PAGES, 0)
        store.set_meta(Meta.ACS_CONTRACTS, 0)
        store.set_meta(Meta.TRACKED_PARTIES, json.dumps(configured))
        store.commit()
        LOG.info(
            f"Tracked parties changed ({'; '.join(changes)}); re-seeding the contracts"
        )

    ### ACS seed

    def seed(self) -> None:
        try:
            self._seed()
        except KeyboardInterrupt:
            # Every page is committed on its own, so this is like --stop-after-acs-pages.
            self.store.rollback()
            LOG.info(
                f"Interrupted after {self.store.get_meta(Meta.ACS_PAGES) or 0} ACS pages; "
                "rerun to resume"
            )
            raise StopRequested()

    def _seed(self) -> None:
        store = self.store
        if store.get_meta(Meta.ACS_COMPLETE) == "1":
            return
        stored_migration_id = store.get_meta(Meta.SNAPSHOT_MIGRATION_ID)
        if stored_migration_id is None:
            migration_id: int = (
                self.args.migration_id if self.args.migration_id is not None else self.scan.get_migration_id()
            )
            try:
                record_time = self.scan.get_snapshot_before(
                    self.args.seed_snapshot_before or utc_now_iso(), migration_id
                )
            except NotFound:
                raise FatalError(
                    f"Scan has no ACS snapshot for migration id {migration_id} before "
                    f"{self.args.seed_snapshot_before or 'now'}. Scan takes snapshots every few hours; "
                    "retry later, or pass an older --migration-id or a later --seed-snapshot-before."
                )
            store.begin()
            store.set_meta(Meta.SNAPSHOT_MIGRATION_ID, migration_id)
            store.set_meta(Meta.SNAPSHOT_RECORD_TIME, record_time)
            store.set_meta(Meta.ACS_PAGES, 0)
            store.set_meta(Meta.ACS_CONTRACTS, 0)
            store.commit()
            LOG.info(f"Seeding contracts from the ACS snapshot at {record_time} (migration id {migration_id})")
        else:
            migration_id = int(stored_migration_id)
            record_time = store.require_meta(Meta.SNAPSHOT_RECORD_TIME)
            if store.get_meta(Meta.ACS_NEXT_PAGE_TOKEN) is not None:
                LOG.info(
                    f"Resuming the seed from the ACS snapshot at {record_time} (migration id {migration_id}) "
                    f"after {store.get_meta(Meta.ACS_PAGES)} pages and {store.get_meta(Meta.ACS_CONTRACTS)} contracts"
                )

        party_ids = sorted(self.config)
        token = store.get_meta(Meta.ACS_NEXT_PAGE_TOKEN)
        pages = int(store.require_meta(Meta.ACS_PAGES))
        contracts = int(store.require_meta(Meta.ACS_CONTRACTS))
        pages_this_run = 0
        contracts_this_run = 0
        started = time.monotonic()
        last_report = 0.0
        while True:
            try:
                page = self.scan.get_acs_page(
                    migration_id, record_time, party_ids, self.args.acs_page_size, token
                )
            except NotFound:
                raise FatalError(
                    f"The ACS snapshot at {record_time} (migration id {migration_id}) is no longer "
                    f"available on {self.scan.url}. Delete {self.args.db} and start over."
                )
            events = page["created_events"]
            token = page.get("next_page_token")
            pages += 1
            pages_this_run += 1
            store.begin()
            for e in events:
                if self.store_if_tracked(e):
                    contracts += 1
                    contracts_this_run += 1
            store.set_meta(Meta.ACS_NEXT_PAGE_TOKEN, token)
            store.set_meta(Meta.ACS_PAGES, pages)
            store.set_meta(Meta.ACS_CONTRACTS, contracts)
            if token is None:
                store.set_meta(Meta.ACS_COMPLETE, 1)
                store.set_meta(Meta.CURSOR_MIGRATION_ID, migration_id)
                store.set_meta(Meta.CURSOR_RECORD_TIME, record_time)
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

    def store_if_tracked(self, created: Json) -> bool:
        """Stores a created contract if a tracked party signs it."""
        if not any(self.store.party_ids.get(p) in self.tracked_by_id for p in created["signatories"]):
            return False
        self.store.put_contract(created["contract_id"], created["signatories"], holding_owner(created))
        return True

    ### update stream

    def stream(self) -> None:
        store = self.store
        migration_id = int(store.require_meta(Meta.CURSOR_MIGRATION_ID))
        record_time = store.require_meta(Meta.CURSOR_RECORD_TIME)
        n = len(self.config)
        LOG.info(
            f"Streaming updates after {record_time} (migration id {migration_id}), tracking "
            f"{n} {_plural(n, 'party', 'parties')}"
        )
        last_heartbeat = time.monotonic()
        # Matches not yet posted to Slack. Their updates are only committed to the store,
        # together with the cursor, once they have been posted.
        unposted: List[Json] = []
        oldest_unposted = 0.0

        def deliver(now: bool) -> None:
            """Posts the unposted matches if `now` or they are due, and then commits the updates
            up to them. Called between pages, also while Scan requests are retried."""
            nonlocal unposted
            if (
                self.slack is None
                or not unposted
                or now
                or time.monotonic() - oldest_unposted >= self.args.slack_batch_seconds
            ):
                if unposted and self.slack is not None:
                    self.slack.post(format_slack_message(unposted))
                unposted = []
                store.commit()
                store.begin()

        stop_at = None if self.args.stop_at_record_time is None else parse_time(self.args.stop_at_record_time)
        store.begin()
        while True:
            updates = self.scan.get_updates(
                migration_id, record_time, self.args.updates_page_size, on_retry=lambda: deliver(False)
            )
            caught_up = len(updates) < self.args.updates_page_size
            # Record times overlapping across migrations were only possible before logical
            # synchronizer upgrades and never happened; with LSU they cannot happen.
            in_range = (
                updates
                if stop_at is None
                else list(itertools.takewhile(lambda u: parse_time(u["record_time"]) <= stop_at, updates))
            )
            matches = [m for m in map(self.process_update_event, in_range) if m is not None]
            for m in matches:
                print(json.dumps(m), flush=True)
            if matches and not unposted:
                oldest_unposted = time.monotonic()
            unposted = unposted + matches
            self.stats["matches"] += len(matches)
            if in_range:
                last = in_range[-1]
                # Reassignments carry the migration id in their event.
                migration_id = last.get("migration_id", last.get("event", {}).get("migration_id"))
                record_time = last["record_time"]
                store.set_meta(Meta.CURSOR_MIGRATION_ID, migration_id)
                store.set_meta(Meta.CURSOR_RECORD_TIME, record_time)
                self.stats["transactions"] += len(in_range)
                self.forget_reported_until(position(migration_id, record_time))
            # Scan only returns updates with later record times than the cursor, so none at or
            # before the cutoff can follow once the cursor is at it.
            reached_stop = len(in_range) < len(updates) or (
                stop_at is not None and parse_time(record_time) >= stop_at
            )

            deliver(caught_up or reached_stop)

            now = time.monotonic()
            if now - last_heartbeat >= self.args.heartbeat_interval_seconds:
                last_heartbeat = now
                self.heartbeat(migration_id, record_time)
            if reached_stop:
                store.commit()
                self.heartbeat(migration_id, record_time)
                LOG.info(f"Reached --stop-at-record-time {self.args.stop_at_record_time}; exiting as requested")
                return
            if caught_up:
                if self.args.exit_when_caught_up:
                    store.commit()
                    self.heartbeat(migration_id, record_time)
                    LOG.info("Caught up with scan; exiting as requested")
                    return
                time.sleep(self.args.poll_interval_seconds)

    def forget_reported_until(self, cursor: Position) -> None:
        remaining = {p: until for p, until in self.reported_until.items() if until > cursor}
        if len(remaining) < len(self.reported_until):
            self.reported_until = remaining
            self._save_reported_until()

    def _save_reported_until(self) -> None:
        self.store.set_meta(
            Meta.REPORTED_UNTIL,
            json.dumps(
                {p: [m, t.isoformat()] for p, (m, t) in self.reported_until.items() if p in self.config}
            ),
        )

    def heartbeat(self, migration_id: int, record_time: str) -> None:
        lag = lag_seconds(record_time)
        LOG.info(
            f"At record time {record_time} (migration id {migration_id}, "
            f"{'unknown' if lag is None else f'{lag}s'} behind); this run: "
            f"{self.stats['transactions']} updates, {self.stats['matches']} matching"
        )

    def process_update_event(self, update: Json) -> Optional[Json]:
        if "events_by_id" not in update:
            # A reassignment; no matching, but keep the contract map complete.
            created = update["event"].get("created_event")
            if created is not None:
                self.store_if_tracked(created)
            return None

        # Pre-order traversal is execution order, so contracts created earlier in this
        # transaction are in the map before they can be exercised.
        ordered = preorder(update["events_by_id"], update["root_event_ids"])
        found: List[Tuple[PartyId, Json]] = []
        senders: Set[PartyId] = set()
        for event in ordered:
            if event["event_type"] == "created_event":
                found += [
                    _reason(p, "created_signatory", event)
                    for p in self.alerting_confirmations(event["signatories"])
                ]
                self.store_if_tracked(event)
            else:
                found += [
                    _reason(p, "acting", event)
                    for p in self.alerting_confirmations(event["acting_parties"])
                ]
                stored = self.store.lookup(event["contract_id"])
                if stored is not None:
                    found += [
                        _reason(self.tracked_by_id[pid], "exercised_contract_signatory", event)
                        for pid in stored.signatory_ids & self.confirmation_ids
                    ]
                    if event["consuming"]:
                        if stored.holding_owner_id in self.tracked_by_id:
                            senders.add(self.tracked_by_id[stored.holding_owner_id])
                        self.store.delete_contract(event["contract_id"])

        # A transfer moves holdings of a tracked party to holdings of other parties. With
        # several tracked senders, outputs can't be attributed to one of them, so each sender
        # is checked against all outputs.
        created_holdings = [
            (owner, e)
            for e in ordered
            if e["event_type"] == "created_event"
            for owner in [holding_owner(e)]
            if owner is not None
        ]
        transfers = [
            _reason(sender, "transfer", e, receiver=owner, amount=holding_amount(e))
            for sender in sorted(senders)
            for owner, e in created_holdings
            if owner not in self.config[sender].expected_recipients
        ]

        update_position = position(update["migration_id"], update["record_time"])
        all_found = found + transfers
        unreported = {
            p: [r for q, r in all_found if q == p]
            for p in dict.fromkeys(q for q, _ in all_found)
            if p not in self.reported_until or update_position > self.reported_until[p]
        }
        if not unreported:
            return None
        return {
            "update_id": update["update_id"],
            "migration_id": update["migration_id"],
            "record_time": update["record_time"],
            "scan_url": self.scan.url,
            "parties": unreported,
        }

    def alerting_confirmations(self, parties: Iterable[str]) -> List[PartyId]:
        """Those of `parties` that are tracked with alert_confirmations."""
        ids = (self.store.party_ids.get(p) for p in parties)
        return [self.tracked_by_id[pid] for pid in ids if pid is not None and pid in self.confirmation_ids]


def preorder(events: Json, root_ids: List[str]) -> List[Json]:
    """The events of a transaction in execution order."""
    ordered: List[Json] = []
    stack = list(reversed(root_ids))
    while stack:
        event = events.get(stack.pop())
        if event is not None:
            ordered.append(event)
            stack.extend(reversed(event.get("child_event_ids", [])))
    return ordered


def _reason(party: PartyId, reason: str, event: Json, **details: Any) -> Tuple[PartyId, Json]:
    choice = {"choice": event["choice"]} if "choice" in event else {}
    return party, {
        "reason": reason,
        "event_id": event["event_id"],
        "template_id": event["template_id"],
        **choice,
        **details,
    }


### command line


def timestamp(value: str) -> str:
    try:
        parsed = parse_time(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be an ISO-8601 timestamp, e.g. 2026-10-26T12:00:00Z")
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError("must include a time zone, e.g. 2026-10-26T12:00:00Z")
    return value


def page_size(value: str) -> int:
    n = int(value)
    if not 1 <= n <= MAX_PAGE_SIZE:
        raise argparse.ArgumentTypeError(f"must be between 1 and {MAX_PAGE_SIZE}")
    return n


def parse_cli_args(argv: Optional[List[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Alert on Scan transactions of tracked parties: transfers to unexpected "
        "recipients, and optionally all transactions they confirm"
    )
    parser.add_argument("--scan-url", required=True, help="Base URL of the Scan instance, e.g. https://scan.sv-1.example.com")
    parser.add_argument("--db", required=True, help="SQLite database file for the contract map and progress; created if missing")
    parser.add_argument("--config", required=True, help="JSON file with the tracked parties; see README.md")
    parser.add_argument(
        "--slack-webhook-url",
        default=os.environ.get("SLACK_WEBHOOK_URL"),
        help="Slack incoming webhook URL to post alerts to (default: $SLACK_WEBHOOK_URL)",
    )
    parser.add_argument(
        "--slack-batch-seconds",
        type=float,
        default=60,
        help="While catching up, collect alerts for up to this long into one Slack message (default: %(default)s)",
    )
    parser.add_argument("--migration-id", type=int, help="Migration id of the ACS snapshot to seed from (default: the latest)")
    parser.add_argument(
        "--seed-snapshot-before",
        type=timestamp,
        help="Seed from the latest ACS snapshot at or before this time (default: now); only used for the first seed",
    )
    parser.add_argument(
        "--stop-at-record-time",
        type=timestamp,
        help="Exit once all updates up to this record time have been processed, e.g. to replay a fixed time range",
    )
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


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_cli_args(argv)
    logging.basicConfig(
        stream=sys.stderr,
        level=args.loglevel.upper(),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        config = load_config(args.config)
        with contextlib.closing(Store(args.db)) as store:
            stored_url = store.get_meta(Meta.SCAN_URL)
            scan_url = args.scan_url.rstrip("/")
            if stored_url is None:
                store.set_meta(Meta.SCAN_URL, scan_url)
            elif stored_url != scan_url:
                LOG.warning(f"{args.db} was created against {stored_url}; continuing with {scan_url}")
            scan = ScanClient(scan_url, args.acs_requests_per_second, args.updates_requests_per_second)
            slack = SlackNotifier(args.slack_webhook_url) if args.slack_webhook_url else None
            monitor = Monitor(args, scan, store, slack, config)
            monitor.prepare()
            monitor.seed()
            monitor.stream()
    except StopRequested:
        pass
    except FatalError as e:
        LOG.error(str(e))
        return 1
    except KeyboardInterrupt:
        LOG.info("Interrupted; progress up to the last alerts sent is saved")
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
