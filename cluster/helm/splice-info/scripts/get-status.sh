#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SV_METRICS_URL="${SV_METRICS_URL:-http://sv-app:10013/metrics}"
SCAN_URL="${SCAN_URL:-http://scan-app:5012}"

SV_THRESHOLD="${SV_THRESHOLD:-600}"
MEDIATOR_THRESHOLD="${MEDIATOR_THRESHOLD:-900}"
SCAN_THRESHOLD="${SCAN_THRESHOLD:-900}"
SEQUENCER_THRESHOLD="${SEQUENCER_THRESHOLD:-1800}" # Sequencer acknowledgments are irregular, so we use a higher threshold here

CURL_TIMEOUT="${CURL_TIMEOUT:-15}"
TLS_SKIP_VERIFY="${TLS_SKIP_VERIFY:-false}"

CURL_CMD=(curl -fs -m "$CURL_TIMEOUT")
GRPCURL_CMD=(grpcurl --max-time "$CURL_TIMEOUT")

if [[ $TLS_SKIP_VERIFY == true ]]; then
  CURL_CMD+=(-k)
  GRPCURL_CMD+=(--insecure)
fi

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

grpcurl() {
  GRPCURL_VERSION="1.9.3"
  GRPCURL_ARCH="linux_x86_64"
  GRPCURL_BIN="$HOME/.grpcurl-$GRPCURL_VERSION"
  GRPCURL_URL="https://github.com/fullstorydev/grpcurl/releases/download/v$GRPCURL_VERSION/grpcurl_${GRPCURL_VERSION}_$GRPCURL_ARCH.tar.gz"
  GRPCURL_EXPECTED_SHA="a926b62a85787ccf73ef8736b3ae554f1242e39d92bb8767a79d6dd23b11d1d5"

  if [[ ! -f "$GRPCURL_BIN" ]]; then
    local flock_file="/tmp/downloading_grpcurl_$GRPCURL_VERSION.lock"
    local flock_fd

    {
      flock "$flock_fd"

      if [[ ! -f "$GRPCURL_BIN" ]]; then
        GRPCURL_DIST=$(mktemp)
        GRPCURL_TMPDIR=$(mktemp -d)

        echo "Downloading grpcurl..." >&2
        curl -Ls "$GRPCURL_URL" -o "$GRPCURL_DIST"
        echo "$GRPCURL_EXPECTED_SHA  $GRPCURL_DIST" | sha256sum --check >&2 || return 1
        tar -xzf "$GRPCURL_DIST" -C "$GRPCURL_TMPDIR" grpcurl
        mv "$GRPCURL_TMPDIR/grpcurl" "$GRPCURL_BIN"

        rm -rf "$GRPCURL_TMPDIR" "$GRPCURL_DIST"
      fi
    } {flock_fd}<>"$flock_file"
  fi

  "$GRPCURL_BIN" "$@"
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

# Extracts status from sequencer metric data. Returns a JSON object with
# svNames as keys and 0 (acknowledgment within threshold) or 1 (otherwise) as
# values.
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

# Tries to reach the scan and checks the age of the last open and issuing
# rounds. Returns a JSON object with svNames as keys and 0 (reachable and
# rounds within threshold), 1 (reachable but rounds not within threshold) or 2
# (not reachable) as values.
scan_get_status() {
  local scan_url=$SCAN_URL
  local scans_info_url="$scan_url/api/scan/v0/scans"

  local scan_info; scan_info=$("${CURL_CMD[@]}" "$scans_info_url" || echo '{}')

  local scan_svnames_and_urls; IFS=$'\n' read -r -d '' -a scan_svnames_and_urls < <(
    echo "$scan_info" |
      jq -r '.scans[]?.scans[] | [.svName, .publicUrl + "/api/scan/v0/open-and-issuing-mining-rounds"] | join(" ")' && printf '\0'
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
                ($svname):
                  if ($delays | all | not) then
                    2
                  elif ($delays | max > $threshold) then
                    1
                  else
                    0
                  end
              }
            '
        )

        # Use an exclusive lock to make sure we don't mix up the outputs
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

# Tries to reach the sequencers and checks their health status. Returns a JSON
# object with svNames as keys and 0 (reachable and serving) or 2 (otherwise) as
# values.
sequencer_get_status_reachability() {
  local scan_url=$SCAN_URL
  local sequencers_info_url="$scan_url/api/scan/v0/dso-sequencers"

  local sequencers_info; sequencers_info=$("${CURL_CMD[@]}" "$sequencers_info_url" || echo '{}')
  local sequencers_info_for_serial; sequencers_info_for_serial=$(echo "$sequencers_info" | jq --argjson serial "$SERIAL_ID" '[.domainSequencers[]?.sequencers[] | select(.synchronizerSerial == $serial)]')

  local sequencer_svnames_and_urls; IFS=$'\n' read -r -d '' -a sequencer_svnames_and_urls < <(
    echo "$sequencers_info_for_serial" |
      jq -r '[.[] | [.svName, .url]] | sort[] | join(" ")' && printf '\0'
  )


  local sequencer_data; sequencer_data=$(
    local -i proc_count=0
    local proc_max=8
    local lockfile; lockfile=$(mktemp)

    for svname_and_url in "${sequencer_svnames_and_urls[@]}"; do
      local svname url
      read -r svname url <<< "$svname_and_url";

      # Limit the number of concurrent processes
      if (( proc_count >= proc_max )); then
        wait -n
        proc_count=$(( proc_count - 1 ))
      fi

      (
        sequencer_response=$(
          "${GRPCURL_CMD[@]}" "${url#https://}:443" "grpc.health.v1.Health/Check" 2>/dev/null |
          jq -e .
        ) && exit_code=$? || exit_code=$?

        [[ $exit_code -ne 0 ]] && sequencer_response='{}'

        sequencer_status=$(
          echo "$sequencer_response" |
          jq \
            --arg svname "$svname" \
            '
              {
                ($svname): if .status == "SERVING" then 0 else 2 end
              }
            '
        )

        # Use an exclusive lock to make sure we don't mix up the outputs
        exec {LOCK_FD}<>"$lockfile"
        flock "$LOCK_FD"

        echo "$sequencer_status"
      ) &

      proc_count=$(( proc_count + 1 ))
    done

    # Wait for all remaining processes to finish
    wait
    rm "$lockfile"
  )

  local exit_code

  local sequencer_status; sequencer_status=$(
    echo "$sequencer_data" | jq -es 'sort | add'
  ) && exit_code=$? || exit_code=$?

  [[ $exit_code -eq 0 ]] && echo "$sequencer_status" || echo '{}'
}

update_serial_id() {
  local fetched_serial_id; fetched_serial_id=$("${CURL_CMD[@]}" -m 1 "$SCAN_URL/api/scan/v0/active-synchronizer-serial" | jq -r '.serial') || true

  if [[ -n "$fetched_serial_id" ]]; then
    SERIAL_ID=$fetched_serial_id
  fi
}

generate_sequencer_metrics_url() {
  echo "http://global-domain-$SERIAL_ID-sequencer:10013/metrics"
}

# Bitwise merge of status maps. Each map is a JSON object with string keys and
# integer values. The values are merged using a bitwise OR operation.
#   merge_status '{k1: v1, k2: v2}' '{k1: v3, k3: v4}' -> '{k1: v1 | v3, k2: v2, k3: v4}'
#
# Examples:
#   echo '{"a": 1, "b": 1}' '{"a": 2, "c": 2}' | merge_status -> '{"a": 3, "b": 1, "c": 2}'
#   echo '{"a": null}' '{"a": 1}'              | merge_status -> '{"a": 1}'
#   echo 'null' '{"a": 1}'                     | merge_status -> '{"a": 1}'
#   echo '{"a": 1}' 'null'                     | merge_status -> '{"a": 1}'
#   echo '{"a": null}' 'null'                  | merge_status -> '{"a": null}'
#   echo 'null' 'null'                         | merge_status -> 'null'
#   echo '{}' '{}'                             | merge_status -> '{}'
merge_status() {
  jq -s \
    '
      def bitor:
        map(select(. != null)) | unique |
        if length == 0 then null
        elif length == 1 then first
        elif any(. == -1) then -1
        else (map(. % 2 | abs) | max) + 2 * (map(. / 2 | floor) | bitor)
        end
        ;

      map(select(. != null)) as $inputs |
      reduce $inputs[] as $i (null;
        reduce ($i | to_entries[]) as $e (. // {};
          .[$e.key] = ([.[$e.key], $e.value] | bitor)
        )
      )
    '
}

main() {
  update_serial_id

  if [[ -z "${SEQUENCER_METRICS_URL:-}" ]]; then
    SEQUENCER_METRICS_URL=$(generate_sequencer_metrics_url)
  fi

  # Get SV and Scan status
  local sv_status; sv_status=$(sv_get_status)
  local scan_status; scan_status=$(scan_get_status)

  # Get acknowledgment metrics from Sequencer
  local sequencer_metric_name; sequencer_metric_name=daml_sequencer_block_acknowledgments_micros
  local sequencer_metric_data; sequencer_metric_data=$(get_sequencer_metric_data "$sequencer_metric_name")

  # Get Mediator status
  local mediator_status; mediator_status=$(get_status_from_sequencer_metric_data "$sequencer_metric_data" "$sequencer_metric_name" MED "$MEDIATOR_THRESHOLD")

  # Get Sequencer status
  local sequencer_status_lag; sequencer_status_lag=$(get_status_from_sequencer_metric_data "$sequencer_metric_data" "$sequencer_metric_name" SEQ "$SEQUENCER_THRESHOLD")
  local sequencer_status_reachability; sequencer_status_reachability=$(sequencer_get_status_reachability)
  local sequencer_status; sequencer_status=$(echo "$sequencer_status_lag" "$sequencer_status_reachability" | merge_status)

  jq -Sn \
    --argjson sv "$sv_status" \
    --argjson sv_threshold "$SV_THRESHOLD" \
    --argjson mediator "$mediator_status" \
    --argjson mediator_threshold "$MEDIATOR_THRESHOLD" \
    --argjson scan "$scan_status" \
    --argjson scan_threshold "$SCAN_THRESHOLD" \
    --argjson sequencer "$sequencer_status" \
    --argjson sequencer_threshold "$SEQUENCER_THRESHOLD" \
    '
      {
        status: {
          sv:        {nodes: $sv,        description: "Last status report within \($sv_threshold) seconds"},
          mediator:  {nodes: $mediator,  description: "Last acknowledgment within \($mediator_threshold) seconds"},
          scan:      {nodes: $scan,      description: "Reachable, last open and issuing rounds are within \($scan_threshold) seconds"},
          sequencer: {nodes: $sequencer, description: "Reachable, last acknowledgment within \($sequencer_threshold) seconds"},
        },
        generatedAt: (now | todate),
      }
    '
}

main "$@"
