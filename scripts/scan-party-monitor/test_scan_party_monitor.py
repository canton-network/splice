#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Unit tests for scan_party_monitor.py, mostly against an in-process fake of Scan.

Run from the repository root with

    python3 -m unittest discover -s scripts/scan-party-monitor -v

Uses only the Python standard library.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import logging
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.parse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, Iterable, Iterator, List, Optional, Tuple, Type
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import scan_party_monitor as spm  # noqa: E402
from scan_party_monitor import Json, Meta  # noqa: E402


def party(hint: str) -> str:
    return f"{hint}::1220{hashlib.sha256(hint.encode()).hexdigest()}"


DSO = party("dso")
ALICE = party("alice")
BOB = party("bob")
CHARLIE = party("charlie")
DAVE = party("dave")

PACKAGE = "0" * 64
AMULET = f"{PACKAGE}:Splice.Amulet:Amulet"
LOCKED_AMULET = f"{PACKAGE}:Splice.Amulet:LockedAmulet"
PREAPPROVAL = f"{PACKAGE}:Splice.AmuletRules:TransferPreapproval"
AMULET_RULES = f"{PACKAGE}:Splice.AmuletRules:AmuletRules"
OTHER = f"{PACKAGE}:Splice.Other:Other"

SNAPSHOT = "2026-01-02T00:00:00Z"


def at(second: int) -> str:
    """A record time `second` seconds after SNAPSHOT."""
    return f"2026-01-02T00:00:{second:02d}.123456Z"


### Scan JSON builders


def created(
    cid: str,
    template_id: str,
    signatories: Iterable[str],
    arguments: Optional[Json] = None,
    observers: Iterable[str] = (),
    event_id: Optional[str] = None,
) -> Json:
    """A created event; without `event_id` in the form of the ACS."""
    event: Json = {
        "contract_id": cid,
        "template_id": template_id,
        "signatories": list(signatories),
        "observers": list(observers),
        "create_arguments": arguments or {},
        "created_at": "2026-01-01T00:00:00Z",
    }
    if event_id is not None:
        event.update(event_type="created_event", event_id=event_id)
    return event


def amulet(cid: str, owner: str, amount: str, event_id: Optional[str] = None) -> Json:
    arguments = {"dso": DSO, "owner": owner, "amount": {"initialAmount": amount}}
    return created(cid, AMULET, [DSO, owner], arguments, event_id=event_id)


def locked_amulet(cid: str, owner: str, holders: List[str], amount: str) -> Json:
    arguments = {
        "amulet": {"dso": DSO, "owner": owner, "amount": {"initialAmount": amount}},
        "lock": {"holders": holders},
    }
    return created(cid, LOCKED_AMULET, [*holders, DSO, owner], arguments)


def preapproval(cid: str, receiver: str) -> Json:
    return created(cid, PREAPPROVAL, [receiver, DSO], {"receiver": receiver, "dso": DSO})


def exercised(
    event_id: str,
    cid: str,
    template_id: str,
    choice: str,
    acting: List[str],
    consuming: bool,
    children: Iterable[str] = (),
) -> Json:
    return {
        "event_type": "exercised_event",
        "event_id": event_id,
        "contract_id": cid,
        "template_id": template_id,
        "choice": choice,
        "choice_argument": {},
        "acting_parties": acting,
        "consuming": consuming,
        "child_event_ids": list(children),
    }


def transaction(update_id: str, record_time: str, *events: Json) -> Json:
    """A transaction whose only root is the first event."""
    return {
        "update_id": update_id,
        "migration_id": 0,
        "record_time": record_time,
        "root_event_ids": [events[0]["event_id"]],
        "events_by_id": {e["event_id"]: e for e in events},
    }


def assignment(update_id: str, record_time: str, created_event: Json) -> Json:
    return {
        "update_id": update_id,
        "record_time": record_time,
        "event": {"migration_id": 0, "created_event": created_event},
    }


def transfer(
    update_id: str,
    record_time: str,
    sender: str,
    input_cid: str,
    receiver: str,
    amount: str,
    change_cid: Optional[str] = None,
    preapproval_cid: Optional[str] = None,
) -> Json:
    """An AmuletRules_Transfer of one holding of `sender`, optionally through a preapproval
    and with change for the sender."""
    u = update_id
    outputs = [f"{u}:output"] + ([f"{u}:change"] if change_cid else [])
    events = [
        exercised(f"{u}:transfer", "amulet-rules", AMULET_RULES, "AmuletRules_Transfer", [sender], False,
                  [f"{u}:archive", *outputs]),
        exercised(f"{u}:archive", input_cid, AMULET, "Archive", [DSO, sender], True),
        amulet(f"{u}-output", receiver, amount, f"{u}:output"),
    ]
    if change_cid:
        events.append(amulet(change_cid, sender, "1.0", f"{u}:change"))
    if preapproval_cid:
        events.insert(0, exercised(f"{u}:send", preapproval_cid, PREAPPROVAL, "TransferPreapproval_Send",
                                   [sender], False, [f"{u}:transfer"]))
    return transaction(update_id, record_time, *events)


ACS = [
    created("observed", OTHER, [DSO], observers=[ALICE]),
    amulet("alice-1", ALICE, "100.0"),
    amulet("alice-2", ALICE, "100.0"),
    amulet("charlie-1", CHARLIE, "50.0"),
    preapproval("bob-preapproval", BOB),
    preapproval("charlie-preapproval", CHARLIE),
    locked_amulet("alice-locked", ALICE, [CHARLIE], "20.0"),
]


def summary(match: Optional[Json]) -> Dict[str, List[Tuple[str, str, str]]]:
    """party -> sorted (reason, template, choice or receiver)"""
    if match is None:
        return {}
    return {
        p: sorted(
            (r["reason"], spm.qualified_name(r["template_id"]), r.get("choice") or r.get("receiver") or "-")
            for r in rs
        )
        for p, rs in match["parties"].items()
    }


### fakes


class FakeScan(spm.ScanClient):
    """Scan with a single ACS snapshot at SNAPSHOT of migration 0, and the given updates after it."""

    def __init__(self, acs: List[Json], updates: List[Json]) -> None:
        super().__init__("https://scan.example.com", 0, 0)
        self.acs = list(acs)
        self.updates = list(updates)
        self.acs_requests: List[Json] = []
        self.snapshot_queries: List[Dict[str, Any]] = []
        # Raise KeyboardInterrupt on this ACS request (1-based), as if Ctrl-C was pressed.
        self.interrupt_acs_request: Optional[int] = None

    def _call(
        self,
        pacer: spm.Pacer,
        method: str,
        path: str,
        body: Optional[Json] = None,
        query: Optional[Dict[str, Any]] = None,
    ) -> Json:
        return self.handle(method, path, body, query)

    def handle(self, method: str, path: str, body: Optional[Json], query: Optional[Dict[str, Any]]) -> Json:
        """Answers a request to `/api/scan<path>`; raises NotFound like ScanClient on HTTP 404."""
        if (method, path) == ("GET", "/v0/migrations/last"):
            return {"migration_id": 0}
        if (method, path) == ("GET", "/v0/state/acs/snapshot-timestamp"):
            assert query is not None
            self.snapshot_queries.append(dict(query))
            if int(query["migration_id"]) != 0 or spm.parse_time(str(query["before"])) < spm.parse_time(SNAPSHOT):
                raise spm.NotFound("no snapshot")
            return {"record_time": SNAPSHOT}
        if (method, path) == ("POST", "/v2/state/acs"):
            assert body is not None
            self.acs_requests.append(body)
            if self.interrupt_acs_request == len(self.acs_requests):
                raise KeyboardInterrupt()
            if body["migration_id"] != 0 or body["record_time"] != SNAPSHOT:
                raise spm.NotFound("no such snapshot")
            parties = set(body["party_ids"])
            matching = [c for c in self.acs if parties & set(c["signatories"] + c["observers"])]
            start = int(body.get("after", 0))
            end = start + body["page_size"]
            page: Json = {"record_time": SNAPSHOT, "migration_id": 0, "created_events": matching[start:end]}
            if end < len(matching):
                page["next_page_token"] = str(end)
            return page
        if (method, path) == ("POST", "/v2/updates"):
            assert body is not None
            after = spm.position(body["after"]["after_migration_id"], body["after"]["after_record_time"])

            def update_position(u: Json) -> spm.Position:
                return spm.position(u.get("migration_id", u.get("event", {}).get("migration_id")), u["record_time"])

            later = [u for u in self.updates if update_position(u) > after]
            return {"transactions": later[: body["page_size"]]}
        raise spm.NotFound(f"{method} {path}")


class FakeSlack(spm.SlackNotifier):
    def __init__(self) -> None:
        super().__init__("https://hooks.example.com/services/test")
        self.posts: List[str] = []
        self.fail = False

    def post(self, text: str) -> None:
        if self.fail:
            raise spm.FatalError("Slack webhook rejected the message with HTTP 400")
        self.posts.append(text)


class ListHandler(logging.Handler):
    def __init__(self) -> None:
        super().__init__()
        self.messages: List[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.messages.append(record.getMessage())


@contextlib.contextmanager
def captured_logs() -> Iterator[List[str]]:
    """Captures the script's log messages (at INFO and above) instead of printing them."""
    handler = ListHandler()
    level, propagate = spm.LOG.level, spm.LOG.propagate
    spm.LOG.addHandler(handler)
    spm.LOG.setLevel(logging.INFO)
    spm.LOG.propagate = False
    try:
        yield handler.messages
    finally:
        spm.LOG.removeHandler(handler)
        spm.LOG.setLevel(level)
        spm.LOG.propagate = propagate


Respond = Callable[[str, str, Dict[str, str], Optional[Json]], Tuple[int, Any, Dict[str, str]]]


@contextlib.contextmanager
def http_server(respond: Respond) -> Iterator[str]:
    """Serves HTTP on localhost with `respond(method, path, query, body) -> (status, body, headers)`,
    and yields its base URL."""

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            pass

        def handle_request(self) -> None:
            url = urllib.parse.urlsplit(self.path)
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length)) if length else None
            status, payload, headers = respond(self.command, url.path, dict(urllib.parse.parse_qsl(url.query)), body)
            data = json.dumps(payload).encode()
            self.send_response(status)
            for k, v in headers.items():
                self.send_header(k, v)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        do_GET = handle_request
        do_POST = handle_request

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


@contextlib.contextmanager
def no_proxy() -> Iterator[None]:
    """Keeps urllib from sending requests for the local test servers to an HTTP proxy."""
    with mock.patch.dict(os.environ, {"no_proxy": "127.0.0.1", "NO_PROXY": "127.0.0.1"}):
        yield


### pure functions


class HelpersTest(unittest.TestCase):
    def test_parse_time(self) -> None:
        utc = timezone.utc
        self.assertEqual(spm.parse_time("2026-01-02T03:04:05Z"), datetime(2026, 1, 2, 3, 4, 5, tzinfo=utc))
        self.assertEqual(spm.parse_time("2026-01-02T03:04:05.5Z"), datetime(2026, 1, 2, 3, 4, 5, 500000, tzinfo=utc))
        # Nanoseconds are truncated to microseconds.
        self.assertEqual(
            spm.parse_time("2026-01-02T03:04:05.123456789+00:00"),
            datetime(2026, 1, 2, 3, 4, 5, 123456, tzinfo=utc),
        )

    def test_backoff_delay(self) -> None:
        for attempt, low, high in [(0, 1, 2), (2, 4, 8), (10, 30, 60), (10_000, 30, 60)]:
            with self.subTest(attempt=attempt):
                self.assertTrue(low <= spm.backoff_delay(attempt) <= high)

    def test_utc_now_iso_keeps_microseconds(self) -> None:
        now = datetime(2026, 1, 2, 3, 4, 5, 678901, tzinfo=timezone.utc)
        with mock.patch.object(spm, "datetime", wraps=datetime) as patched:
            patched.now.return_value = now
            self.assertEqual(spm.utc_now_iso(), "2026-01-02T03:04:05.678901Z")
        self.assertEqual(spm.parse_time("2026-01-02T03:04:05.678901Z"), now)

    def test_positions_order_by_migration_then_record_time(self) -> None:
        self.assertLess(spm.position(0, at(2)), spm.position(0, at(10)))
        self.assertLess(spm.position(0, at(10)), spm.position(1, at(2)))
        self.assertEqual(spm.position(0, "2026-01-02T00:00:01Z"), spm.position(0, "2026-01-02T00:00:01.000000Z"))

    def test_qualified_name(self) -> None:
        self.assertEqual(spm.qualified_name(AMULET), "Splice.Amulet:Amulet")

    def test_holdings(self) -> None:
        self.assertEqual(spm.holding_owner(amulet("a", ALICE, "1.5")), ALICE)
        self.assertEqual(spm.holding_amount(amulet("a", ALICE, "1.5")), "1.5")
        locked = locked_amulet("l", ALICE, [CHARLIE], "2.5")
        self.assertEqual(spm.holding_owner(locked), ALICE)
        self.assertEqual(spm.holding_amount(locked), "2.5")
        self.assertIsNone(spm.holding_owner(preapproval("p", BOB)))
        self.assertIsNone(spm.holding_owner(created("c", AMULET, [DSO, ALICE])))

    def test_timestamp_argument(self) -> None:
        self.assertEqual(spm.timestamp("2026-10-26T12:00:00Z"), "2026-10-26T12:00:00Z")
        for value in ["2026-10-26T12:00:00", "yesterday"]:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                spm.timestamp(value)


class ConfigTest(unittest.TestCase):
    def test_expected_recipients_combine(self) -> None:
        config = spm.parse_config(
            {
                "expected_recipients": [BOB],
                "parties": {
                    ALICE: {"additional_recipients": [CHARLIE]},
                    DAVE: {"alert_confirmations": True},
                },
            }
        )
        self.assertEqual(config[ALICE], spm.PartyConfig(frozenset({ALICE, BOB, CHARLIE}), False))
        self.assertEqual(config[DAVE], spm.PartyConfig(frozenset({DAVE, BOB}), True))

    def test_rejects_invalid_configurations(self) -> None:
        cases: List[Tuple[object, str]] = [
            ([ALICE], "must be a JSON object"),
            ({"parties": {}}, "non-empty 'parties'"),
            ({"parties": {ALICE: {}}, "allowed_recipients": [BOB]}, "unknown keys ['allowed_recipients']"),
            ({"parties": {ALICE: {"recipients": [BOB]}}}, "unknown keys ['recipients']"),
            ({"parties": {ALICE: []}}, "must be an object"),
            ({"parties": {"alice": {}}}, "'alice' is not a party id"),
            ({"parties": {"alice::1220abcd": {}}}, "at least 68 hex digits"),
            ({"parties": {ALICE + "x": {}}}, "is not a party id"),
            ({"expected_recipients": ["bob"], "parties": {ALICE: {}}}, "expected_recipients: 'bob' is not a party id"),
            ({"parties": {ALICE: {"additional_recipients": BOB}}}, "must be a list of party ids"),
            ({"parties": {ALICE: {"alert_confirmations": "yes"}}}, "must be true or false"),
            ({"parties": {party(f"p{i}"): {} for i in range(1001)}}, "At most 1000 parties"),
        ]
        for raw, message in cases:
            with self.subTest(message=message):
                with self.assertRaises(spm.FatalError) as e:
                    spm.parse_config(raw)
                self.assertIn(message, str(e.exception))

    def test_load_config(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "parties.json")
            with self.assertRaisesRegex(spm.FatalError, "Cannot read the configuration"):
                spm.load_config(path)
            with open(path, "w") as f:
                f.write("{")
            with self.assertRaisesRegex(spm.FatalError, "Cannot read the configuration"):
                spm.load_config(path)
            with open(path, "w") as f:
                json.dump({"parties": {ALICE: {}}}, f)
            self.assertEqual(list(spm.load_config(path)), [ALICE])


class SlackFormatTest(unittest.TestCase):
    @staticmethod
    def match(update_id: str, record_time: str, parties: Dict[str, List[Json]]) -> Json:
        return {
            "update_id": update_id,
            "migration_id": 0,
            "record_time": record_time,
            "scan_url": "https://scan.example.com",
            "parties": parties,
        }

    TRANSFER: Json = {"reason": "transfer", "event_id": "u:1", "template_id": AMULET, "receiver": CHARLIE, "amount": "10.0"}
    ACTING: Json = {"reason": "acting", "event_id": "u:0", "template_id": AMULET_RULES, "choice": "AmuletRules_Transfer"}

    def test_single_match(self) -> None:
        text = spm.format_slack_message([self.match("u1", at(1), {ALICE: [self.TRANSFER, self.ACTING]})])
        self.assertEqual(
            text.splitlines(),
            [
                ":rotating_light: Alert for tracked party on https://scan.example.com",
                f"update `u1` at record time `{at(1)}` (migration 0)",
                f"• `{ALICE}`: transfer of 10.0 to `{CHARLIE}` (not an expected recipient)",
                f"• `{ALICE}`: acting on Splice.AmuletRules:AmuletRules / AmuletRules_Transfer",
            ],
        )

    def test_several_matches_are_grouped(self) -> None:
        text = spm.format_slack_message(
            [
                self.match("u1", at(1), {ALICE: [self.TRANSFER]}),
                self.match("u2", at(2), {ALICE: [self.TRANSFER] * 5}),
            ]
        )
        lines = text.splitlines()
        self.assertEqual(
            lines[0],
            f":rotating_light: 2 alerts for tracked parties on https://scan.example.com, "
            f"record times `{at(1)}` – `{at(2)}`",
        )
        self.assertEqual(len(lines), 3)
        self.assertTrue(lines[1].startswith(f"• `u1` at `{at(1)}`: `{ALICE}`: transfer of 10.0"))
        self.assertTrue(lines[2].startswith(f"• `u2` at `{at(2)}`: "))
        self.assertTrue(lines[2].endswith("; +2 more"))

    def test_long_messages_are_capped(self) -> None:
        matches = [self.match(f"u{i}", at(i), {ALICE: [self.TRANSFER]}) for i in range(30)]
        lines = spm.format_slack_message(matches).splitlines()
        self.assertEqual(len(lines), 1 + spm.SLACK_MAX_LINES + 1)
        self.assertEqual(lines[-1], "… and 10 more updates (see the script's stdout)")


class StoreTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.path = os.path.join(tmp.name, "monitor.sqlite")

    def open(self) -> spm.Store:
        store = spm.Store(self.path)
        self.addCleanup(store.close)
        return store

    def test_contracts_persist(self) -> None:
        store = self.open()
        store.put_contract("c1", [DSO, ALICE], ALICE)
        store.put_contract("c2", [BOB], None)
        reopened = self.open()
        self.assertEqual(
            reopened.lookup("c1"), spm.StoredContract({reopened.party_id(DSO), reopened.party_id(ALICE)}, reopened.party_id(ALICE))
        )
        self.assertEqual(reopened.lookup("c2"), spm.StoredContract({reopened.party_id(BOB)}, None))
        reopened.delete_contract("c1")
        self.assertIsNone(reopened.lookup("c1"))
        reopened.clear_contracts()
        self.assertIsNone(reopened.lookup("c2"))

    def test_meta(self) -> None:
        store = self.open()
        self.assertIsNone(store.get_meta(Meta.ACS_PAGES))
        with self.assertRaisesRegex(spm.FatalError, "lacks acs_pages"):
            store.require_meta(Meta.ACS_PAGES)
        store.set_meta(Meta.ACS_PAGES, 3)
        self.assertEqual(store.require_meta(Meta.ACS_PAGES), "3")
        store.set_meta(Meta.ACS_PAGES, None)
        self.assertIsNone(store.get_meta(Meta.ACS_PAGES))

    def test_rollback_discards_changes(self) -> None:
        store = self.open()
        store.begin()
        store.put_contract("c1", [ALICE], None)
        store.rollback()
        self.assertIsNone(store.lookup("c1"))
        store.rollback()  # no transaction: no-op

    def test_rejects_other_schema_versions(self) -> None:
        self.open().set_meta(Meta.SCHEMA_VERSION, "0")
        with self.assertRaisesRegex(spm.FatalError, "schema version 0 is not supported"):
            spm.Store(self.path)


### the monitor against a fake Scan


class MonitorTestCase(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.db = os.path.join(tmp.name, "monitor.sqlite")
        self.scan = FakeScan(ACS, [])
        self.slack = FakeSlack()
        self.logs: List[str] = []

    def monitor(self, config: Json, *flags: str) -> spm.Monitor:
        args = spm.parse_cli_args(
            ["--scan-url", self.scan.url, "--db", self.db, "--config", "-", "--exit-when-caught-up", *flags]
        )
        return spm.Monitor(args, self.scan, spm.Store(self.db), self.slack, spm.parse_config(config))

    def run_monitor(self, config: Json, *flags: str, raises: Optional[Type[BaseException]] = None) -> List[Json]:
        """Runs the monitor like main() does; returns the matches printed to stdout."""
        out = io.StringIO()
        monitor = self.monitor(config, *flags)
        try:
            with contextlib.redirect_stdout(out), captured_logs() as self.logs:
                if raises is None:
                    monitor.prepare()
                    monitor.seed()
                    monitor.stream()
                else:
                    with self.assertRaises(raises):
                        monitor.prepare()
                        monitor.seed()
                        monitor.stream()
        finally:
            # Like the end of the process: an open transaction is rolled back.
            monitor.store.close()
        return [json.loads(line) for line in out.getvalue().splitlines()]

    def seeded(self, config: Json) -> spm.Monitor:
        monitor = self.monitor(config)
        self.addCleanup(monitor.store.close)
        with captured_logs():
            monitor.prepare()
            monitor.seed()
        return monitor

    def assertLogged(self, fragment: str) -> None:
        self.assertTrue(any(fragment in m for m in self.logs), f"{fragment!r} not in {self.logs}")

    def assertNotLogged(self, fragment: str) -> None:
        self.assertFalse(any(fragment in m for m in self.logs), f"{fragment!r} in {self.logs}")

    def meta(self, key: Meta) -> Optional[str]:
        conn = sqlite3.connect(self.db)
        try:
            row = conn.execute("SELECT value FROM meta WHERE key = ?", (key.value,)).fetchone()
            return row[0] if row else None
        finally:
            conn.close()


ONLY_ALICE: Json = {"parties": {ALICE: {}}}


class SeedTest(MonitorTestCase):
    def test_stores_only_contracts_signed_by_tracked_parties(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        self.assertEqual([r["party_ids"] for r in self.scan.acs_requests], [[ALICE]])
        store = monitor.store
        alice = store.party_id(ALICE)
        self.assertEqual(store.lookup("alice-1"), spm.StoredContract({store.party_id(DSO), alice}, alice))
        self.assertEqual(store.lookup("alice-locked"), spm.StoredContract({store.party_id(DSO), store.party_id(CHARLIE), alice}, alice))
        self.assertIsNone(store.lookup("observed"))  # alice is only an observer
        self.assertIsNone(store.lookup("charlie-1"))
        self.assertEqual(self.meta(Meta.ACS_COMPLETE), "1")
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), SNAPSHOT)

    def test_resumes_after_stop_after_acs_pages(self) -> None:
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1", "--stop-after-acs-pages", "2", raises=spm.StopRequested)
        self.assertLogged("Stopping after 2 ACS pages as requested")
        self.assertEqual((self.meta(Meta.ACS_PAGES), self.meta(Meta.ACS_CONTRACTS)), ("2", "1"))
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1")
        self.assertLogged("Resuming the seed")
        self.assertLogged("ACS seed complete: 3 contracts")
        self.assertEqual([r.get("after") for r in self.scan.acs_requests], [None, "1", "2", "3"])

    def test_ctrl_c_stops_the_seed_after_the_last_saved_page(self) -> None:
        self.scan.interrupt_acs_request = 2
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1", raises=spm.StopRequested)
        self.assertLogged("Interrupted after 1 ACS pages; rerun to resume")
        self.assertEqual(self.meta(Meta.ACS_NEXT_PAGE_TOKEN), "1")
        self.scan.interrupt_acs_request = None
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1")
        self.assertLogged("ACS seed complete: 3 contracts")
        self.assertEqual([r.get("after") for r in self.scan.acs_requests], [None, "1", "1", "2", "3"])

    def test_seed_snapshot_before(self) -> None:
        self.run_monitor(ONLY_ALICE, "--seed-snapshot-before", "2026-01-03T00:00:00Z")
        self.assertEqual(self.scan.snapshot_queries, [{"before": "2026-01-03T00:00:00Z", "migration_id": 0}])

    def test_fails_without_a_snapshot(self) -> None:
        for flags in [["--seed-snapshot-before", "2026-01-01T00:00:00Z"], ["--migration-id", "1"]]:
            with self.subTest(flags=flags):
                self.run_monitor(ONLY_ALICE, *flags, raises=spm.FatalError)


class MatchingTest(MonitorTestCase):
    def test_expected_transfers_report_nothing(self) -> None:
        configs: List[Json] = [
            {"parties": {ALICE: {"additional_recipients": [BOB]}}},
            {"expected_recipients": [BOB], "parties": {ALICE: {}}},
        ]
        for config in configs:
            with self.subTest(config=config):
                self.setUp()
                monitor = self.seeded(config)
                update = transfer("u1", at(1), ALICE, "alice-1", BOB, "10.0", "alice-change", "bob-preapproval")
                self.assertIsNone(monitor.process_update(update))
                # The contract map follows the transaction.
                self.assertIsNone(monitor.store.lookup("alice-1"))
                self.assertIsNotNone(monitor.store.lookup("alice-change"))

    def test_unexpected_transfer_is_reported(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"additional_recipients": [BOB]}, CHARLIE: {}}})
        update = transfer("u1", at(1), ALICE, "alice-1", CHARLIE, "10.0", "alice-change", "charlie-preapproval")
        self.assertEqual(
            monitor.process_update(update),
            {
                "update_id": "u1",
                "migration_id": 0,
                "record_time": at(1),
                "scan_url": self.scan.url,
                "parties": {
                    ALICE: [
                        {"reason": "transfer", "event_id": "u1:output", "template_id": AMULET, "receiver": CHARLIE, "amount": "10.0"}
                    ]
                },
            },
        )

    def test_transfer_of_an_untracked_party_is_not_reported(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        self.assertIsNone(monitor.process_update(transfer("u1", at(1), CHARLIE, "charlie-1", BOB, "10.0")))

    def test_alert_confirmations_reports_all_confirmation_reasons(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"alert_confirmations": True}}})
        update = transfer("u1", at(1), ALICE, "alice-1", CHARLIE, "10.0", "alice-change", "charlie-preapproval")
        self.assertEqual(
            summary(monitor.process_update(update)),
            {
                ALICE: [
                    ("acting", "Splice.Amulet:Amulet", "Archive"),
                    ("acting", "Splice.AmuletRules:AmuletRules", "AmuletRules_Transfer"),
                    ("acting", "Splice.AmuletRules:TransferPreapproval", "TransferPreapproval_Send"),
                    ("created_signatory", "Splice.Amulet:Amulet", "-"),
                    ("exercised_contract_signatory", "Splice.Amulet:Amulet", "Archive"),
                    ("transfer", "Splice.Amulet:Amulet", CHARLIE),
                ]
            },
        )

    def test_receiver_through_a_preapproval_confirms(self) -> None:
        monitor = self.seeded({"parties": {BOB: {"alert_confirmations": True}}})
        update = transfer("u1", at(1), ALICE, "alice-1", BOB, "10.0", "alice-change", "bob-preapproval")
        self.assertEqual(
            summary(monitor.process_update(update)),
            {
                BOB: [
                    ("created_signatory", "Splice.Amulet:Amulet", "-"),
                    ("exercised_contract_signatory", "Splice.AmuletRules:TransferPreapproval", "TransferPreapproval_Send"),
                ]
            },
        )

    def test_observers_do_not_confirm(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"alert_confirmations": True}}})
        update = transaction(
            "u1",
            at(1),
            exercised("u1:0", "observed", OTHER, "Other_Do", [DSO], True, ["u1:1"]),
            created("observed-2", OTHER, [DSO], observers=[ALICE], event_id="u1:1"),
        )
        self.assertIsNone(monitor.process_update(update))

    def test_holding_created_and_spent_in_one_transaction(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        update = transaction(
            "u1",
            at(1),
            exercised("u1:0", "amulet-rules", AMULET_RULES, "AmuletRules_Mint", [ALICE], False, ["u1:1", "u1:2", "u1:3"]),
            amulet("fresh", ALICE, "5.0", "u1:1"),
            exercised("u1:2", "fresh", AMULET, "Archive", [DSO, ALICE], True),
            amulet("charlie-2", CHARLIE, "5.0", "u1:3"),
        )
        self.assertEqual(summary(monitor.process_update(update)), {ALICE: [("transfer", "Splice.Amulet:Amulet", CHARLIE)]})

    def test_locked_amulet_sender_is_the_owner_not_the_lock_holder(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {}, CHARLIE: {}}})
        update = transaction(
            "u1",
            at(1),
            exercised("u1:0", "alice-locked", LOCKED_AMULET, "LockedAmulet_Unlock", [CHARLIE], True, ["u1:1"]),
            amulet("bob-2", BOB, "20.0", "u1:1"),
        )
        self.assertEqual(summary(monitor.process_update(update)), {ALICE: [("transfer", "Splice.Amulet:Amulet", BOB)]})

    def test_assigned_holdings_are_tracked(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        self.assertIsNone(monitor.process_update(assignment("r1", at(1), amulet("moved", ALICE, "7.0"))))
        update = transfer("u2", at(2), ALICE, "moved", CHARLIE, "7.0")
        self.assertEqual(summary(monitor.process_update(update)), {ALICE: [("transfer", "Splice.Amulet:Amulet", CHARLIE)]})


def unexpected_transfers(n: int) -> List[Json]:
    """n transfers alice -> charlie, at(1) .. at(n), each spending the previous one's change."""
    inputs = ["alice-1"] + [f"alice-change-{i}" for i in range(1, n)]
    return [
        transfer(f"u{i}", at(i), ALICE, inputs[i - 1], CHARLIE, "1.0", f"alice-change-{i}", "charlie-preapproval")
        for i in range(1, n + 1)
    ]


class StreamTest(MonitorTestCase):
    def test_reports_once_and_resumes(self) -> None:
        self.scan.updates = [
            transfer("u1", at(1), ALICE, "alice-1", CHARLIE, "10.0", "alice-change"),
            transfer("u2", at(2), ALICE, "alice-change", BOB, "1.0"),
        ]
        config: Json = {"parties": {ALICE: {"additional_recipients": [BOB]}}}
        self.assertEqual([m["update_id"] for m in self.run_monitor(config)], ["u1"])
        self.assertLogged("Caught up with scan; exiting as requested")
        self.assertEqual(len(self.slack.posts), 1)
        self.assertIn(f"transfer of 10.0 to `{CHARLIE}`", self.slack.posts[0])
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), at(2))

        self.assertEqual(self.run_monitor(config), [])
        self.assertEqual(len(self.scan.acs_requests), 1)
        self.assertEqual(len(self.slack.posts), 1)

    def test_slack_messages_are_grouped_while_catching_up(self) -> None:
        for batch_seconds, posts in [("60", 1), ("0", 3)]:
            with self.subTest(batch_seconds=batch_seconds):
                self.setUp()
                self.scan.updates = unexpected_transfers(3)
                matches = self.run_monitor(
                    ONLY_ALICE, "--updates-page-size", "1", "--slack-batch-seconds", batch_seconds
                )
                self.assertEqual(len(matches), 3)
                self.assertEqual(len(self.slack.posts), posts)
                if posts == 1:
                    self.assertTrue(self.slack.posts[0].startswith(":rotating_light: 3 alerts"))

    def test_stop_at_record_time(self) -> None:
        self.scan.updates = unexpected_transfers(3)
        matches = self.run_monitor(ONLY_ALICE, "--stop-at-record-time", at(2))
        self.assertEqual([m["update_id"] for m in matches], ["u1", "u2"])
        self.assertLogged(f"Reached --stop-at-record-time {at(2)}; exiting as requested")
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), at(2))
        self.assertEqual([m["update_id"] for m in self.run_monitor(ONLY_ALICE)], ["u3"])

    def test_alerts_are_repeated_if_slack_fails(self) -> None:
        self.scan.updates = unexpected_transfers(1)
        self.slack.fail = True
        self.assertEqual(len(self.run_monitor(ONLY_ALICE, raises=spm.FatalError)), 1)
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), SNAPSHOT)
        self.slack.fail = False
        self.assertEqual(len(self.run_monitor(ONLY_ALICE)), 1)
        self.assertEqual(len(self.slack.posts), 1)
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), at(1))


class ReconfigurationTest(MonitorTestCase):
    def test_adding_a_party_reseeds_without_repeating_alerts(self) -> None:
        self.scan.updates = unexpected_transfers(1)
        self.assertEqual(summary(self.run_monitor(ONLY_ALICE)[0]), {ALICE: [("transfer", "Splice.Amulet:Amulet", CHARLIE)]})

        config: Json = {"parties": {ALICE: {}, CHARLIE: {"alert_confirmations": True}}}
        matches = self.run_monitor(config)
        self.assertLogged(f"Tracked parties changed (added: {CHARLIE}); re-seeding the contracts")
        self.assertEqual(self.scan.snapshot_queries[-1], {"before": at(1), "migration_id": 0})
        self.assertEqual(self.scan.acs_requests[-1]["party_ids"], [ALICE, CHARLIE])
        # u1 is replayed for charlie, but alice's alert is not repeated.
        self.assertEqual(
            [summary(m) for m in matches],
            [
                {
                    CHARLIE: [
                        ("created_signatory", "Splice.Amulet:Amulet", "-"),
                        ("exercised_contract_signatory", "Splice.AmuletRules:TransferPreapproval", "TransferPreapproval_Send"),
                    ]
                }
            ],
        )
        self.assertEqual(self.meta(Meta.REPORTED_UNTIL), "{}")

        self.scan.updates.append(transfer("u2", at(2), ALICE, "alice-2", CHARLIE, "3.0"))
        matches = self.run_monitor(config)
        self.assertNotLogged("re-seeding")
        self.assertEqual(
            [summary(m) for m in matches],
            [
                {
                    ALICE: [("transfer", "Splice.Amulet:Amulet", CHARLIE)],
                    CHARLIE: [("created_signatory", "Splice.Amulet:Amulet", "-")],
                }
            ],
        )

    def test_other_changes_do_not_reseed(self) -> None:
        self.run_monitor({"parties": {ALICE: {}, CHARLIE: {}}})
        configs: List[Json] = [
            {"parties": {ALICE: {"alert_confirmations": True, "additional_recipients": [BOB]}, CHARLIE: {}}},
            {"expected_recipients": [BOB], "parties": {ALICE: {}}},
        ]
        for config in configs:
            with self.subTest(config=config):
                self.run_monitor(config)
                self.assertNotLogged("re-seeding")
                self.assertEqual(len(self.scan.acs_requests), 1)
        self.assertEqual(self.meta(Meta.TRACKED_PARTIES), json.dumps([ALICE]))

    def test_changing_parties_during_the_seed_restarts_it(self) -> None:
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1", "--stop-after-acs-pages", "1", raises=spm.StopRequested)
        config: Json = {"parties": {ALICE: {}, CHARLIE: {}}}
        self.run_monitor(config, "--acs-page-size", "1")
        self.assertLogged(f"Tracked parties changed (added: {CHARLIE}); re-seeding the contracts")
        self.assertNotLogged("Resuming the seed")
        restarted = self.scan.acs_requests[1]
        self.assertEqual((restarted["party_ids"], restarted.get("after")), ([ALICE, CHARLIE], None))
        self.assertLogged("ACS seed complete: 5 contracts")


### HTTP


class HttpTest(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.object(spm, "backoff_delay", return_value=0.0)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.requests: List[Tuple[str, str, Optional[Json]]] = []
        self.responses: List[Tuple[int, Any, Dict[str, str]]] = []

    def respond(self, method: str, path: str, query: Dict[str, str], body: Optional[Json]) -> Tuple[int, Any, Dict[str, str]]:
        self.requests.append((method, path, body))
        return self.responses.pop(0)

    def test_scan_client_retries_and_slows_down_when_rate_limited(self) -> None:
        self.responses = [(503, {"error": "unavailable"}, {}), (429, {"error": "slow down"}, {}), (200, {"transactions": []}, {})]
        with no_proxy(), http_server(self.respond) as url, captured_logs() as logs:
            scan = spm.ScanClient(url, 0, 0)
            self.assertEqual(scan.get_updates(0, SNAPSHOT, 10), [])
        self.assertEqual(len(self.requests), 3)
        self.assertEqual(
            self.requests[-1],
            ("POST", "/api/scan/v2/updates", {"after": {"after_migration_id": 0, "after_record_time": SNAPSHOT}, "page_size": 10}),
        )
        self.assertTrue(any("Rate limited by scan on /v2/updates" in m for m in logs), logs)
        self.assertGreater(scan.updates_pacer.penalty_until, 0)

    def test_scan_client_errors(self) -> None:
        self.responses = [(404, {"error": "not found"}, {}), (400, {"error": "bad request"}, {})]
        with no_proxy(), http_server(self.respond) as url:
            scan = spm.ScanClient(url, 0, 0)
            with self.assertRaises(spm.NotFound):
                scan.get_acs_page(0, SNAPSHOT, [ALICE], 10, None)
            with self.assertRaisesRegex(spm.FatalError, "HTTP 400"):
                scan.get_acs_page(0, SNAPSHOT, [ALICE], 10, "token")
        self.assertEqual(
            [body for _, _, body in self.requests],
            [
                {"migration_id": 0, "record_time": SNAPSHOT, "party_ids": [ALICE], "page_size": 10},
                {"migration_id": 0, "record_time": SNAPSHOT, "party_ids": [ALICE], "page_size": 10, "after": "token"},
            ],
        )

    def test_slack_notifier(self) -> None:
        self.responses = [
            (500, {}, {}),
            (429, {}, {"Retry-After": "0"}),
            (200, {}, {}),
            (400, {"error": "invalid_payload"}, {}),
        ]
        with no_proxy(), http_server(self.respond) as url, captured_logs():
            slack = spm.SlackNotifier(url + "/hook")
            slack.post("hello")
            with self.assertRaisesRegex(spm.FatalError, "HTTP 400"):
                slack.post("rejected")
        self.assertEqual([body for _, _, body in self.requests], [{"text": "hello"}] * 3 + [{"text": "rejected"}])

    def test_main(self) -> None:
        scan = FakeScan(ACS, unexpected_transfers(2))
        posts: List[str] = []

        def respond(method: str, path: str, query: Dict[str, str], body: Optional[Json]) -> Tuple[int, Any, Dict[str, str]]:
            if path == "/slack":
                assert body is not None
                posts.append(body["text"])
                return 200, {}, {}
            try:
                return 200, scan.handle(method, path[len("/api/scan"):], body, query), {}
            except spm.NotFound as e:
                return 404, {"error": str(e)}, {}

        with tempfile.TemporaryDirectory() as tmp, no_proxy(), http_server(respond) as url, \
                mock.patch.object(logging, "basicConfig"):
            config = os.path.join(tmp, "parties.json")
            with open(config, "w") as f:
                json.dump(ONLY_ALICE, f)
            argv = [
                "--scan-url", url, "--db", os.path.join(tmp, "monitor.sqlite"), "--config", config,
                "--slack-webhook-url", url + "/slack", "--exit-when-caught-up",
                "--acs-requests-per-second", "0", "--updates-requests-per-second", "0",
            ]
            out = io.StringIO()
            with contextlib.redirect_stdout(out), captured_logs():
                self.assertEqual(spm.main(argv), 0)
            self.assertEqual([json.loads(line)["update_id"] for line in out.getvalue().splitlines()], ["u1", "u2"])
            self.assertEqual(len(posts), 1)

            with open(config, "w") as f:
                json.dump({"parties": {"alice": {}}}, f)
            with captured_logs() as logs:
                self.assertEqual(spm.main(argv), 1)
            self.assertIn("'alice' is not a party id", logs[-1])


if __name__ == "__main__":
    unittest.main()
