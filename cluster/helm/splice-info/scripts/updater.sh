#!/bin/sh

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eu

period=60

html_dir=/usr/share/nginx/html
runtime_index_file="$html_dir/runtime/index.html"

dso_json_path=runtime/dso.json
dso_json_file="$html_dir/$dso_json_path"

status_json_path=runtime/status.json
status_json_file="$html_dir/$status_json_path"

jq -n \
  --arg dso "/$dso_json_path" \
  --arg status "/$status_json_path" \
  '
    {
      $dso,
      $status,
    }
  ' > "$runtime_index_file"

while true; do
  start_time=$(date +%s);

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

  wait

  end_time=$(date +%s);
  sleep "$((period - (end_time - start_time)))"
done
