#!/usr/bin/env python3

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates.
# All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import calendar
import datetime
import json
import os
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Optional

DEFAULT_GROUP_NAME = "Upcoming"

DATE_COLUMN_NAME = "Date/Time US EST"
STATUS_COLUMN_NAME = "Submission Status"
NETWORK_COLUMN_NAME = "Network"
ACTIVITY_COLUMN_NAME = "Type of Activity"
VERSION_COLUMN_NAME = "Minor Versions"

INITIAL_STATUS = "To Be Confirmed"

NETWORK_DEVNET = "DevNet"
NETWORK_TESTNET = "TestNet"
NETWORK_MAINNET = "MainNet"

# These must match the labels that actually exist on the Monday board.
ACTIVITY_WEEKLY = "Weekly Upgrades"
ACTIVITY_DAML = "Splice Daml Model Effectivity"
ACTIVITY_LSU = "Protocol Upgrades (LSU)"
ACTIVITY_CONFIG = "Configuration Change"


@dataclass(frozen=True)
class ScheduledEvent:
    title: str
    date: datetime.date
    network: str
    activity: str
    minor_version: str
    time_utc: Optional[str] = None


_BOARD_CACHE: dict[int, dict] = {}
_ITEMS_CACHE: dict[int, dict[str, list[str]]] = {}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Create/update the monthly Splice release schedule "
            "in monday.com."
        )
    )

    parser.add_argument(
        "version",
        help="Minor version, e.g. 0.8",
    )

    parser.add_argument(
        "month",
        help=(
            "Month containing the first DevNet Monday, "
            "in YYYY-MM format, e.g. 2026-08"
        ),
    )

    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "Validate the Monday board and show what would change "
            "without writing anything."
        ),
    )

    args = parser.parse_args()

    if re.fullmatch(r"\d+\.\d+", args.version) is None:
        parser.error(
            "version must be in MAJOR.MINOR form, for example 0.8"
        )

    if re.fullmatch(
        r"\d{4}-(0[1-9]|1[0-2])",
        args.month,
    ) is None:
        parser.error(
            "month must be in YYYY-MM format"
        )

    return args

def first_monday_in_month(
    month: str,
) -> datetime.date:
    year, month_num = map(
        int,
        month.split("-"),
    )

    first_day = datetime.date(
        year,
        month_num,
        1,
    )

    offset = (
        0 - first_day.weekday()
    ) % 7

    return (
        first_day
        + datetime.timedelta(
            days=offset
        )
    )


def mondays_in_month(
    month: str,
) -> int:
    year, month_num = map(
        int,
        month.split("-"),
    )

    _, days_in_month = (
        calendar.monthrange(
            year,
            month_num,
        )
    )

    first_day = datetime.date(
        year,
        month_num,
        1,
    )

    first_monday_offset = (
        0 - first_day.weekday()
    ) % 7

    return (
        days_in_month
        - first_monday_offset
        + 6
    ) // 7


def schedule_date(
    month: str,
    weekday: str,
    week_number: int,
) -> datetime.date:
    weekdays = {
        "monday": 0,
        "tuesday": 1,
        "wednesday": 2,
        "thursday": 3,
        "friday": 4,
        "saturday": 5,
        "sunday": 6,
    }

    key = weekday.strip().lower()

    if key not in weekdays:
        raise ValueError(
            f"Unknown weekday: {weekday}"
        )

    if week_number < 0:
        raise ValueError(
            "week_number must be >= 0"
        )

    base_monday = (
        first_monday_in_month(month)
        + datetime.timedelta(
            weeks=week_number
        )
    )

    return (
        base_monday
        + datetime.timedelta(
            days=weekdays[key]
        )
    )


def required_env(
    name: str,
) -> str:
    value = os.getenv(name)

    if value is None or value.strip() == "":
        raise RuntimeError(
            f"Missing required environment variable: {name}"
        )

    return value.strip()

def board_id_from_env() -> int:
    value = required_env(
        "MONDAY_BOARD_ID"
    )

    try:
        return int(value)

    except ValueError as exc:
        raise RuntimeError(
            "MONDAY_BOARD_ID must be numeric; "
            f"got {value!r}"
        ) from exc

def monday_request(
    token: str,
    query: str,
    variables: dict,
) -> dict:
    payload = {
        "query": query,
        "variables": variables,
    }

    headers = {
        "Authorization": token,
        "Content-Type": "application/json",
    }

    api_version = os.getenv(
        "MONDAY_API_VERSION"
    )

    if api_version:
        headers["API-Version"] = (
            api_version
        )

    request = urllib.request.Request(
        "https://api.monday.com/v2",
        data=json.dumps(
            payload
        ).encode(
            "utf-8"
        ),
        headers=headers,
        method="POST",
    )

    try:
        with urllib.request.urlopen(
            request,
            timeout=30,
        ) as response:

            body = (
                response
                .read()
                .decode("utf-8")
            )

    except urllib.error.HTTPError as exc:

        response_body = (
            exc.read().decode(
                "utf-8",
                errors="replace",
            )
        )

        raise RuntimeError(
            f"Monday API request failed "
            f"({exc.code}): "
            f"{response_body}"
        ) from exc

    except urllib.error.URLError as exc:

        raise RuntimeError(
            f"Could not reach Monday API: {exc}"
        ) from exc

    parsed = json.loads(body)

    if parsed.get("errors"):

        raise RuntimeError(
            "Monday API returned errors: "
            + json.dumps(
                parsed["errors"],
                ensure_ascii=False,
            )
        )

    return parsed

def normalize_settings(
    raw_settings,
) -> dict:
    if raw_settings is None:
        return {}

    if isinstance(
        raw_settings,
        dict,
    ):
        return raw_settings

    if isinstance(
        raw_settings,
        str,
    ):
        raw_settings = (
            raw_settings.strip()
        )

        if not raw_settings:
            return {}

        try:
            parsed = json.loads(
                raw_settings
            )

            if isinstance(
                parsed,
                dict,
            ):
                return parsed

        except json.JSONDecodeError:
            pass

    return {}


def available_labels(
    column: dict,
) -> set[str]:
    settings = normalize_settings(
        column.get("settings")
    )

    raw_labels = settings.get(
        "labels",
        [],
    )

    labels: set[str] = set()

    if isinstance(
        raw_labels,
        list,
    ):
        for entry in raw_labels:

            if isinstance(
                entry,
                dict,
            ):
                label = entry.get(
                    "label"
                )

                if (
                    isinstance(
                        label,
                        str,
                    )
                    and label
                ):
                    labels.add(
                        label
                    )

            elif (
                isinstance(
                    entry,
                    str,
                )
                and entry
            ):
                labels.add(
                    entry
                )

    elif isinstance(
        raw_labels,
        dict,
    ):
        for value in (
            raw_labels.values()
        ):

            if (
                isinstance(
                    value,
                    str,
                )
                and value
            ):
                labels.add(
                    value
                )

            elif isinstance(
                value,
                dict,
            ):
                label = value.get(
                    "label"
                )

                if (
                    isinstance(
                        label,
                        str,
                    )
                    and label
                ):
                    labels.add(
                        label
                    )

    return labels


def get_board(
    token: str,
    board_id: int,
) -> dict:
    if board_id in _BOARD_CACHE:
        return _BOARD_CACHE[
            board_id
        ]

    query = """
    query BoardInfo($boardId: [ID!]!) {
      boards(ids: $boardId) {
        id
        name
        columns {
          id
          title
          type
          settings
        }
        groups {
          id
          title
          archived
          deleted
        }
      }
    }
    """

    response = monday_request(
        token,
        query,
        {
            "boardId": [
                board_id
            ]
        },
    )

    boards = (
        response
        .get(
            "data",
            {},
        )
        .get(
            "boards",
            [],
        )
    )

    if not boards:
        raise RuntimeError(
            f"Board not found or "
            f"not accessible: "
            f"{board_id}"
        )

    board = boards[0]

    _BOARD_CACHE[
        board_id
    ] = board

    return board


def get_column(
    board: dict,
    title: str,
) -> dict:
    target = (
        title
        .strip()
        .casefold()
    )

    matches = [
        column
        for column
        in board.get(
            "columns",
            [],
        )
        if (
            str(
                column.get(
                    "title",
                    "",
                )
            )
            .strip()
            .casefold()
            == target
        )
    ]

    if not matches:

        existing = ", ".join(
            sorted(
                str(
                    column.get(
                        "title",
                        "",
                    )
                )
                for column
                in board.get(
                    "columns",
                    [],
                )
            )
        )

        raise RuntimeError(
            f"Column {title!r} "
            f"not found. "
            f"Board columns are: "
            f"{existing}"
        )

    if len(matches) > 1:
        raise RuntimeError(
            f"More than one column "
            f"is named {title!r}. "
            f"Rename one so the "
            f"automation has an "
            f"unambiguous target."
        )

    return matches[0]


def get_group_id(
    board: dict,
    group_name: str,
) -> str:
    target = (
        group_name
        .strip()
        .casefold()
    )

    matches = [
        group
        for group
        in board.get(
            "groups",
            [],
        )
        if (
            not group.get(
                "archived"
            )
            and not group.get(
                "deleted"
            )
            and (
                str(
                    group.get(
                        "title",
                        "",
                    )
                )
                .strip()
                .casefold()
                == target
            )
        )
    ]

    if not matches:

        existing = ", ".join(
            str(
                group.get(
                    "title",
                    "",
                )
            )
            for group
            in board.get(
                "groups",
                [],
            )
            if (
                not group.get(
                    "archived"
                )
                and not group.get(
                    "deleted"
                )
            )
        )

        raise RuntimeError(
            f"Group {group_name!r} "
            f"not found. "
            f"Active groups are: "
            f"{existing}"
        )

    if len(matches) > 1:
        raise RuntimeError(
            f"More than one active "
            f"group is named "
            f"{group_name!r}."
        )

    return str(
        matches[0]["id"]
    )


def ensure_column_type(
    column: dict,
    allowed_types: set[str],
) -> None:
    actual = str(
        column.get(
            "type",
            "",
        )
    ).lower()

    if actual not in allowed_types:
        raise RuntimeError(
            f"Column "
            f"{column['title']!r} "
            f"has type "
            f"{actual!r}; "
            f"expected one of "
            f"{sorted(allowed_types)}"
        )


def require_label(
    column: dict,
    label: str,
) -> None:
    labels = available_labels(
        column
    )

    if not labels:
        return

    if label not in labels:
        raise RuntimeError(
            f"Column "
            f"{column['title']!r} "
            f"does not contain "
            f"label {label!r}. "
            f"Available labels: "
            f"{sorted(labels)}"
        )


def preflight(
    token: str,
    board_id: int,
    group_name: str,
) -> tuple[dict, str]:
    board = get_board(
        token,
        board_id,
    )

    date_col = get_column(
        board,
        DATE_COLUMN_NAME,
    )

    status_col = get_column(
        board,
        STATUS_COLUMN_NAME,
    )

    network_col = get_column(
        board,
        NETWORK_COLUMN_NAME,
    )

    activity_col = get_column(
        board,
        ACTIVITY_COLUMN_NAME,
    )

    version_col = get_column(
        board,
        VERSION_COLUMN_NAME,
    )

    ensure_column_type(
        date_col,
        {"date"},
    )

    ensure_column_type(
        status_col,
        {
            "status",
            "color",
        },
    )

    ensure_column_type(
        network_col,
        {
            "status",
            "color",
            "dropdown",
            "text",
        },
    )

    ensure_column_type(
        activity_col,
        {
            "status",
            "color",
            "dropdown",
            "text",
        },
    )

    ensure_column_type(
        version_col,
        {
            "dropdown",
            "status",
            "color",
            "text",
        },
    )

    require_label(
        status_col,
        INITIAL_STATUS,
    )

    for label in (
        NETWORK_DEVNET,
        NETWORK_TESTNET,
        NETWORK_MAINNET,
    ):
        require_label(
            network_col,
            label,
        )

    for label in (
        ACTIVITY_WEEKLY,
        ACTIVITY_DAML,
        ACTIVITY_LSU,
        ACTIVITY_CONFIG,
    ):
        require_label(
            activity_col,
            label,
        )

    group_id = get_group_id(
        board,
        group_name,
    )

    return (
        board,
        group_id,
    )

def choice_value(
    column: dict,
    label: str,
):
    column_type = str(
        column.get(
            "type",
            "",
        )
    ).lower()

    if column_type in {
        "status",
        "color",
    }:
        return {
            "label": label
        }

    if column_type == "dropdown":
        return {
            "labels": [
                label
            ]
        }

    if column_type in {
        "text",
        "long_text",
    }:
        return label

    raise RuntimeError(
        f"Unsupported choice "
        f"column type "
        f"{column_type!r} "
        f"for "
        f"{column['title']!r}"
    )


def date_value(
    event_date: datetime.date,
    time_utc: Optional[str],
) -> dict:
    value = {
        "date":
            event_date.isoformat()
    }

    if time_utc is not None:

        if re.fullmatch(
            r"(?:[01]\d|2[0-3]):[0-5]\d",
            time_utc,
        ) is None:

            raise ValueError(
                f"Invalid UTC time "
                f"{time_utc!r}; "
                f"expected HH:MM"
            )

        value["time"] = (
            f"{time_utc}:00"
        )

    return value


def build_column_values(
    board: dict,
    event: ScheduledEvent,
    include_submission_status: bool,
) -> dict:
    date_col = get_column(
        board,
        DATE_COLUMN_NAME,
    )

    network_col = get_column(
        board,
        NETWORK_COLUMN_NAME,
    )

    activity_col = get_column(
        board,
        ACTIVITY_COLUMN_NAME,
    )

    version_col = get_column(
        board,
        VERSION_COLUMN_NAME,
    )

    values = {
        str(
            date_col["id"]
        ):
            date_value(
                event.date,
                event.time_utc,
            ),

        str(
            network_col["id"]
        ):
            choice_value(
                network_col,
                event.network,
            ),

        str(
            activity_col["id"]
        ):
            choice_value(
                activity_col,
                event.activity,
            ),

        str(
            version_col["id"]
        ):
            choice_value(
                version_col,
                event.minor_version,
            ),
    }

    if include_submission_status:

        status_col = get_column(
            board,
            STATUS_COLUMN_NAME,
        )

        values[
            str(
                status_col["id"]
            )
        ] = choice_value(
            status_col,
            INITIAL_STATUS,
        )

    return values

def load_existing_items(
    token: str,
    board_id: int,
) -> dict[str, list[str]]:
    if board_id in _ITEMS_CACHE:
        return _ITEMS_CACHE[
            board_id
        ]

    items_by_name: dict[
        str,
        list[str],
    ] = {}

    first_query = """
    query BoardItems($boardId: [ID!]!) {
      boards(ids: $boardId) {
        items_page(limit: 500) {
          cursor
          items {
            id
            name
          }
        }
      }
    }
    """

    response = monday_request(
        token,
        first_query,
        {
            "boardId": [
                board_id
            ]
        },
    )

    boards = (
        response
        .get(
            "data",
            {},
        )
        .get(
            "boards",
            [],
        )
    )

    if not boards:
        raise RuntimeError(
            f"Board not found: "
            f"{board_id}"
        )

    page = (
        boards[0]
        .get(
            "items_page",
        )
        or {}
    )

    for item in page.get(
        "items",
        [],
    ):
        items_by_name.setdefault(
            str(
                item["name"]
            ),
            [],
        ).append(
            str(
                item["id"]
            )
        )

    cursor = page.get(
        "cursor"
    )

    next_query = """
    query MoreItems($cursor: String!) {
      next_items_page(cursor: $cursor) {
        cursor
        items {
          id
          name
        }
      }
    }
    """

    while cursor:

        response = monday_request(
            token,
            next_query,
            {
                "cursor": cursor
            },
        )

        page = (
            response
            .get(
                "data",
                {},
            )
            .get(
                "next_items_page",
            )
            or {}
        )

        for item in page.get(
            "items",
            [],
        ):
            items_by_name.setdefault(
                str(
                    item["name"]
                ),
                [],
            ).append(
                str(
                    item["id"]
                )
            )

        cursor = page.get(
            "cursor"
        )

    _ITEMS_CACHE[
        board_id
    ] = items_by_name

    return items_by_name


# =============================================================================
# CREATE / UPDATE
# =============================================================================

def create_item(
    token: str,
    board_id: int,
    group_id: str,
    board: dict,
    event: ScheduledEvent,
) -> str:
    mutation = """
    mutation CreateItem(
      $boardId: ID!,
      $groupId: String!,
      $itemName: String!,
      $columnValues: JSON!
    ) {
      create_item(
        board_id: $boardId,
        group_id: $groupId,
        item_name: $itemName,
        column_values: $columnValues,
        create_labels_if_missing: true
      ) {
        id
      }
    }
    """

    values = build_column_values(
        board,
        event,
        include_submission_status=True,
    )

    response = monday_request(
        token,
        mutation,
        {
            "boardId":
                board_id,

            "groupId":
                group_id,

            "itemName":
                event.title,

            "columnValues":
                json.dumps(
                    values
                ),
        },
    )

    return str(
        response[
            "data"
        ][
            "create_item"
        ][
            "id"
        ]
    )


def update_item(
    token: str,
    board_id: int,
    board: dict,
    item_id: str,
    event: ScheduledEvent,
) -> None:
    mutation = """
    mutation UpdateItem(
      $boardId: ID!,
      $itemId: ID!,
      $columnValues: JSON!
    ) {
      change_multiple_column_values(
        board_id: $boardId,
        item_id: $itemId,
        column_values: $columnValues,
        create_labels_if_missing: true
      ) {
        id
      }
    }
    """

    values = build_column_values(
        board,
        event,
        include_submission_status=False,
    )

    monday_request(
        token,
        mutation,
        {
            "boardId":
                board_id,

            "itemId":
                item_id,

            "columnValues":
                json.dumps(
                    values
                ),
        },
    )


def describe_event(
    event: ScheduledEvent,
) -> str:
    when = event.date.isoformat()

    if event.time_utc:
        when += (
            f" {event.time_utc} UTC"
        )

    return (
        f"{when} | "
        f"{event.network} | "
        f"{event.activity} | "
        f"{event.minor_version}"
    )


def upsert_event(
    token: str,
    board_id: int,
    group_id: str,
    board: dict,
    event: ScheduledEvent,
    dry_run: bool,
) -> None:
    items = load_existing_items(
        token,
        board_id,
    )

    matching_ids = items.get(
        event.title,
        [],
    )

    if len(matching_ids) > 1:
        raise RuntimeError(
            f"Cannot safely update "
            f"{event.title!r}: "
            f"multiple items already "
            f"have that exact name: "
            f"{matching_ids}"
        )

    details = describe_event(
        event
    )

    if len(matching_ids) == 1:

        item_id = (
            matching_ids[0]
        )

        if dry_run:

            print(
                f"WOULD UPDATE "
                f"{item_id}: "
                f"{event.title} "
                f"-> {details}"
            )

            return

        update_item(
            token,
            board_id,
            board,
            item_id,
            event,
        )

        print(
            f"UPDATED "
            f"{item_id}: "
            f"{event.title} "
            f"-> {details}"
        )

        return

    if dry_run:

        print(
            f"WOULD CREATE: "
            f"{event.title} "
            f"-> {details}"
        )

        return

    item_id = create_item(
        token,
        board_id,
        group_id,
        board,
        event,
    )

    items.setdefault(
        event.title,
        [],
    ).append(
        item_id
    )

    print(
        f"CREATED "
        f"{item_id}: "
        f"{event.title} "
        f"-> {details}"
    )

def make_schedule(
    version: str,
    month: str,
) -> list[ScheduledEvent]:
    events: list[
        ScheduledEvent
    ] = []

    for patch in range(
        mondays_in_month(
            month
        )
    ):
        events.append(
            ScheduledEvent(
                title=(
                    f"DevNet upgrades "
                    f"to Splice "
                    f"{version}.{patch}"
                ),

                date=schedule_date(
                    month,
                    "monday",
                    patch,
                ),

                network=(
                    NETWORK_DEVNET
                ),

                activity=(
                    ACTIVITY_WEEKLY
                ),

                minor_version=(
                    version
                ),
            )
        )

    events.extend(
        [
            ScheduledEvent(
                title=(
                    f"DevNet New Daml "
                    f"models effective "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    2,
                ),

                time_utc="12:00",

                network=(
                    NETWORK_DEVNET
                ),

                activity=(
                    ACTIVITY_DAML
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"DevNet: "
                    f"Topology Freeze "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    2,
                ),

                time_utc="13:00",

                network=(
                    NETWORK_DEVNET
                ),

                activity=(
                    ACTIVITY_LSU
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"DevNet LSU "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "wednesday",
                    2,
                ),

                time_utc="13:00",

                network=(
                    NETWORK_DEVNET
                ),

                activity=(
                    ACTIVITY_LSU
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"DevNet Breaking "
                    f"Config Changes "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    3,
                ),

                time_utc="12:00",

                network=(
                    NETWORK_DEVNET
                ),

                activity=(
                    ACTIVITY_CONFIG
                ),

                minor_version=(
                    version
                ),
            ),
        ]
    )

    for patch in range(
        mondays_in_month(
            month
        )
    ):
        events.append(
            ScheduledEvent(
                title=(
                    f"TestNet upgrades "
                    f"to Splice "
                    f"{version}.{patch}"
                ),

                date=schedule_date(
                    month,
                    "monday",
                    patch + 1,
                ),

                network=(
                    NETWORK_TESTNET
                ),

                activity=(
                    ACTIVITY_WEEKLY
                ),

                minor_version=(
                    version
                ),
            )
        )

    events.extend(
        [
            ScheduledEvent(
                title=(
                    f"TestNet New Daml "
                    f"models effective "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    3,
                ),

                time_utc="12:00",

                network=(
                    NETWORK_TESTNET
                ),

                activity=(
                    ACTIVITY_DAML
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"TestNet: "
                    f"Topology Freeze "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    3,
                ),

                time_utc="13:00",

                network=(
                    NETWORK_TESTNET
                ),

                activity=(
                    ACTIVITY_LSU
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"TestNet LSU "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "wednesday",
                    3,
                ),

                time_utc="13:00",

                network=(
                    NETWORK_TESTNET
                ),

                activity=(
                    ACTIVITY_LSU
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"TestNet Breaking "
                    f"Config Changes "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    4,
                ),

                time_utc="12:00",

                network=(
                    NETWORK_TESTNET
                ),

                activity=(
                    ACTIVITY_CONFIG
                ),

                minor_version=(
                    version
                ),
            ),
        ]
    )

    for patch in range(
        mondays_in_month(
            month
        )
    ):
        events.append(
            ScheduledEvent(
                title=(
                    f"MainNet upgrades "
                    f"to Splice "
                    f"{version}.{patch}"
                ),

                date=schedule_date(
                    month,
                    "monday",
                    patch + 2,
                ),

                network=(
                    NETWORK_MAINNET
                ),

                activity=(
                    ACTIVITY_WEEKLY
                ),

                minor_version=(
                    version
                ),
            )
        )

    events.extend(
        [
            ScheduledEvent(
                title=(
                    f"MainNet new Daml "
                    f"models effective "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    4,
                ),

                time_utc="12:00",

                network=(
                    NETWORK_MAINNET
                ),

                activity=(
                    ACTIVITY_DAML
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"MainNet "
                    f"Topology Freeze "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "friday",
                    4,
                ),

                time_utc="13:00",

                network=(
                    NETWORK_MAINNET
                ),

                activity=(
                    ACTIVITY_LSU
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"MainNet LSU "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "saturday",
                    4,
                ),

                time_utc="13:00",

                network=(
                    NETWORK_MAINNET
                ),

                activity=(
                    ACTIVITY_LSU
                ),

                minor_version=(
                    version
                ),
            ),

            ScheduledEvent(
                title=(
                    f"MainNet breaking "
                    f"config change "
                    f"({version})"
                ),

                date=schedule_date(
                    month,
                    "tuesday",
                    5,
                ),

                time_utc="12:00",

                network=(
                    NETWORK_MAINNET
                ),

                activity=(
                    ACTIVITY_CONFIG
                ),

                minor_version=(
                    version
                ),
            ),
        ]
    )

    return events


def main() -> None:
    args = parse_args()

    token = required_env(
        "MONDAY_API_TOKEN"
    )

    board_id = (
        board_id_from_env()
    )

    group_name = os.getenv(
        "MONDAY_GROUP_NAME",
        DEFAULT_GROUP_NAME,
    ).strip()

    first_monday = (
        first_monday_in_month(
            args.month
        )
    )

    print()
    print(
        f"Splice "
        f"{args.version}.x"
    )

    print(
        f"Monday board: "
        f"{board_id}"
    )

    print(
        f"Target group: "
        f"{group_name}"
    )

    print(
        f"First DevNet Monday: "
        f"{first_monday.isoformat()}"
    )

    if args.dry_run:
        print(
            "DRY RUN — validating "
            "the board and showing "
            "changes only."
        )

    board, group_id = preflight(
        token,
        board_id,
        group_name,
    )

    print(
        f"Board name: "
        f"{board.get('name', '')}"
    )

    print(
        "Preflight validation: OK"
    )

    events = make_schedule(
        args.version,
        args.month,
    )

    print(
        f"Schedule events: "
        f"{len(events)}"
    )

    print()

    current_network = None

    for event in events:

        if (
            event.network
            != current_network
        ):
            current_network = (
                event.network
            )

            print(
                current_network
            )

        upsert_event(
            token,
            board_id,
            group_id,
            board,
            event,
            args.dry_run,
        )

    print()
    print("Done.")


if __name__ == "__main__":
    main()