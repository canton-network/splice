#!/bin/sh

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eu

trap cleanup TERM

cleanup() {
  trap '' TERM # ignore TERM
  kill -- -$$ # send TERM to all processes in the group
  wait # wait for all processes to exit
}

interruptible_run() {
  "$@" &
  wait "$!"
}

period=60

html_dir=/usr/share/nginx/html
runtime_index_file="$html_dir/runtime/index.html"

dso_json_path=runtime/dso.json
dso_json_file="$html_dir/$dso_json_path"

status_json_path=runtime/status.json
status_json_file="$html_dir/$status_json_path"

info_json_path=runtime/info.json
info_json_file="$html_dir/$info_json_path"

jq -n \
  --arg dso "/$dso_json_path" \
  --arg status "/$status_json_path" \
  --arg info "/$info_json_path" \
  '
    {
      $dso,
      $info,
      $status,
    }
  ' > "$runtime_index_file"

while true; do
  start_time=$(interruptible_run date +%s);

  if result=$(/scripts/get-dso.sh); then
    dest="$dso_json_file"
    echo "$result" > "$dest.new"
    mv "$dest.new" "$dest"
  fi &

  if result=$(/scripts/get-status.sh); then
    dest="$status_json_file"
    echo "$result" > "$dest.new"
    mv "$dest.new" "$dest"
  fi &

  if result=$(/scripts/get-info.sh); then
    dest="$info_json_file"
    echo "$result" > "$dest.new"
    mv "$dest.new" "$dest"
  fi &

  wait

  end_time=$(interruptible_run date +%s);

  sleep_time=$(( period - (end_time - start_time) ))
  sleep_time=$(( sleep_time > 0 ? sleep_time : 0 ))
  interruptible_run sleep "$sleep_time"
done
