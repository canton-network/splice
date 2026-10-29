#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Unit tests for scan_party_monitor.py, mostly against an in-process fake of Scan.

Run from the repository root with

    python3 -m unittest discover -s scripts/scan-party-monitor -v

Unlike the script, the tests also need PyYAML (as in the nix dev shell), to check the fake
Scan's JSON against Scan's OpenAPI spec.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import logging
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, Iterable, Iterator, List, Optional, Tuple, Type
from unittest import mock

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import scan_party_monitor as spm  # noqa: E402
from scan_party_monitor import Json, Meta  # noqa: E402


def party(hint: str) -> spm.PartyId:
    return spm._check_party_id(f"{hint}::1220{hashlib.sha256(hint.encode()).hexdigest()}", "test party")


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

PACKAGE_NAME = "splice-amulet"
SYNCHRONIZER = "global-domain::1220"

SNAPSHOT = "2026-01-02T00:00:00Z"


def at(second: int) -> str:
    """A record time `second` seconds after SNAPSHOT."""
    assert 0 <= second <= 59, f"{second} is not a second of a minute"
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
        "package_name": PACKAGE_NAME,
        "signatories": list(signatories),
        "observers": list(observers),
        "create_arguments": arguments or {},
        "created_at": "2026-01-01T00:00:00Z",
    }
    if event_id is None:
        event["created_in_update_id"] = f"{cid}-creation"
    else:
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
    """A self-managed TransferPreapproval, i.e. its provider is the receiver."""
    arguments = {
        "dso": DSO,
        "receiver": receiver,
        "provider": receiver,
        "validFrom": "2026-01-01T00:00:00Z",
        "lastRenewedAt": "2026-01-01T00:00:00Z",
        "expiresAt": "2027-01-01T00:00:00Z",
    }
    return created(cid, PREAPPROVAL, [receiver, DSO], arguments)


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
        "package_name": PACKAGE_NAME,
        "choice": choice,
        "choice_argument": {},
        "exercise_result": {},
        "acting_parties": acting,
        "consuming": consuming,
        "child_event_ids": list(children),
    }


def transaction(update_id: str, record_time: str, *events: Json) -> Json:
    """A transaction whose only root is the first event."""
    return {
        "update_id": update_id,
        "migration_id": 0,
        "workflow_id": "",
        "record_time": record_time,
        "synchronizer_id": SYNCHRONIZER,
        "effective_at": record_time,
        "root_event_ids": [events[0]["event_id"]],
        "events_by_id": {e["event_id"]: e for e in events},
    }


def assignment(update_id: str, record_time: str, created_event: Json) -> Json:
    return {
        "update_id": update_id,
        "offset": "1",
        "record_time": record_time,
        "event": {
            "submitter": DSO,
            "source_synchronizer": "other-domain::1220",
            "target_synchronizer": SYNCHRONIZER,
            "migration_id": 0,
            "unassign_id": f"{update_id}-unassign",
            "created_event": created_event,
            "reassignment_counter": 1,
        },
    }


def verdict(
    update_id: str,
    record_time: str,
    confirming: Iterable[Iterable[str]] = (),
    submitting: Iterable[str] = (),
    result: str = "VERDICT_RESULT_ACCEPTED",
) -> Json:
    """A mediator verdict with one view per element of `confirming`, which that view's parties
    confirm."""
    views = [
        {
            "view_id": i,
            "informees": list(parties),
            "confirming_parties": [{"parties": list(parties), "threshold": len(list(parties))}],
            "sub_views": [],
            "view_hash": "",
        }
        for i, parties in enumerate(confirming)
    ]
    return {
        "update_id": update_id,
        "migration_id": 0,
        "domain_id": "global-domain::1220",
        "record_time": record_time,
        "finalization_time": record_time,
        "submitting_parties": list(submitting),
        "submitting_participant_uid": "participant::1220",
        "verdict_result": result,
        "mediator_group": 0,
        "transaction_views": {"views": views, "root_views": list(range(len(views)))},
    }


def event(update: Optional[Json] = None, verdict: Optional[Json] = None) -> Json:
    """An item of Scan's event stream."""
    return {"update": update, "verdict": verdict, "traffic_summary": None, "app_activity_records": None}


def with_verdict(update: Json, confirming: Iterable[Iterable[str]] = (), submitting: Iterable[str] = ()) -> Json:
    """An event of a transaction and its accepted verdict."""
    return event(update, verdict(update["update_id"], update["record_time"], confirming, submitting))


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
    """The event of an AmuletRules_Transfer of one holding of `sender`, optionally through a
    preapproval and with change for the sender. The sender submits it and confirms it with
    the DSO party; with a preapproval, the receiver confirms too."""
    u = update_id
    send = (
        [exercised(f"{u}:send", preapproval_cid, PREAPPROVAL, "TransferPreapproval_Send", [sender], False,
                   [f"{u}:transfer"])]
        if preapproval_cid
        else []
    )
    output = amulet(f"{u}-output", receiver, amount, f"{u}:output")
    change = [amulet(change_cid, sender, "1.0", f"{u}:change")] if change_cid else []
    confirming = [[DSO, sender]] + ([[receiver]] if preapproval_cid else [])
    update = transaction(
        update_id,
        record_time,
        *send,
        exercised(f"{u}:transfer", "amulet-rules", AMULET_RULES, "AmuletRules_Transfer", [sender], False,
                  [f"{u}:archive", *(e["event_id"] for e in [output, *change])]),
        exercised(f"{u}:archive", input_cid, AMULET, "Archive", [DSO, sender], True),
        output,
        *change,
    )
    return with_verdict(update, confirming, [sender])


ACS = [
    created("observed", OTHER, [DSO], observers=[ALICE]),
    amulet("alice-1", ALICE, "100.0"),
    amulet("alice-2", ALICE, "100.0"),
    amulet("charlie-1", CHARLIE, "50.0"),
    preapproval("bob-preapproval", BOB),
    preapproval("charlie-preapproval", CHARLIE),
    locked_amulet("alice-locked", ALICE, [CHARLIE], "20.0"),
]


def parse_item(item: Json) -> spm.EventHistoryItem:
    return spm.EventHistoryItem.from_json(spm.JsonObject(item))


def holding(contract: Json) -> Optional[spm.Holding]:
    return spm.Contract.from_json(spm.JsonObject(contract)).holding


def process(monitor: spm.Monitor, item: Json) -> Optional[Json]:
    """The match of an event history item, as printed to stdout."""
    match = monitor.process_event_history_item(parse_item(item))
    return None if match is None else match.to_json()


def summary(match: Optional[Json]) -> Dict[str, List[Tuple[str, str]]]:
    """party -> sorted (reason, receiver or "-")"""
    if match is None:
        return {}
    return {p: sorted((r["reason"], r.get("receiver") or "-") for r in rs) for p, rs in match["parties"].items()}


### fakes


FAKE_BACKOFF_SECONDS = 30


class FakeScan(spm.ScanClient):
    """Scan with a single ACS snapshot at SNAPSHOT of migration 0, and the given events after it."""

    def __init__(self, acs: List[Json], events: List[Json]) -> None:
        super().__init__("https://scan.example.com", 0, 0)
        self.acs = list(acs)
        self.events = list(events)
        self.acs_requests: List[Json] = []
        self.snapshot_queries: List[Dict[str, Any]] = []
        self.events_requests: List[Json] = []
        # Raise KeyboardInterrupt on this ACS or events request (1-based), as if Ctrl-C was
        # pressed.
        self.interrupt_acs_request: Optional[int] = None
        self.interrupt_events_request: Optional[int] = None
        # Events request (1-based) -> how many attempts fail before it is answered. Each failure
        # advances `clock` by FAKE_BACKOFF_SECONDS, as ScanClient's backoff would.
        self.failed_events_attempts: Dict[int, int] = {}
        self.clock = 0.0

    def _call(
        self,
        pacer: spm.Pacer,
        method: str,
        path: str,
        body: Optional[Json] = None,
        query: Optional[Dict[str, Any]] = None,
        on_retry: Callable[[], None] = lambda: None,
    ) -> Json:
        if path == "/v0/events":
            for _ in range(self.failed_events_attempts.get(len(self.events_requests) + 1, 0)):
                self.clock += FAKE_BACKOFF_SECONDS
                on_retry()
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
        if (method, path) == ("POST", "/v2/holdings/state"):
            assert body is not None
            self.acs_requests.append(body)
            if self.interrupt_acs_request == len(self.acs_requests):
                raise KeyboardInterrupt()
            if body["migration_id"] != 0 or body["record_time"] != SNAPSHOT:
                raise spm.NotFound("no such snapshot")
            matching = [c for c in self.acs if (h := holding(c)) is not None and h.owner in body["owner_party_ids"]]
            start = int(body.get("after", 0))
            end = start + body["page_size"]
            page: Json = {"record_time": SNAPSHOT, "migration_id": 0, "created_events": matching[start:end]}
            if end < len(matching):
                page["next_page_token"] = str(end)
            return page
        if (method, path) == ("POST", "/v0/events"):
            assert body is not None
            self.events_requests.append(body)
            if self.interrupt_events_request == len(self.events_requests):
                raise KeyboardInterrupt()
            after = spm.position(body["after"]["after_migration_id"], body["after"]["after_record_time"])
            later = [e for e in self.events if parse_item(e).position > after]
            return {"events": later[: body["page_size"]]}
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
    and yields its base URL. A larger Content-Length in `headers` truncates the response."""

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
            for k, v in {"Content-Type": "application/json", "Content-Length": str(len(data)), **headers}.items():
                self.send_header(k, v)
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


# Headers for http_server that cut the response body short, as when the connection drops.
TRUNCATED = {"Content-Length": "1000"}


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
        self.assertEqual(holding(amulet("a", ALICE, "1.5")), spm.Holding(ALICE, "1.5"))
        self.assertEqual(holding(locked_amulet("l", ALICE, [CHARLIE], "2.5")), spm.Holding(ALICE, "2.5"))
        self.assertIsNone(holding(preapproval("p", BOB)))
        self.assertIsNone(holding(created("c", AMULET, [DSO, ALICE])))

    def test_timestamp_argument(self) -> None:
        self.assertEqual(spm.timestamp("2026-10-26T12:00:00Z"), "2026-10-26T12:00:00Z")
        for value in ["2026-10-26T12:00:00", "yesterday"]:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                spm.timestamp(value)

    def test_deprecated_updates_options(self) -> None:
        with mock.patch("sys.stderr", new_callable=io.StringIO) as stderr:
            args = spm.parse_cli_args(
                ["--scan-url", "http://scan", "--config", "c.json", "--db", "d.db"]
                + ["--updates-page-size", "5", "--updates-requests-per-second", "0.5"]
            )
        self.assertEqual((args.events_page_size, args.events_requests_per_second), (5, 0.5))
        self.assertIn("--updates-page-size is deprecated; use --events-page-size", stderr.getvalue())
        self.assertIn("--updates-requests-per-second is deprecated; use --events-requests-per-second", stderr.getvalue())
        defaults = spm.parse_cli_args(["--scan-url", "http://scan", "--config", "c.json", "--db", "d.db"])
        self.assertEqual((defaults.events_page_size, defaults.events_requests_per_second), (spm.MAX_PAGE_SIZE, 2.0))


### the fake Scan against Scan's OpenAPI spec

SCAN_YAML = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../apps/scan/src/main/openapi/scan.yaml")

# Optional keys that Scan leaves out of some responses, and that the fake leaves out too.
MAY_BE_ABSENT = {
    ("AcsResponseV2", "next_page_token"),  # on the last page
    ("ExercisedEvent", "interface_id"),  # for choices that are not inherited from an interface
    ("UpdateHistoryTransactionV2", "external_transaction_hash"),  # for transactions not signed externally
}

JSON_TYPES: Dict[str, Tuple[Type[Any], ...]] = {
    "object": (dict,),
    "array": (list,),
    "string": (str,),
    "integer": (int,),
    "number": (int, float),
    "boolean": (bool,),
}


class OpenApiSpec:
    def __init__(self, path: str) -> None:
        with open(path) as f:
            self.spec: Json = yaml.safe_load(f)

    def response_schema(self, method: str, path: str) -> Json:
        response: Json = self.spec["paths"][path][method.lower()]["responses"]["200"]
        return {**response["content"]["application/json"]["schema"]}

    def problems(self, value: Any, schema: Json, where: str = "$", name: str = "") -> List[str]:
        """How `value` differs from `schema`: missing or unknown keys, and wrong types."""
        nullable = schema.get("nullable", False)
        while "$ref" in schema:
            name = schema["$ref"].rsplit("/", 1)[-1]
            schema = self.spec["components"]["schemas"][name]
            nullable = nullable or schema.get("nullable", False)
        if value is None:
            return [] if nullable else [f"{where}: null"]
        if "allOf" in schema:
            parts = [self.resolved(s) for s in schema["allOf"]]
            merged = {k: v for part in parts for k, v in part.get("properties", {}).items()}
            return self.problems(value, {"type": "object", "properties": merged}, where, name)
        if "oneOf" in schema:
            # The closest alternative, e.g. a created rather than an exercised event.
            return min((self.problems(value, s, where, name) for s in schema["oneOf"]), key=len)
        expected = schema.get("type")
        if expected is not None and (
            not isinstance(value, JSON_TYPES[expected]) or (isinstance(value, bool) and expected != "boolean")
        ):
            return [f"{where}: {value!r} is not of type {expected}"]
        if "enum" in schema and value not in schema["enum"]:
            return [f"{where}: {value!r} is not one of {schema['enum']}"]
        if isinstance(value, list):
            return [p for i, v in enumerate(value) for p in self.problems(v, schema.get("items", {}), f"{where}[{i}]")]
        if not isinstance(value, dict):
            return []
        if isinstance(schema.get("additionalProperties"), dict):
            return [p for k, v in value.items() for p in self.problems(v, schema["additionalProperties"], f"{where}.{k}")]
        properties: Json = schema.get("properties", {})
        if not properties:
            return []  # e.g. Daml values
        missing = [f"{where}: missing {name}.{k}" for k in properties if k not in value and (name, k) not in MAY_BE_ABSENT]
        unknown = [f"{where}: {k} is not in {name}" for k in value if k not in properties]
        nested = [p for k, v in value.items() if k in properties for p in self.problems(v, properties[k], f"{where}.{k}")]
        return missing + unknown + nested

    def resolved(self, schema: Json) -> Json:
        while "$ref" in schema:
            schema = self.spec["components"]["schemas"][schema["$ref"].rsplit("/", 1)[-1]]
        return schema


class ScanSpecTest(unittest.TestCase):
    """The fake Scan's JSON has every key of Scan's OpenAPI spec, and no others."""

    spec: OpenApiSpec

    @classmethod
    def setUpClass(cls) -> None:
        cls.spec = OpenApiSpec(SCAN_YAML)

    def assertMatchesSpec(self, value: Any, schema: Json) -> None:
        self.assertEqual(self.spec.problems(value, schema), [])

    def test_checker_finds_differences(self) -> None:
        schema = {"$ref": "#/components/schemas/Quorum"}
        self.assertEqual(self.spec.problems({"parties": ["p"], "threshold": 1}, schema), [])
        self.assertEqual(
            self.spec.problems({"parties": "p", "weight": 1}, schema),
            ["$: missing Quorum.threshold", "$: weight is not in Quorum", "$.parties: 'p' is not of type array"],
        )

    def test_acs_contracts(self) -> None:
        for contract in ACS:
            with self.subTest(contract_id=contract["contract_id"]):
                self.assertMatchesSpec(contract, {"$ref": "#/components/schemas/ActiveContract"})

    def test_fake_scan_responses(self) -> None:
        events = [
            transfer("u1", at(1), ALICE, "alice-1", BOB, "10.0", "alice-change", "bob-preapproval"),
            with_verdict(
                transaction(
                    "u2",
                    at(2),
                    exercised("u2:0", "alice-locked", LOCKED_AMULET, "LockedAmulet_Unlock", [CHARLIE], True, ["u2:1"]),
                    amulet("bob-2", BOB, "20.0", "u2:1"),
                ),
                [[DSO, ALICE, CHARLIE]],
            ),
            event(verdict=verdict("u3", at(3), [[ALICE, BOB]], [BOB])),
            event(verdict=verdict("u4", at(4), [[ALICE]], [ALICE], "VERDICT_RESULT_REJECTED")),
            event(assignment("r5", at(5), amulet("moved", ALICE, "7.0", "r5:0"))),
        ]
        scan = FakeScan(ACS, events)
        holdings = {"migration_id": 0, "record_time": SNAPSHOT, "owner_party_ids": [ALICE, CHARLIE], "page_size": 2}
        requests: List[Tuple[str, str, Optional[Json], Optional[Dict[str, Any]]]] = [
            ("GET", "/v0/migrations/last", None, None),
            ("GET", "/v0/state/acs/snapshot-timestamp", None, {"before": at(1), "migration_id": 0}),
            ("POST", "/v2/holdings/state", holdings, None),  # a page with a next page
            ("POST", "/v2/holdings/state", {**holdings, "after": "2"}, None),  # the last page
            ("POST", "/v0/events", {"after": {"after_migration_id": 0, "after_record_time": SNAPSHOT}, "page_size": 10}, None),
        ]
        for method, path, body, query in requests:
            with self.subTest(method=method, path=path, body=body):
                response = scan.handle(method, path, body, query)
                self.assertMatchesSpec(response, self.spec.response_schema(method, path))
        self.assertEqual(len(scan.handle(*requests[-1][:2], requests[-1][2], None)["events"]), len(events))


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
            ({"parties": {"alice::1220abcd": {}}}, "at least 68 lowercase hex digits"),
            ({"parties": {ALICE + "x": {}}}, "is not a party id"),
            # Party ids compare case-sensitively, and namespaces are lowercase.
            ({"parties": {ALICE.upper(): {}}}, "is not a party id"),
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


class ParseTest(unittest.TestCase):
    def test_transaction_events_are_in_execution_order(self) -> None:
        update = transaction(
            "u1",
            at(1),
            exercised("u1:0", "c0", OTHER, "Root", [ALICE], False, ["u1:1", "u1:3"]),
            exercised("u1:1", "c1", OTHER, "Child", [ALICE], True, ["u1:2", "u1:missing"]),
            amulet("a2", ALICE, "1.0", "u1:2"),
            amulet("a3", BOB, "2.0", "u1:3"),
        )
        item = parse_item(event(update))
        assert isinstance(item.update, spm.Transaction)
        self.assertEqual([e.event_id for e in item.update.events_in_order], ["u1:0", "u1:1", "u1:2", "u1:3"])
        self.assertEqual(item.update.events_in_order[1], spm.ExercisedEvent("u1:1", "c1", True))
        created_event = item.update.events_in_order[2]
        assert isinstance(created_event, spm.CreatedEvent)
        self.assertEqual(created_event.contract.holding, spm.Holding(ALICE, "1.0"))
        self.assertEqual((item.update_id, item.position, item.private), ("u1", spm.position(0, at(1)), False))

    def test_reassignments(self) -> None:
        assigned = parse_item(event(assignment("r1", at(1), amulet("moved", ALICE, "7.0", "r1:0"))))
        assert isinstance(assigned.update, spm.Reassignment) and assigned.update.created is not None
        self.assertEqual(assigned.update.created.contract.contract_id, "moved")
        unassignment = assignment("r2", at(2), amulet("moved", ALICE, "7.0", "r2:0"))
        unassignment["event"] = {**unassignment["event"], "contract_id": "moved"}
        del unassignment["event"]["created_event"]
        unassigned = parse_item(event(unassignment))
        self.assertEqual(unassigned.update, spm.Reassignment("r2", 0, at(2), None))

    def test_verdict_only(self) -> None:
        item = parse_item(event(verdict=verdict("u1", at(1), [[ALICE, BOB], [BOB]])))
        self.assertIsNone(item.update)
        assert item.verdict is not None
        self.assertEqual(
            item.verdict.views, [spm.View(0, frozenset({ALICE, BOB})), spm.View(1, frozenset({BOB}))]
        )
        self.assertEqual((item.update_id, item.record_time, item.private), ("u1", at(1), True))
        rejected = parse_item(event(verdict=verdict("u2", at(2), [[ALICE]], result="VERDICT_RESULT_REJECTED")))
        self.assertFalse(rejected.private)

    def test_unexpected_json_is_fatal(self) -> None:
        bad_time = transfer("u1", at(1), ALICE, "alice-1", BOB, "1.0")
        bad_time["update"]["record_time"] = 5
        bad_event_type = with_verdict(transaction("u2", at(2), amulet("a", ALICE, "1.0", "u2:0")))
        bad_event_type["update"]["events_by_id"]["u2:0"]["event_type"] = "archived_event"
        for item, message in [
            (event(), "$ has neither an update nor a verdict"),
            (bad_time, "$.update.record_time is not a string: 5"),
            (bad_event_type, "$.update.events_by_id.u2:0.event_type is not created_event or exercised_event"),
            ({"update": None, "verdict": {"update_id": "u3"}}, "$.verdict lacks 'migration_id'"),
        ]:
            with self.subTest(message=message), self.assertRaises(spm.FatalError) as raised:
                parse_item(item)
            self.assertIn(message, str(raised.exception))


class SlackFormatTest(unittest.TestCase):
    @staticmethod
    def match(
        update_id: str,
        record_time: str,
        parties: Dict[spm.PartyId, List[spm.Reason]],
        verdict_result: Optional[str] = "VERDICT_RESULT_ACCEPTED",
        private: bool = False,
    ) -> spm.Match:
        return spm.Match(update_id, 0, record_time, "https://scan.example.com", verdict_result, private, parties)

    TRANSFER = spm.TransferReason("u:1", AMULET, CHARLIE, "10.0")
    CONFIRMING = spm.ConfirmingReason([0])

    def test_single_match(self) -> None:
        text = spm.format_slack_message([self.match("u1", at(1), {ALICE: [self.TRANSFER, self.CONFIRMING]})])
        self.assertEqual(
            text.splitlines(),
            [
                ":rotating_light: Alert for tracked party on https://scan.example.com",
                f"update `u1` at record time `{at(1)}` (migration 0)",
                f"• `{ALICE}`: transfer of 10.0 to `{CHARLIE}` (not an expected recipient)",
                f"• `{ALICE}`: confirming party",
            ],
        )

    def test_private_and_rejected_transactions_are_marked(self) -> None:
        private = self.match("u1", at(1), {ALICE: [self.CONFIRMING]}, private=True)
        rejected = self.match("u2", at(2), {ALICE: [self.CONFIRMING]}, "VERDICT_RESULT_REJECTED")
        self.assertIn(f"update `u1` at record time `{at(1)}` (migration 0, private)", spm.format_slack_message([private]))
        self.assertIn(f"update `u2` at record time `{at(2)}` (migration 0, rejected)", spm.format_slack_message([rejected]))
        lines = spm.format_slack_message([private, rejected]).splitlines()
        self.assertEqual(
            lines[1:],
            [
                f"• `u1` at `{at(1)}` (private): `{ALICE}`: confirming party",
                f"• `u2` at `{at(2)}` (rejected): `{ALICE}`: confirming party",
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

    def test_holdings_persist(self) -> None:
        store = self.open()
        store.put_holding("h1", ALICE)
        store.put_holding("h2", BOB)
        reopened = self.open()
        self.assertEqual(reopened.holding_owner_id("h1"), reopened.party_id(ALICE))
        self.assertEqual(reopened.holding_owner_id("h2"), reopened.party_id(BOB))
        reopened.delete_holding("h1")
        self.assertIsNone(reopened.holding_owner_id("h1"))
        reopened.clear_holdings()
        self.assertIsNone(reopened.holding_owner_id("h2"))

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
        store.put_holding("h1", ALICE)
        store.rollback()
        self.assertIsNone(store.holding_owner_id("h1"))
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
        self.slack_configured = True
        self.logs: List[str] = []

    def monitor(self, config: Json, *flags: str) -> spm.Monitor:
        args = spm.parse_cli_args(
            ["--scan-url", self.scan.url, "--db", self.db, "--config", "-", "--exit-when-caught-up", *flags]
        )
        slack = self.slack if self.slack_configured else None
        return spm.Monitor(args, self.scan, spm.Store(self.db), slack, spm.parse_config(config))

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
        """The committed value of `key`, read with a fresh connection like the next run would."""
        with contextlib.closing(spm.Store(self.db)) as store:
            return store.get_meta(key)


ONLY_ALICE: Json = {"parties": {ALICE: {}}}


class SeedTest(MonitorTestCase):
    def test_stores_the_holdings_of_tracked_parties(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        self.assertEqual([r["owner_party_ids"] for r in self.scan.acs_requests], [[ALICE]])
        store = monitor.store
        alice = store.party_id(ALICE)
        self.assertEqual([store.holding_owner_id(c) for c in ["alice-1", "alice-2", "alice-locked"]], [alice] * 3)
        self.assertIsNone(store.holding_owner_id("charlie-1"))
        self.assertEqual(self.meta(Meta.ACS_HOLDINGS), "3")
        self.assertEqual(self.meta(Meta.ACS_COMPLETE), "1")
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), SNAPSHOT)

    def test_resumes_after_stop_after_acs_pages(self) -> None:
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1", "--stop-after-acs-pages", "2", raises=spm.StopRequested)
        self.assertLogged("Stopping after 2 ACS pages as requested")
        self.assertEqual((self.meta(Meta.ACS_PAGES), self.meta(Meta.ACS_HOLDINGS)), ("2", "2"))
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1")
        self.assertLogged("Resuming the seed")
        self.assertLogged("ACS seed complete: 3 holdings")
        self.assertEqual([r.get("after") for r in self.scan.acs_requests], [None, "1", "2"])

    def test_ctrl_c_stops_the_seed_after_the_last_saved_page(self) -> None:
        self.scan.interrupt_acs_request = 2
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1", raises=spm.StopRequested)
        self.assertLogged("Interrupted after 1 ACS pages; rerun to resume")
        self.assertEqual(self.meta(Meta.ACS_NEXT_PAGE_TOKEN), "1")
        self.scan.interrupt_acs_request = None
        self.run_monitor(ONLY_ALICE, "--acs-page-size", "1")
        self.assertLogged("ACS seed complete: 3 holdings")
        self.assertEqual([r.get("after") for r in self.scan.acs_requests], [None, "1", "1", "2"])

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
                item = transfer("u1", at(1), ALICE, "alice-1", BOB, "10.0", "alice-change", "bob-preapproval")
                self.assertIsNone(process(monitor, item))
                # The holdings follow the transaction.
                self.assertIsNone(monitor.store.holding_owner_id("alice-1"))
                self.assertIsNotNone(monitor.store.holding_owner_id("alice-change"))

    def test_unexpected_transfer_is_reported(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"additional_recipients": [BOB]}, CHARLIE: {}}})
        item = transfer("u1", at(1), ALICE, "alice-1", CHARLIE, "10.0", "alice-change", "charlie-preapproval")
        self.assertEqual(
            process(monitor, item),
            {
                "update_id": "u1",
                "migration_id": 0,
                "record_time": at(1),
                "scan_url": self.scan.url,
                "verdict_result": "VERDICT_RESULT_ACCEPTED",
                "parties": {
                    ALICE: [
                        {"reason": "transfer", "event_id": "u1:output", "template_id": AMULET, "receiver": CHARLIE, "amount": "10.0"}
                    ]
                },
            },
        )

    def test_transfer_of_an_untracked_party_is_not_reported(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        self.assertIsNone(process(monitor, transfer("u1", at(1), CHARLIE, "charlie-1", BOB, "10.0")))

    def test_alert_confirmations_reports_confirming(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"alert_confirmations": True}}})
        item = transfer("u1", at(1), ALICE, "alice-1", CHARLIE, "10.0", "alice-change", "charlie-preapproval")
        self.assertEqual(
            summary(process(monitor, item)),
            {ALICE: [("confirming", "-"), ("transfer", CHARLIE)]},
        )

    def test_confirming_parties_come_from_the_verdict_views(self) -> None:
        monitor = self.seeded({"parties": {BOB: {"alert_confirmations": True}, CHARLIE: {"alert_confirmations": True}}})
        item = event(verdict=verdict("u1", at(1), [[DSO, ALICE], [BOB, DSO], [CHARLIE, BOB]]))
        match = process(monitor, item)
        assert match is not None
        self.assertEqual(
            match["parties"],
            {BOB: [{"reason": "confirming", "views": [1, 2]}], CHARLIE: [{"reason": "confirming", "views": [2]}]},
        )

    def test_submitters_that_do_not_confirm_are_not_reported(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"alert_confirmations": True}}})
        self.assertIsNone(process(monitor, event(verdict=verdict("u1", at(1), [[DSO]], [ALICE]))))

    def test_informees_do_not_confirm(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"alert_confirmations": True}}})
        item = event(verdict=verdict("u1", at(1), [[DSO]]))
        item["verdict"]["transaction_views"]["views"][0]["informees"].append(ALICE)
        self.assertIsNone(process(monitor, item))

    def test_private_transactions_are_reported(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"alert_confirmations": True}}})
        self.assertEqual(
            process(monitor, event(verdict=verdict("u1", at(1), [[ALICE, BOB]], [BOB]))),
            {
                "update_id": "u1",
                "migration_id": 0,
                "record_time": at(1),
                "scan_url": self.scan.url,
                "verdict_result": "VERDICT_RESULT_ACCEPTED",
                "private": True,
                "parties": {ALICE: [{"reason": "confirming", "views": [0]}]},
            },
        )

    def test_rejected_transactions_are_reported(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {"alert_confirmations": True}}})
        match = process(monitor, event(verdict=verdict("u1", at(1), [[ALICE]], [ALICE], "VERDICT_RESULT_REJECTED")))
        assert match is not None
        self.assertEqual(match["verdict_result"], "VERDICT_RESULT_REJECTED")
        self.assertNotIn("private", match)
        self.assertEqual(summary(match), {ALICE: [("confirming", "-")]})

    def test_verdicts_are_only_reported_with_alert_confirmations(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        self.assertIsNone(process(monitor, event(verdict=verdict("u1", at(1), [[ALICE]], [ALICE]))))

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
        self.assertEqual(summary(process(monitor, with_verdict(update))), {ALICE: [("transfer", CHARLIE)]})

    def test_holdings_created_after_the_seed_are_tracked(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        received = transfer("u1", at(1), CHARLIE, "charlie-1", ALICE, "5.0")
        self.assertIsNone(process(monitor, received))
        self.assertEqual(monitor.store.holding_owner_id("u1-output"), monitor.store.party_id(ALICE))
        self.assertEqual(
            summary(process(monitor, transfer("u2", at(2), ALICE, "u1-output", BOB, "5.0"))),
            {ALICE: [("transfer", BOB)]},
        )

    def test_locked_amulet_sender_is_the_owner_not_the_lock_holder(self) -> None:
        monitor = self.seeded({"parties": {ALICE: {}, CHARLIE: {}}})
        update = transaction(
            "u1",
            at(1),
            exercised("u1:0", "alice-locked", LOCKED_AMULET, "LockedAmulet_Unlock", [CHARLIE], True, ["u1:1"]),
            amulet("bob-2", BOB, "20.0", "u1:1"),
        )
        self.assertEqual(summary(process(monitor, with_verdict(update))), {ALICE: [("transfer", BOB)]})

    def test_assigned_holdings_are_tracked(self) -> None:
        monitor = self.seeded(ONLY_ALICE)
        self.assertIsNone(process(monitor, event(assignment("r1", at(1), amulet("moved", ALICE, "7.0", "r1:0")))))
        item = transfer("u2", at(2), ALICE, "moved", CHARLIE, "7.0")
        self.assertEqual(summary(process(monitor, item)), {ALICE: [("transfer", CHARLIE)]})


def unexpected_transfers(n: int) -> List[Json]:
    """n transfers alice -> charlie, at(1) .. at(n), each spending the previous one's change."""
    changes = [f"alice-change-{i}" for i in range(1, n + 1)]
    inputs = ["alice-1", *changes[:-1]]
    return [
        transfer(f"u{i}", at(i), ALICE, input_cid, CHARLIE, "1.0", change_cid, "charlie-preapproval")
        for i, (input_cid, change_cid) in enumerate(zip(inputs, changes), start=1)
    ]


class StreamTest(MonitorTestCase):
    def test_reports_once_and_resumes(self) -> None:
        self.scan.events = [
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
                self.scan.events = unexpected_transfers(3)
                matches = self.run_monitor(
                    ONLY_ALICE, "--events-page-size", "1", "--slack-batch-seconds", batch_seconds
                )
                self.assertEqual(len(matches), 3)
                self.assertEqual(len(self.slack.posts), posts)
                if posts == 1:
                    self.assertTrue(self.slack.posts[0].startswith(":rotating_light: 3 alerts"))

    def test_stop_at_record_time(self) -> None:
        self.scan.events = unexpected_transfers(3)
        matches = self.run_monitor(ONLY_ALICE, "--stop-at-record-time", at(2))
        self.assertEqual([m["update_id"] for m in matches], ["u1", "u2"])
        self.assertLogged(f"Reached --stop-at-record-time {at(2)}; exiting as requested")
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), at(2))
        self.assertEqual([m["update_id"] for m in self.run_monitor(ONLY_ALICE)], ["u3"])

    def test_stop_at_the_record_time_of_the_last_event(self) -> None:
        # No later event shows that the cutoff has passed, so this would poll until one comes,
        # but for --exit-when-caught-up.
        self.scan.events = unexpected_transfers(2)
        self.assertEqual(len(self.run_monitor(ONLY_ALICE, "--stop-at-record-time", at(2))), 2)
        self.assertLogged(f"Reached --stop-at-record-time {at(2)}; exiting as requested")
        self.assertNotLogged("Caught up with scan")

    def test_alerts_are_repeated_if_slack_fails(self) -> None:
        self.scan.events = unexpected_transfers(1)
        self.slack.fail = True
        self.assertEqual(len(self.run_monitor(ONLY_ALICE, raises=spm.FatalError)), 1)
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), SNAPSHOT)
        self.slack.fail = False
        self.assertEqual(len(self.run_monitor(ONLY_ALICE)), 1)
        self.assertEqual(len(self.slack.posts), 1)
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), at(1))

    def test_a_kill_keeps_the_pages_without_alerts(self) -> None:
        self.scan.events = [
            with_verdict(transaction(f"o{i}", at(i), created(f"other-{i}", OTHER, [DSO], event_id=f"o{i}:0")), [[DSO]])
            for i in range(1, 4)
        ]
        self.scan.interrupt_events_request = 3
        self.assertEqual(self.run_monitor(ONLY_ALICE, "--events-page-size", "1", raises=KeyboardInterrupt), [])
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), at(2))
        self.scan.interrupt_events_request = None
        self.scan.events_requests = []
        self.run_monitor(ONLY_ALICE, "--events-page-size", "1")
        self.assertEqual(self.scan.events_requests[0]["after"]["after_record_time"], at(2))

    def test_a_kill_repeats_only_the_alerts_not_yet_posted_to_slack(self) -> None:
        # Without Slack there is nothing to batch, so every page is kept.
        for slack_configured, cursor, repeated in [(True, SNAPSHOT, ["u1", "u2", "u3"]), (False, at(2), ["u3"])]:
            with self.subTest(slack_configured=slack_configured):
                self.setUp()
                self.slack_configured = slack_configured
                self.scan.events = unexpected_transfers(3)
                self.scan.interrupt_events_request = 3
                matches = self.run_monitor(ONLY_ALICE, "--events-page-size", "1", raises=KeyboardInterrupt)
                self.assertEqual([m["update_id"] for m in matches], ["u1", "u2"])
                self.assertEqual(self.slack.posts, [])
                self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), cursor)
                self.scan.interrupt_events_request = None
                self.assertEqual([m["update_id"] for m in self.run_monitor(ONLY_ALICE)], repeated)
                self.assertEqual(len(self.slack.posts), 1 if slack_configured else 0)

    def test_alerts_are_posted_while_scan_requests_are_retried(self) -> None:
        self.scan.events = unexpected_transfers(2)
        # u1 is due on Slack during the second backoff; then the run is killed.
        self.scan.failed_events_attempts = {2: 2}
        self.scan.interrupt_events_request = 2
        with mock.patch("scan_party_monitor.time.monotonic", lambda: self.scan.clock):
            self.run_monitor(ONLY_ALICE, "--events-page-size", "1", raises=KeyboardInterrupt)
        self.assertEqual(len(self.slack.posts), 1)
        self.assertIn("`u1`", self.slack.posts[0])
        self.assertEqual(self.meta(Meta.CURSOR_RECORD_TIME), at(1))


class ReconfigurationTest(MonitorTestCase):
    def test_adding_a_party_reseeds_without_repeating_alerts(self) -> None:
        self.scan.events = unexpected_transfers(1)
        self.assertEqual(summary(self.run_monitor(ONLY_ALICE)[0]), {ALICE: [("transfer", CHARLIE)]})

        config: Json = {"parties": {ALICE: {}, CHARLIE: {"alert_confirmations": True}}}
        matches = self.run_monitor(config)
        self.assertLogged(f"Tracked parties changed (added: {CHARLIE}); re-seeding the holdings")
        self.assertEqual(self.scan.snapshot_queries[-1], {"before": at(1), "migration_id": 0})
        self.assertEqual(self.scan.acs_requests[-1]["owner_party_ids"], [ALICE, CHARLIE])
        # u1 is replayed for charlie, but alice's alert is not repeated.
        self.assertEqual([summary(m) for m in matches], [{CHARLIE: [("confirming", "-")]}])
        self.assertEqual(self.meta(Meta.REPORTED_UNTIL), "{}")

        # The re-seed stored charlie's holding from before u1.
        self.scan.events.append(transfer("u2", at(2), CHARLIE, "charlie-1", BOB, "3.0"))
        matches = self.run_monitor(config)
        self.assertNotLogged("re-seeding")
        self.assertEqual(
            [summary(m) for m in matches],
            [{CHARLIE: [("confirming", "-"), ("transfer", BOB)]}],
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
        self.assertLogged(f"Tracked parties changed (added: {CHARLIE}); re-seeding the holdings")
        self.assertNotLogged("Resuming the seed")
        restarted = self.scan.acs_requests[1]
        self.assertEqual((restarted["owner_party_ids"], restarted.get("after")), ([ALICE, CHARLIE], None))
        self.assertLogged("ACS seed complete: 4 holdings")


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
        self.responses = [
            (503, {"error": "unavailable"}, {}),
            (503, {"error": "unavailable"}, TRUNCATED),
            (429, {"error": "slow down"}, {}),
            (200, {"events": []}, TRUNCATED),
            (200, {"events": []}, {}),
        ]
        with no_proxy(), http_server(self.respond) as url, captured_logs() as logs:
            scan = spm.ScanClient(url, 0, 0)
            retries: List[int] = []
            self.assertEqual(scan.get_events(0, SNAPSHOT, 10, on_retry=lambda: retries.append(len(self.requests))), [])
        self.assertEqual(len(self.requests), 5)
        self.assertEqual(retries, [1, 2, 3, 4])
        self.assertTrue(any("IncompleteRead" in m for m in logs), logs)
        self.assertEqual(
            self.requests[-1],
            ("POST", "/api/scan/v0/events", {"after": {"after_migration_id": 0, "after_record_time": SNAPSHOT}, "page_size": 10}),
        )
        self.assertTrue(any("Rate limited by scan on /v0/events" in m for m in logs), logs)
        self.assertGreater(scan.events_pacer.penalty_until, 0)

    def test_scan_client_errors(self) -> None:
        self.responses = [(404, {"error": "not found"}, {}), (400, {"error": "bad request"}, {})]
        with no_proxy(), http_server(self.respond) as url:
            scan = spm.ScanClient(url, 0, 0)
            with self.assertRaises(spm.NotFound):
                scan.get_holdings_page(0, SNAPSHOT, [ALICE], 10, None)
            with self.assertRaisesRegex(spm.FatalError, "HTTP 400"):
                scan.get_holdings_page(0, SNAPSHOT, [ALICE], 10, "token")
        self.assertEqual(
            [(path, body) for _, path, body in self.requests],
            [
                ("/api/scan/v2/holdings/state", {"migration_id": 0, "record_time": SNAPSHOT, "owner_party_ids": [ALICE], "page_size": 10}),
                (
                    "/api/scan/v2/holdings/state",
                    {"migration_id": 0, "record_time": SNAPSHOT, "owner_party_ids": [ALICE], "page_size": 10, "after": "token"},
                ),
            ],
        )

    def test_slack_notifier(self) -> None:
        self.responses = [
            (500, {}, {}),
            (500, {}, TRUNCATED),
            (429, {}, {"Retry-After": "0"}),
            (200, {}, {}),
            (400, {"error": "invalid_payload"}, {}),
        ]
        with no_proxy(), http_server(self.respond) as url, captured_logs():
            slack = spm.SlackNotifier(url + "/hook")
            slack.post("hello")
            with self.assertRaisesRegex(spm.FatalError, "HTTP 400"):
                slack.post("rejected")
        self.assertEqual([body for _, _, body in self.requests], [{"text": "hello"}] * 4 + [{"text": "rejected"}])

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
                "--acs-requests-per-second", "0", "--events-requests-per-second", "0",
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
