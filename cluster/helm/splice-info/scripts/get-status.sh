#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SV_METRICS_URL="${SV_METRICS_URL:-http://sv-app:10013/metrics}"
SEQUENCER_METRICS_URL="${SEQUENCER_METRICS_URL:-http://global-domain-$MIGRATION_ID-sequencer:10013/metrics}"
SCAN_URL="${SCAN_URL:-http://scan-app:5012}"

SV_THRESHOLD="${SV_THRESHOLD:-600}"
MEDIATOR_THRESHOLD="${MEDIATOR_THRESHOLD:-900}"
SCAN_THRESHOLD="${SCAN_THRESHOLD:-900}"

CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

TLS_SKIP_VERIFY="${TLS_SKIP_VERIFY:-false}"
CURL_CMD=(curl -fs -m "$CURL_TIMEOUT")
[[ $TLS_SKIP_VERIFY == true ]] && CURL_CMD+=(-k)

prom2json() {
  P2J_VERSION="1.5.0"
  P2J_ARCH="linux-amd64"
  P2J_BIN="$HOME/.prom2json-$P2J_VERSION"
  P2J_URL="https://github.com/prometheus/prom2json/releases/download/v$P2J_VERSION/prom2json-$P2J_VERSION.$P2J_ARCH.tar.gz"
  P2J_EXPECTED_SHA="5935363cc8c88360e3aa275ddc5a754ad95f6bab6b6052978e686300baa5a4d6"

  if [[ ! -f "$P2J_BIN" ]]; then
    P2J_DIST=$(mktemp)
    P2J_TMPDIR=$(mktemp -d)

    echo "Downloading prom2json..." >&2
    curl -Ls "$P2J_URL" -o "$P2J_DIST"
    echo "$P2J_EXPECTED_SHA  $P2J_DIST" | sha256sum --check >&2 || return 1
    tar -xzf "$P2J_DIST" -C "$P2J_TMPDIR" --strip-components=1 "prom2json-$P2J_VERSION.$P2J_ARCH/prom2json"
    mv "$P2J_TMPDIR/prom2json" "$P2J_BIN"

    rm -rf "$P2J_TMPDIR" "$P2J_DIST"
  fi

  "$P2J_BIN"
}

sv_get_status() {
  SV_METRIC=splice_sv_status_report_creation_time_us

  local exit_code

  local response; response=$(
    "${CURL_CMD[@]}" "$SV_METRICS_URL?name[]=$SV_METRIC" |
      prom2json |
      jq -e \
        --arg threshold "$SV_THRESHOLD" \
        --arg metric "$SV_METRIC" \
        '
          ($threshold | tonumber) as $threshold
          | .[]
          | select(.name == $metric).metrics
          | map(
              {
                (.labels.report_publisher): (if (now - (.value | tonumber)/pow (10;6)) < $threshold then 0 else 1 end)
              }
            )
          | add
        '
  ) && exit_code=$? || exit_code=$?

  [[ $exit_code -eq 0 ]] && echo "$response" || echo '{}'
}

get_sequencer_metric_data() {
  local metric_name=$1

  "${CURL_CMD[@]}" "$SEQUENCER_METRICS_URL?name[]=$metric_name" |
    prom2json ||
    echo '[]'
}

get_status_from_sequencer_metric_data() {
  local metric_json=$1
  local metric_name=$2
  local category_name=$3
  local threshold=$4

  local exit_code

  local result; result=$(
    echo "$metric_json" |
      jq -e \
        --arg metric "$metric_name" \
        --arg category_name "$category_name" \
        --arg threshold "$threshold" \
        '
          ($threshold | tonumber) as $threshold
          | .[]
          | select(.name == $metric).metrics
          | map(
              (.labels.member | split("::")) as [$category, $name, $fingerprint] |
              select($category == $category_name) |
              {
                ($name): (if (now - (.value | tonumber)/pow (10;6)) < $threshold then 0 else 1 end)
              }
            )
          | add
        '
  ) && exit_code=$? || exit_code=$?

  [[ $exit_code -eq 0 ]] && echo "$result" || echo '{}'
}

scan_get_status() {
  local scan_url=$SCAN_URL
  local scans_info_url="$scan_url/api/scan/v0/scans"

  local scan_info; scan_info=$("${CURL_CMD[@]}" "$scans_info_url" || echo '{}')

  local scan_svnames_and_urls; IFS=$'\n' read -r -d '' -a scan_svnames_and_urls < <(
    echo "$scan_info" |
      jq -r '.scans[].scans[] | [.svName, .publicUrl + "/api/scan/v0/open-and-issuing-mining-rounds"] | join(" ")' && printf '\0'
  )

  local scan_data; scan_data=$(
    local -i proc_count=0
    local proc_max=8
    local lockfile; lockfile=$(mktemp)

    for svname_and_url in "${scan_svnames_and_urls[@]}"; do
      local svname url
      read -r svname url <<< "$svname_and_url";

      # Limit the number of concurrent processes
      if (( proc_count >= proc_max )); then
        wait -n
        proc_count=$(( proc_count - 1 ))
      fi

      (
        scan_response=$(
          "${CURL_CMD[@]}" \
             --compressed \
             --json '{"cached_open_mining_round_contract_ids":[],"cached_issuing_round_contract_ids":[]}' \
             "$url" | jq -e .
        ) && exit_code=$? || exit_code=$?

        [[ $exit_code -ne 0 ]] && scan_response='{}'

        scan_status=$(
          echo "$scan_response" |
          jq \
            --arg threshold "$SCAN_THRESHOLD" \
            --arg svname "$svname" \
            '
              def get_delay(field; $now):
                  [ field[]?.contract.created_at ]
                  | sort[-1]
                  | (try(.[0:19] + "Z" | ($now - fromdate) | round) // null)
              ;

              ($threshold | tonumber) as $threshold |
              now as $now |
              get_delay(.open_mining_rounds; $now) as $open_delay |
              get_delay(.issuing_mining_rounds; $now) as $issuing_delay |
              [$open_delay, $issuing_delay] as $delays |
              {
                ($svname): if ($delays | all) and ($delays | max < $threshold) then 0 else 1 end
              }
            '
        )

        # Use an exlusive lock to make sure we don't mix up the outputs
        exec {LOCK_FD}<>"$lockfile"
        flock "$LOCK_FD"

        echo "$scan_status"
      ) &

      proc_count=$(( proc_count + 1 ))
    done

    # Wait for all remaining processes to finish
    wait
    rm "$lockfile"
  )

  local exit_code

  local scan_status; scan_status=$(
    echo "$scan_data" | jq -es 'sort | add'
  ) && exit_code=$? || exit_code=$?

  [[ $exit_code -eq 0 ]] && echo "$scan_status" || echo '{}'
}

sv_status=$(sv_get_status)

sequencer_metric_name=daml_sequencer_block_acknowledgments_micros
sequencer_metric_data=$(get_sequencer_metric_data "$sequencer_metric_name")

mediator_status=$(get_status_from_sequencer_metric_data "$sequencer_metric_data" "$sequencer_metric_name" MED "$MEDIATOR_THRESHOLD")
scan_status=$(scan_get_status)

jq -n \
  --argjson sv "$sv_status" \
  --argjson sv_threshold "$SV_THRESHOLD" \
  --argjson mediator "$mediator_status" \
  --argjson mediator_threshold "$MEDIATOR_THRESHOLD" \
  --argjson scan "$scan_status" \
  --argjson scan_threshold "$SCAN_THRESHOLD" \
  '
    {
      status: {
        sv:       {nodes: $sv,       description: "Last status report within \($sv_threshold) seconds"},
        mediator: {nodes: $mediator, description: "Last acknowledgment within \($mediator_threshold) seconds"},
        scan:     {nodes: $scan,     description: "Last open and issuing rounds are within \($scan_threshold) seconds"},
      },
      generatedAt: (now | todate),
    }
  '
