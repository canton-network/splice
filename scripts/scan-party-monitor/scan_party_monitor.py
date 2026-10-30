#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Monitors a Scan instance for activity of a configured set of tracked parties, and alerts on
  - transfers of Amulet from a tracked party to a party that is not one of its expected
    recipients, and
  - for tracked parties with `alert_confirmations` set, every transaction that the party
    confirms, according to the mediator verdict. This includes transactions that are
    entirely private to other parties than the DSO party, and rejected ones.

Matching transactions are printed to stdout as JSON lines and, optionally, posted to a Slack
incoming webhook.

The script follows Scan's event stream, which pairs each transaction visible to the DSO party
with the mediator verdict on it. A transfer archives holdings of the sender, but the stream
does not say whose holdings were archived, so the script keeps the holdings of the tracked
parties in a local SQLite database. It is seeded from a Scan ACS snapshot and then kept up to
date from the event stream. Both phases are resumable: stopping the script and restarting it
with the same --db continues where it left off. Adding parties to the configuration re-seeds
the holdings.

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
from typing import Any, Callable, Dict, FrozenSet, List, NewType, Optional, Set, Tuple, Union

VERSION = "0.3.0"
USER_AGENT = f"splice-scan-party-monitor/{VERSION}"
SCHEMA_VERSION = "2"
MAX_PAGE_SIZE = 1000
# reduces load on scan with plenty of headroom for MainNet catchup
DEFAULT_EVENTS_PAGE_SIZE = 100
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
VERDICT_REJECTED = "VERDICT_RESULT_REJECTED"
AMULET_PACKAGE = "splice-amulet"
AMULET = "Splice.Amulet:Amulet"
LOCKED_AMULET = "Splice.Amulet:LockedAmulet"

LOG = logging.getLogger("scan-party-monitor")

# A JSON object as returned by Scan, or one of our match records.
Json = Dict[str, Any]
# (migration id, record time): the order of events in Scan.
Position = Tuple[int, datetime]
BEFORE_EVERYTHING: Position = (-1, datetime.min.replace(tzinfo=timezone.utc))
# A party id from the configuration. Only _check_party_id creates one, so it has passed that
# check; party ids from Scan are plain strs.
PartyId = NewType("PartyId", str)


class Meta(Enum):
    """Keys of the store's meta table."""

    SCHEMA_VERSION = "schema_version"
    SCAN_URL = "scan_url"
    # JSON list of the parties whose holdings are stored
    TRACKED_PARTIES = "tracked_parties"
    # JSON object: party -> [migration id, record time] up to which its matches were reported
    REPORTED_UNTIL = "reported_until"
    # The ACS snapshot to seed from, and the progress of the seed
    SNAPSHOT_MIGRATION_ID = "snapshot_migration_id"
    SNAPSHOT_RECORD_TIME = "snapshot_record_time"
    ACS_NEXT_PAGE_TOKEN = "acs_next_page_token"
    ACS_PAGES = "acs_pages"
    ACS_HOLDINGS = "acs_holdings"
    ACS_COMPLETE = "acs_complete"
    # The position in the event stream
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


### Scan's JSON, parsed into what the monitor uses


class JsonObject:
    """A JSON object from Scan, with typed accessors that raise FatalError naming the path of
    an unexpected value."""

    def __init__(self, value: object, path: str = "$") -> None:
        if not isinstance(value, dict):
            raise _unexpected(path, "an object", value)
        self.value: Json = value
        self.path = path

    def _field(self, key: str) -> Tuple[object, str]:
        if key not in self.value:
            raise FatalError(f"Unexpected response from Scan: {self.path} lacks {key!r}")
        return self.value[key], f"{self.path}.{key}"

    def string(self, key: str) -> str:
        value, path = self._field(key)
        if not isinstance(value, str):
            raise _unexpected(path, "a string", value)
        return value

    def optional_string(self, key: str) -> Optional[str]:
        return None if self.value.get(key) is None else self.string(key)

    def integer(self, key: str) -> int:
        value, path = self._field(key)
        if not isinstance(value, int) or isinstance(value, bool):
            raise _unexpected(path, "an integer", value)
        return value

    def boolean(self, key: str) -> bool:
        value, path = self._field(key)
        if not isinstance(value, bool):
            raise _unexpected(path, "true or false", value)
        return value

    def obj(self, key: str) -> JsonObject:
        value, path = self._field(key)
        return JsonObject(value, path)

    def optional_obj(self, key: str) -> Optional[JsonObject]:
        return None if self.value.get(key) is None else self.obj(key)

    def _list(self, key: str) -> Tuple[List[object], str]:
        value, path = self._field(key)
        if not isinstance(value, list):
            raise _unexpected(path, "a list", value)
        return value, path

    def objs(self, key: str) -> List[JsonObject]:
        values, path = self._list(key)
        return [JsonObject(v, f"{path}[{i}]") for i, v in enumerate(values)]

    def strings(self, key: str) -> List[str]:
        values, path = self._list(key)
        for i, v in enumerate(values):
            if not isinstance(v, str):
                raise _unexpected(f"{path}[{i}]", "a string", v)
        return [str(v) for v in values]


def _unexpected(path: str, expected: str, value: object) -> FatalError:
    return FatalError(f"Unexpected response from Scan: {path} is not {expected}: {json.dumps(value)[:200]}")


@dataclass(frozen=True)
class Holding:
    """The parts of an Amulet or LockedAmulet that the monitor uses."""

    owner: str
    amount: Optional[str]

    @staticmethod
    def of(package_name: str, template_id: str, create_arguments: object) -> Optional[Holding]:
        """The holding of a contract, None for other templates. Daml values are read leniently."""
        name = qualified_name(template_id) if package_name == AMULET_PACKAGE else None
        amulet: object = None
        if name == AMULET:
            amulet = create_arguments
        elif name == LOCKED_AMULET and isinstance(create_arguments, dict):
            amulet = create_arguments.get("amulet")
        if not isinstance(amulet, dict) or not isinstance(amulet.get("owner"), str):
            return None
        amount = amulet.get("amount")
        initial_amount = amount.get("initialAmount") if isinstance(amount, dict) else None
        return Holding(amulet["owner"], initial_amount if isinstance(initial_amount, str) else None)


@dataclass(frozen=True)
class Contract:
    """An ActiveContract, or the contract of a CreatedEvent."""

    contract_id: str
    template_id: str
    created_at: str
    holding: Optional[Holding]

    @staticmethod
    def from_json(o: JsonObject) -> Contract:
        template_id = o.string("template_id")
        return Contract(
            contract_id=o.string("contract_id"),
            template_id=template_id,
            created_at=o.string("created_at"),
            holding=Holding.of(o.string("package_name"), template_id, o.value.get("create_arguments")),
        )


@dataclass(frozen=True)
class HoldingsPage:
    """An AcsResponseV2 of /v2/holdings/state."""

    contracts: List[Contract]
    next_page_token: Optional[str]

    @staticmethod
    def from_json(o: JsonObject) -> HoldingsPage:
        return HoldingsPage(
            contracts=[Contract.from_json(c) for c in o.objs("created_events")],
            next_page_token=o.optional_string("next_page_token"),
        )


@dataclass(frozen=True)
class CreatedEvent:
    event_id: str
    contract: Contract


@dataclass(frozen=True)
class ExercisedEvent:
    event_id: str
    contract_id: str
    consuming: bool


TreeEvent = Union[CreatedEvent, ExercisedEvent]


def _created_event(o: JsonObject) -> CreatedEvent:
    return CreatedEvent(event_id=o.string("event_id"), contract=Contract.from_json(o))


@dataclass(frozen=True)
class Transaction:
    """An UpdateHistoryTransactionV2, with its events in execution order."""

    update_id: str
    migration_id: int
    record_time: str
    events_in_order: List[TreeEvent]

    @staticmethod
    def from_json(o: JsonObject) -> Transaction:
        events = o.obj("events_by_id")
        # Pre-order traversal is execution order. Scan only has the events that the DSO party
        # sees, so children may be missing.
        ordered: List[TreeEvent] = []
        stack = list(reversed(o.strings("root_event_ids")))
        while stack:
            event = events.optional_obj(stack.pop())
            if event is None:
                continue
            event_type = event.string("event_type")
            if event_type == "created_event":
                ordered.append(_created_event(event))
            elif event_type == "exercised_event":
                ordered.append(
                    ExercisedEvent(
                        event_id=event.string("event_id"),
                        contract_id=event.string("contract_id"),
                        consuming=event.boolean("consuming"),
                    )
                )
                stack.extend(reversed(event.strings("child_event_ids")))
            else:
                raise _unexpected(f"{event.path}.event_type", "created_event or exercised_event", event_type)
        return Transaction(
            update_id=o.string("update_id"),
            migration_id=o.integer("migration_id"),
            record_time=o.string("record_time"),
            events_in_order=ordered,
        )


@dataclass(frozen=True)
class Reassignment:
    """An UpdateHistoryReassignment: an assignment, with its created event, or an unassignment."""

    update_id: str
    migration_id: int
    record_time: str
    created: Optional[CreatedEvent]

    @staticmethod
    def from_json(o: JsonObject) -> Reassignment:
        event = o.obj("event")
        created = event.optional_obj("created_event")
        return Reassignment(
            update_id=o.string("update_id"),
            migration_id=event.integer("migration_id"),
            record_time=o.string("record_time"),
            created=None if created is None else _created_event(created),
        )


@dataclass(frozen=True)
class View:
    view_id: int
    # All parties of the view's confirmation quorums
    confirming_parties: FrozenSet[str]

    @staticmethod
    def from_json(o: JsonObject) -> View:
        return View(
            view_id=o.integer("view_id"),
            confirming_parties=frozenset(p for q in o.objs("confirming_parties") for p in q.strings("parties")),
        )


@dataclass(frozen=True)
class Verdict:
    """An EventHistoryVerdict: the mediator verdict on a transaction."""

    update_id: str
    migration_id: int
    record_time: str
    verdict_result: str
    views: List[View]

    @property
    def rejected(self) -> bool:
        return self.verdict_result == VERDICT_REJECTED

    @staticmethod
    def from_json(o: JsonObject) -> Verdict:
        return Verdict(
            update_id=o.string("update_id"),
            migration_id=o.integer("migration_id"),
            record_time=o.string("record_time"),
            verdict_result=o.string("verdict_result"),
            views=[View.from_json(v) for v in o.obj("transaction_views").objs("views")],
        )


@dataclass(frozen=True)
class EventHistoryItem:
    """An item of Scan's event stream: the part of a transaction that the DSO party sees, the
    mediator verdict on it, or both; or a reassignment. It has at least one of them."""

    update: Optional[Union[Transaction, Reassignment]]
    verdict: Optional[Verdict]

    @staticmethod
    def from_json(o: JsonObject) -> EventHistoryItem:
        update = o.optional_obj("update")
        verdict = o.optional_obj("verdict")
        if update is None and verdict is None:
            raise FatalError(f"Unexpected response from Scan: {o.path} has neither an update nor a verdict")
        return EventHistoryItem(
            update=(
                None
                if update is None
                else Transaction.from_json(update)
                if "events_by_id" in update.value
                else Reassignment.from_json(update)
            ),
            verdict=None if verdict is None else Verdict.from_json(verdict),
        )

    @property
    def _part(self) -> Union[Transaction, Reassignment, Verdict]:
        part = self.update if self.update is not None else self.verdict
        assert part is not None, "from_json requires an update or a verdict"
        return part

    @property
    def update_id(self) -> str:
        return self._part.update_id

    @property
    def migration_id(self) -> int:
        return self._part.migration_id

    @property
    def record_time(self) -> str:
        return self._part.record_time

    @property
    def position(self) -> Position:
        return position(self.migration_id, self.record_time)

    @property
    def private(self) -> bool:
        """An accepted transaction that the DSO party doesn't see at all."""
        return self.update is None and self.verdict is not None and not self.verdict.rejected


### match records


@dataclass(frozen=True)
class TransferReason:
    """A tracked party's holdings went to `receiver`, who is not an expected recipient."""

    event_id: str
    template_id: str
    receiver: str
    amount: Optional[str]

    def to_json(self) -> Json:
        return {
            "reason": "transfer",
            "event_id": self.event_id,
            "template_id": self.template_id,
            "receiver": self.receiver,
            "amount": self.amount,
        }


@dataclass(frozen=True)
class ConfirmingReason:
    """A tracked party with alert_confirmations confirmed these views of a transaction."""

    views: List[int]

    def to_json(self) -> Json:
        return {"reason": "confirming", "views": self.views}


Reason = Union[TransferReason, ConfirmingReason]


@dataclass(frozen=True)
class Match:
    """A transaction to report, with the reasons per tracked party."""

    update_id: str
    migration_id: int
    record_time: str
    scan_url: str
    # None for a reassignment
    verdict_result: Optional[str]
    private: bool
    parties: Dict[PartyId, List[Reason]]

    @property
    def rejected(self) -> bool:
        return self.verdict_result == VERDICT_REJECTED

    def to_json(self) -> Json:
        """The match as printed to stdout."""
        return {
            "update_id": self.update_id,
            "migration_id": self.migration_id,
            "record_time": self.record_time,
            "scan_url": self.scan_url,
            **({} if self.verdict_result is None else {"verdict_result": self.verdict_result}),
            **({"private": True} if self.private else {}),
            "parties": {p: [r.to_json() for r in rs] for p, rs in self.parties.items()},
        }


def group_by_party(found: List[Tuple[PartyId, Reason]]) -> Dict[PartyId, List[Reason]]:
    """The reasons per party, in the order the parties were first found."""
    grouped: Dict[PartyId, List[Reason]] = {}
    for party, reason in found:
        grouped.setdefault(party, []).append(reason)
    return grouped


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
    def __init__(self, url: str, acs_rate: float, events_rate: float) -> None:
        self.url = url.rstrip("/")
        self.acs_pacer = Pacer("/v2/holdings/state", acs_rate)
        self.events_pacer = Pacer("/v0/events", events_rate)
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

    def get_holdings_page(
        self,
        migration_id: int,
        record_time: str,
        owner_party_ids: List[PartyId],
        page_size: int,
        after: Optional[str],
    ) -> HoldingsPage:
        """A page of the Amulet and LockedAmulet holdings of the given owners in an ACS snapshot."""
        body: Json = {
            "migration_id": migration_id,
            "record_time": record_time,
            "owner_party_ids": owner_party_ids,
            "page_size": page_size,
        }
        if after is not None:
            body["after"] = after
        response = self._call(self.acs_pacer, "POST", "/v2/holdings/state", body)
        return HoldingsPage.from_json(JsonObject(response, "/v2/holdings/state"))

    def get_events(
        self,
        after_migration_id: int,
        after_record_time: str,
        page_size: int,
        on_retry: Callable[[], None] = lambda: None,
    ) -> List[EventHistoryItem]:
        body: Json = {
            "after": {
                "after_migration_id": after_migration_id,
                "after_record_time": after_record_time,
            },
            "page_size": page_size,
        }
        response = self._call(self.events_pacer, "POST", "/v0/events", body, on_retry=on_retry)
        return [EventHistoryItem.from_json(e) for e in JsonObject(response, "/v0/events").objs("events")]


### local store


class Store:
    """SQLite store for the holdings of the tracked parties, and progress.

    The caller controls transactions with begin()/commit(), so that the holdings and the
    progress markers are always updated atomically.
    """

    def __init__(self, path: str) -> None:
        self.conn = sqlite3.connect(path, isolation_level=None)
        self.conn.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS parties (id INTEGER PRIMARY KEY, party TEXT UNIQUE NOT NULL)"
        )
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS holdings (cid TEXT PRIMARY KEY, owner INTEGER NOT NULL) WITHOUT ROWID"
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

    def put_holding(self, cid: str, owner: str) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO holdings (cid, owner) VALUES (?, ?)", (cid, self.party_id(owner))
        )

    def holding_owner_id(self, cid: str) -> Optional[int]:
        """The party id of the owner of a stored holding, None if it is not stored."""
        row = self.conn.execute("SELECT owner FROM holdings WHERE cid = ?", (cid,)).fetchone()
        return None if row is None else int(row[0])

    def delete_holding(self, cid: str) -> None:
        self.conn.execute("DELETE FROM holdings WHERE cid = ?", (cid,))

    def clear_holdings(self) -> None:
        self.conn.execute("DELETE FROM holdings")


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


def format_reason(reason: Reason) -> str:
    if isinstance(reason, TransferReason):
        amount = reason.amount or "an unknown amount"
        return f"transfer of {amount} to `{reason.receiver}` (not an expected recipient)"
    return "confirming party"


def _kind(match: Match) -> Optional[str]:
    """How a transaction differs from one that Scan shows in full, if it does."""
    if match.rejected:
        return "rejected"
    return "private" if match.private else None


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


def _update_line(match: Match) -> str:
    """One line of a grouped Slack message."""
    reasons = [f"`{party}`: {format_reason(r)}" for party, rs in match.parties.items() for r in rs]
    more = len(reasons) - SLACK_MAX_REASONS_PER_UPDATE
    shown = "; ".join(reasons[:SLACK_MAX_REASONS_PER_UPDATE] + ([f"+{more} more"] if more > 0 else []))
    kind = _kind(match)
    return f"• `{match.update_id}` at `{match.record_time}`{f' ({kind})' if kind else ''}: {shown}"


def format_slack_message(matches: List[Match]) -> str:
    """One Slack message for all of `matches`, which must not be empty."""
    first = matches[0]
    if len(matches) == 1:
        parties = first.parties
        kind = _kind(first)
        header = [
            f":rotating_light: Alert for tracked {_plural(len(parties), 'party', 'parties')} "
            f"on {first.scan_url}",
            f"update `{first.update_id}` at record time `{first.record_time}` "
            f"(migration {first.migration_id}{f', {kind}' if kind else ''})",
        ]
        lines = [f"• `{party}`: {format_reason(r)}" for party, rs in parties.items() for r in rs]
        return _join_capped(header, lines, "reasons")

    header = [
        f":rotating_light: {len(matches)} alerts for tracked parties on {first.scan_url}, "
        f"record times `{first.record_time}` – `{matches[-1].record_time}`"
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
        self.confirmation_parties = {p for p, c in config.items() if c.alert_confirmations}
        # Per party, the position up to which its matches were already reported before a re-seed.
        reported_until = json.loads(store.get_meta(Meta.REPORTED_UNTIL) or "{}")
        self.reported_until: Dict[PartyId, Position] = {
            p: position(*reported_until[p]) for p in config if p in reported_until
        }
        self.stats: Dict[str, int] = {"transactions": 0, "matches": 0}

    ### configuration changes

    def prepare(self) -> None:
        """Compares the configured parties with the ones whose holdings are stored, and
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
            # events from there without reporting again what was already reported.
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
        store.clear_holdings()
        store.set_meta(Meta.ACS_COMPLETE, 0)
        store.set_meta(Meta.ACS_NEXT_PAGE_TOKEN, None)
        store.set_meta(Meta.ACS_PAGES, 0)
        store.set_meta(Meta.ACS_HOLDINGS, 0)
        store.set_meta(Meta.TRACKED_PARTIES, json.dumps(configured))
        store.commit()
        LOG.info(
            f"Tracked parties changed ({'; '.join(changes)}); re-seeding the holdings"
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
            store.set_meta(Meta.ACS_HOLDINGS, 0)
            store.commit()
            LOG.info(f"Seeding holdings from the ACS snapshot at {record_time} (migration id {migration_id})")
        else:
            migration_id = int(stored_migration_id)
            record_time = store.require_meta(Meta.SNAPSHOT_RECORD_TIME)
            if store.get_meta(Meta.ACS_NEXT_PAGE_TOKEN) is not None:
                LOG.info(
                    f"Resuming the seed from the ACS snapshot at {record_time} (migration id {migration_id}) "
                    f"after {store.get_meta(Meta.ACS_PAGES)} pages and {store.get_meta(Meta.ACS_HOLDINGS)} holdings"
                )

        party_ids = sorted(self.config)
        token = store.get_meta(Meta.ACS_NEXT_PAGE_TOKEN)
        pages = int(store.require_meta(Meta.ACS_PAGES))
        holdings = int(store.require_meta(Meta.ACS_HOLDINGS))
        pages_this_run = 0
        holdings_this_run = 0
        started = time.monotonic()
        last_report = 0.0
        while True:
            try:
                page = self.scan.get_holdings_page(
                    migration_id, record_time, party_ids, self.args.acs_page_size, token
                )
            except NotFound:
                raise FatalError(
                    f"The ACS snapshot at {record_time} (migration id {migration_id}) is no longer "
                    f"available on {self.scan.url}. Delete {self.args.db} and start over."
                )
            token = page.next_page_token
            pages += 1
            pages_this_run += 1
            store.begin()
            for contract in page.contracts:
                if self.store_if_tracked(contract):
                    holdings += 1
                    holdings_this_run += 1
            store.set_meta(Meta.ACS_NEXT_PAGE_TOKEN, token)
            store.set_meta(Meta.ACS_PAGES, pages)
            store.set_meta(Meta.ACS_HOLDINGS, holdings)
            if token is None:
                store.set_meta(Meta.ACS_COMPLETE, 1)
                store.set_meta(Meta.CURSOR_MIGRATION_ID, migration_id)
                store.set_meta(Meta.CURSOR_RECORD_TIME, record_time)
            store.commit()

            now = time.monotonic()
            if token is None or now - last_report >= self.args.progress_interval_seconds:
                last_report = now
                rate = holdings_this_run / max(now - started, 1e-6)
                created_at = page.contracts[-1].created_at if page.contracts else "-"
                LOG.info(
                    f"ACS seed: {pages} pages, {holdings} holdings stored "
                    f"({rate:.0f} holdings/s), reached holdings created at {created_at}"
                )
            if token is None:
                LOG.info(f"ACS seed complete: {holdings} holdings")
                return
            if self.args.stop_after_acs_pages is not None and pages_this_run >= self.args.stop_after_acs_pages:
                LOG.info(f"Stopping after {pages_this_run} ACS pages as requested; rerun to resume")
                raise StopRequested()

    def store_if_tracked(self, contract: Contract) -> bool:
        """Stores a holding if a tracked party owns it."""
        if contract.holding is None or contract.holding.owner not in self.config:
            return False
        self.store.put_holding(contract.contract_id, contract.holding.owner)
        return True

    ### event stream

    def stream(self) -> None:
        store = self.store
        migration_id = int(store.require_meta(Meta.CURSOR_MIGRATION_ID))
        record_time = store.require_meta(Meta.CURSOR_RECORD_TIME)
        n = len(self.config)
        LOG.info(
            f"Streaming events after {record_time} (migration id {migration_id}), tracking "
            f"{n} {_plural(n, 'party', 'parties')}"
        )
        last_heartbeat = time.monotonic()
        # Matches not yet posted to Slack. Their events are only committed to the store,
        # together with the cursor, once they have been posted.
        unposted: List[Match] = []
        oldest_unposted = 0.0

        def deliver(now: bool) -> None:
            """Posts the unposted matches if `now` or they are due, and then commits the events
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
            events = self.scan.get_events(
                migration_id, record_time, self.args.events_page_size, on_retry=lambda: deliver(False)
            )
            caught_up = len(events) < self.args.events_page_size
            # Record times increase across migrations, so the record time alone gives the order.
            in_range = (
                events
                if stop_at is None
                else list(itertools.takewhile(lambda e: parse_time(e.record_time) <= stop_at, events))
            )
            matches = [m for m in map(self.process_event_history_item, in_range) if m is not None]
            for m in matches:
                print(json.dumps(m.to_json()), flush=True)
            if matches and not unposted:
                oldest_unposted = time.monotonic()
            unposted = unposted + matches
            self.stats["matches"] += len(matches)
            if in_range:
                migration_id, record_time = in_range[-1].migration_id, in_range[-1].record_time
                store.set_meta(Meta.CURSOR_MIGRATION_ID, migration_id)
                store.set_meta(Meta.CURSOR_RECORD_TIME, record_time)
                self.stats["transactions"] += len(in_range)
                self.forget_reported_until(position(migration_id, record_time))
            # Scan only returns events with later record times than the cursor, so none at or
            # before the cutoff can follow once the cursor is at it.
            reached_stop = len(in_range) < len(events) or (
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
            f"{self.stats['transactions']} transactions, {self.stats['matches']} matching"
        )

    def process_event_history_item(self, item: EventHistoryItem) -> Optional[Match]:
        """Updates the holdings from an item of Scan's event stream; returns its match, if any."""
        found = (self._update_reasons(item.update) if item.update is not None else []) + (
            self._verdict_reasons(item.verdict) if item.verdict is not None else []
        )
        at = item.position
        unreported = {
            p: reasons
            for p, reasons in group_by_party(found).items()
            if self.reported_until.get(p, BEFORE_EVERYTHING) < at
        }
        if not unreported:
            return None
        return Match(
            update_id=item.update_id,
            migration_id=item.migration_id,
            record_time=item.record_time,
            scan_url=self.scan.url,
            verdict_result=None if item.verdict is None else item.verdict.verdict_result,
            private=item.private,
            parties=unreported,
        )

    def _update_reasons(self, update: Union[Transaction, Reassignment]) -> List[Tuple[PartyId, Reason]]:
        """Updates the holdings from the part of a transaction that the DSO party sees, and
        returns its transfers to unexpected recipients."""
        if isinstance(update, Reassignment):
            # No matching, but keep the holdings complete.
            if update.created is not None:
                self.store_if_tracked(update.created.contract)
            return []

        # In execution order, holdings created earlier in this transaction are stored before
        # they can be archived.
        senders: Set[PartyId] = set()
        for event in update.events_in_order:
            if isinstance(event, CreatedEvent):
                self.store_if_tracked(event.contract)
            elif event.consuming:
                owner_id = self.store.holding_owner_id(event.contract_id)
                if owner_id is not None:
                    if owner_id in self.tracked_by_id:
                        senders.add(self.tracked_by_id[owner_id])
                    self.store.delete_holding(event.contract_id)

        # A transfer moves holdings of a tracked party to holdings of other parties. With
        # several tracked senders, outputs can't be attributed to one of them, so each sender
        # is checked against all outputs.
        created_holdings = [
            (e, e.contract.holding)
            for e in update.events_in_order
            if isinstance(e, CreatedEvent) and e.contract.holding is not None
        ]
        return [
            (sender, TransferReason(e.event_id, e.contract.template_id, holding.owner, holding.amount))
            for sender in sorted(senders)
            for e, holding in created_holdings
            if holding.owner not in self.config[sender].expected_recipients
        ]

    def _verdict_reasons(self, verdict: Verdict) -> List[Tuple[PartyId, Reason]]:
        """The parties with `alert_confirmations` that confirmed a transaction."""
        return [
            (p, ConfirmingReason(confirmed))
            for p in sorted(self.confirmation_parties)
            for confirmed in [[v.view_id for v in verdict.views if p in v.confirming_parties]]
            if confirmed
        ]


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


class DeprecatedAlias(argparse.Action):
    """A former name of the option with the same dest; warns and stores into that dest."""

    def __call__(
        self, parser: argparse.ArgumentParser, namespace: argparse.Namespace, values: Any, option_string: Optional[str] = None
    ) -> None:
        replacement = "--" + self.dest.replace("_", "-")
        sys.stderr.write(f"{parser.prog}: warning: {option_string} is deprecated; use {replacement}\n")
        setattr(namespace, self.dest, values)


def parse_cli_args(argv: Optional[List[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Alert on Scan transactions of tracked parties: transfers to unexpected "
        "recipients, and optionally all transactions they confirm"
    )
    parser.add_argument("--scan-url", required=True, help="Base URL of the Scan instance, e.g. https://scan.sv-1.example.com")
    parser.add_argument("--db", required=True, help="SQLite database file for the tracked parties' holdings and progress; created if missing")
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
        help="Exit once all events up to this record time have been processed, e.g. to replay a fixed time range",
    )
    parser.add_argument("--acs-page-size", type=page_size, default=MAX_PAGE_SIZE, help="Holdings per ACS request (default: %(default)s)")
    parser.add_argument(
        "--acs-requests-per-second",
        type=float,
        default=1.0,
        help="Pace of ACS requests while seeding. Scan's ACS rate limit is shared by all of its clients; "
        "please don't raise this (default: %(default)s)",
    )
    parser.add_argument("--events-page-size", type=page_size, default=DEFAULT_EVENTS_PAGE_SIZE, help="Events per request (default: %(default)s)")
    parser.add_argument(
        "--events-requests-per-second",
        type=float,
        default=2.0,
        help="Pace of event requests while catching up; shared rate limit as above (default: %(default)s)",
    )
    deprecated: Dict[str, Any] = {"action": DeprecatedAlias, "default": argparse.SUPPRESS, "help": argparse.SUPPRESS}
    parser.add_argument("--updates-page-size", dest="events_page_size", type=page_size, **deprecated)
    parser.add_argument("--updates-requests-per-second", dest="events_requests_per_second", type=float, **deprecated)
    parser.add_argument("--poll-interval-seconds", type=float, default=10, help="Wait between polls once caught up (default: %(default)s)")
    parser.add_argument("--progress-interval-seconds", type=float, default=10, help="Interval between ACS seed progress reports (default: %(default)s)")
    parser.add_argument("--heartbeat-interval-seconds", type=float, default=300, help="Interval between status logs while streaming (default: %(default)s)")
    parser.add_argument("--exit-when-caught-up", action="store_true", help="Exit once all events currently in Scan have been processed")
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
            scan = ScanClient(scan_url, args.acs_requests_per_second, args.events_requests_per_second)
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
