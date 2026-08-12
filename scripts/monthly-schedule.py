#!/usr/bin/env python3

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import calendar
import datetime
import json
import os
import re
import urllib.error
import urllib.request
from typing import Union


_COLUMN_ID_CACHE: dict[tuple[int, str], str] = {}


def parse_args() -> argparse.Namespace:
  parser = argparse.ArgumentParser(
    description="Generate monthly schedule data for a release version."
  )
  parser.add_argument("version", help="Version number, for example 0.8")
  parser.add_argument("month", help="Month in YYYY-MM format, for example 2026-08")
  args = parser.parse_args()

  if re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", args.month) is None:
    parser.error("month must be in YYYY-MM format")

  return args


def first_monday_in_month(month: str) -> datetime.date:
  year_str, month_str = month.split("-")
  year = int(year_str)
  month_num = int(month_str)
  first_day = datetime.date(year, month_num, 1)
  offset = (0 - first_day.weekday()) % 7
  return first_day + datetime.timedelta(days=offset)


def mondays_in_month(month: str) -> int:
  year_str, month_str = month.split("-")
  year = int(year_str)
  month_num = int(month_str)
  _, days_in_month = calendar.monthrange(year, month_num)
  first_day = datetime.date(year, month_num, 1)
  # weekday(): Monday=0 ... Sunday=6
  first_monday_offset = (0 - first_day.weekday()) % 7
  return (days_in_month - first_monday_offset + 6) // 7


def week_date_after_first_monday(
  month: str, day_of_week: Union[int, str], week_number: int
) -> datetime.date:
  if week_number < 0:
    raise ValueError("week_number must be >= 0")

  weekday_by_name = {
    "monday": 0,
    "tuesday": 1,
    "wednesday": 2,
    "thursday": 3,
    "friday": 4,
    "saturday": 5,
    "sunday": 6,
  }

  if isinstance(day_of_week, str):
    key = day_of_week.strip().lower()
    if key not in weekday_by_name:
      raise ValueError("day_of_week must be an integer 0-6 or weekday name")
    target_weekday = weekday_by_name[key]
  else:
    target_weekday = day_of_week
    if not 0 <= target_weekday <= 6:
      raise ValueError("day_of_week must be an integer in range 0..6")

  base_monday = first_monday_in_month(month) + datetime.timedelta(weeks=week_number)
  return base_monday + datetime.timedelta(days=target_weekday)


def _required_env_var(name: str) -> str:
  value = os.getenv(name)
  if value is None or value.strip() == "":
    raise RuntimeError(f"Missing required environment variable: {name}")
  return value


def _monday_api_request(token: str, query: str, variables: dict) -> dict:
  payload = {
    "query": query,
    "variables": variables,
  }

  request = urllib.request.Request(
    "https://api.monday.com/v2",
    data=json.dumps(payload).encode("utf-8"),
    headers={
      "Authorization": token,
      "Content-Type": "application/json",
    },
    method="POST",
  )

  try:
    with urllib.request.urlopen(request) as response:
      body = response.read().decode("utf-8")
  except urllib.error.HTTPError as exc:
    response_body = exc.read().decode("utf-8", errors="replace")
    raise RuntimeError(
      f"Monday API request failed ({exc.code}): {response_body}"
    ) from exc

  parsed = json.loads(body)
  if "errors" in parsed:
    raise RuntimeError(f"Monday API returned errors: {parsed['errors']}")
  return parsed


def _column_id_for_name(token: str, board_id: int, column_name: str) -> str:
  cache_key = (board_id, column_name)
  if cache_key in _COLUMN_ID_CACHE:
    return _COLUMN_ID_CACHE[cache_key]

  query = """
  query BoardColumns($boardId: [ID!]) {
    boards(ids: $boardId) {
      columns {
        id
        title
      }
    }
  }
  """
  response = _monday_api_request(token, query, {"boardId": [board_id]})
  boards = response.get("data", {}).get("boards", [])
  if not boards:
    raise RuntimeError(f"Board not found: {board_id}")

  target = column_name.strip().lower()
  for column in boards[0].get("columns", []):
    if str(column.get("title", "")).strip().lower() == target:
      column_id = column["id"]
      _COLUMN_ID_CACHE[cache_key] = column_id
      return column_id

  raise RuntimeError(
    f"Column named '{column_name}' not found on board {board_id}."
  )


def _create_monday_item(title: str, event_date: datetime.date) -> str:
  token = _required_env_var("MONDAY_API_TOKEN")
  board_id = int(_required_env_var("MONDAY_BOARD_ID"))
  date_column_name = "Date/Time US EST"
  submission_status_column_name = "Submission Status"

  submission_status_column_id = _column_id_for_name(
    token,
    board_id,
    submission_status_column_name,
  )

  mutation = """
  mutation CreateItem($boardId: ID!, $itemName: String!, $columnValues: JSON) {
      create_item(board_id: $boardId, item_name: $itemName, column_values: $columnValues) {
    id
      }
  }
  """

  column_values_payload = {
    submission_status_column_id: {
      "label": "To Be Confirmed",
    }
  }

  if date_column_name:
    date_column_id = _column_id_for_name(token, board_id, date_column_name)
    column_values_payload[date_column_id] = {
      "date": event_date.isoformat(),
    }

  parsed = _monday_api_request(
    token,
    mutation,
    {
      "boardId": board_id,
      "itemName": f"{title} ({event_date.isoformat()})",
      "columnValues": json.dumps(column_values_payload),
    },
  )

  return parsed["data"]["create_item"]["id"]


def create_event(
  title: str,
  args: argparse.Namespace,
  day_of_week: Union[int, str],
  week_number: int,
) -> None:
  event_date = week_date_after_first_monday(args.month, day_of_week, week_number)
  item_id = _create_monday_item(title, event_date)
  print(f"Created monday.com item {item_id}: {title} on {event_date.isoformat()}")

def main() -> None:
  args = parse_args()
  first_monday = first_monday_in_month(args.month)
  print()
  print(f"Creating schedule for version {args.version} released in {args.month} (first_monday={first_monday.isoformat()})")
  create_event("TEST!!!", args, 'monday', 0)

  # print("\n   DevNet")
  # for patch in range(mondays_in_month(args.month)):
  #   create_event(f"DevNet upgrades to Splice {args.version}.{patch}", args, 'monday', patch)
  # create_event(f"DevNet: Daml models introduced by Splice {args.version}.x take effect", args, 'tuesday', 2)
  # create_event(f"DevNet: Topology freeze ({args.version})", args, 'tuesday', 2)
  # create_event(f"DevNet: LSU ({args.version})", args, 'wednesday', 2)
  # create_event(f"DevNet: Breaking configuration changes introduced by Splice {args.version}.x take effect", args, 'tuesday', 3)

  # print("\n   TestNet")
  # for patch in range(mondays_in_month(args.month)):
  #   create_event(f"TestNet upgrades to Splice {args.version}.{patch}", args, 'monday', patch + 1)
  # create_event(f"TestNet: Daml models introduced by Splice {args.version}.x take effect", args, 'tuesday', 3)
  # create_event(f"TestNet: Topology freeze ({args.version})", args, 'tuesday', 3)
  # create_event(f"TestNet: LSU ({args.version})", args, 'wednesday', 3)
  # create_event(f"TestNet: Breaking configuration changes introduced by Splice {args.version}.x take effect", args, 'tuesday', 4)

  # print("\n   MainNet")
  # for patch in range(mondays_in_month(args.month)):
  #   create_event(f"MainNet upgrades to Splice {args.version}.{patch}", args, 'monday', patch + 2)
  # create_event(f"MainNet: Daml models introduced by Splice {args.version}.x take effect", args, 'tuesday', 4)
  # create_event(f"MainNet: Topology freeze ({args.version})", args, 'friday', 4)
  # create_event(f"MainNet: LSU ({args.version})", args, 'saturday', 4)
  # create_event(f"MainNet: Breaking configuration changes introduced by Splice {args.version}.x take effect", args, 'tuesday', 5)



if __name__ == "__main__":
  main()
