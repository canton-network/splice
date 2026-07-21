#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SV_METRICS_URL="${SV_METRICS_URL:-http://sv-app:10013/metrics}"
SCAN_URL="${SCAN_URL:-http://scan-app:5012}"

SV_THRESHOLD="${SV_THRESHOLD:-600}"
MEDIATOR_THRESHOLD="${MEDIATOR_THRESHOLD:-900}"
SCAN_THRESHOLD_ROUNDS="${SCAN_THRESHOLD_ROUNDS:-900}"
SCAN_THRESHOLD_EVENTS="${SCAN_THRESHOLD_EVENT:-300}"
SEQUENCER_THRESHOLD="${SEQUENCER_THRESHOLD:-2520}" # 42 minutes, Sequencer acknowledgments are irregular, so we use a higher threshold here

PARALLELISM="${PARALLELISM:-8}"

CURL_TIMEOUT="${CURL_TIMEOUT:-15}"
TLS_SKIP_VERIFY="${TLS_SKIP_VERIFY:-false}"

CURL_CMD=(curl -fs -m "$CURL_TIMEOUT")
GRPC_HEALTH_CMD=(grpc_health_code --max-time "$CURL_TIMEOUT")

if [[ $TLS_SKIP_VERIFY == true ]]; then
  CURL_CMD+=(-k)
  GRPC_HEALTH_CMD+=(--insecure)
fi

CURL_CMD_JSON=$(jq -nc --args '$ARGS.positional' -- "${CURL_CMD[@]}")
GRPC_HEALTH_CMD_JSON=$(jq -nc --args '$ARGS.positional' -- "${GRPC_HEALTH_CMD[@]}")

prom2json() {
  P2J_VERSION="1.5.0"
  P2J_ARCH="linux-amd64"
  P2J_BIN="/tmp/.prom2json-$P2J_VERSION"
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

  "$P2J_BIN" "$@"
}

# Converts a JSON object with string values to a JSON object with parsed JSON
# values. If a value is not valid JSON, it is replaced with null.
json_object_values_fromjson() {
  jq -e '.[] |= try(fromjson) catch null | values'
}

grpc_health() {
  local insecure=false max_time

  local args=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -m|--max-time)  max_time=$2; shift 2 ;;
      -k|--insecure)  insecure=true; shift ;;
      *)              args+=("$1"); shift ;;
    esac
  done

  [[ ${#args[@]} -eq 1 ]] ||
    { echo "Usage: grpc_health [-m|--max-time SECONDS] [-k|--insecure] [http://|https://]HOST:PORT" >&2; return 1; }

  local url=${args[0]}
  local curl_opts=()

  [[ -n ${max_time-} ]] && curl_opts+=(--max-time "$max_time")
  "$insecure" && curl_opts+=(-k)

  if [[ $url == "https://"* ]]; then
    curl_opts+=(--http2)
  else
    curl_opts+=(--http2-prior-knowledge)
  fi

  local out; out=$(
    set -o pipefail
    printf '\0\0\0\0\0' |
      curl -fs "${curl_opts[@]}" \
        -X POST -H 'Content-Type: application/grpc' \
        --data-binary @- "$url/grpc.health.v1.Health/Check" |
      xxd -p
  ) || { echo "error: request failed" >&2; return 1; }

  [[ "$out" == 00000000020801 ]] && { echo SERVING; return 0; }
  echo "error: not serving" >&2; return 1
}

grpc_health_code() {
  grpc_health "$@" &> /dev/null && echo 0 || echo 2
}

sv_get_status() {
  local sv_metric=splice_sv_status_report_creation_time_us

  local response; response=$(
    get_metric_data "$SV_METRICS_URL" "$sv_metric" |
      jq -e \
        --argjson threshold "$SV_THRESHOLD" \
        '
          .
          | map(
              .labels.report_publisher as $name |
              now - (.value | tonumber) / pow(10;6) as $delay |

              {
                ($name): (if $delay < $threshold then 0 else 1 end)
              }
            )
          | add
          | values
        '
  ) && echo "$response" || echo '{}'
}

get_metric_data() {
  local metrics_url=$1
  local metric_name=$2

  local response; response=$(
    "${CURL_CMD[@]}" "$metrics_url?name[]=$metric_name" | # filtering by name makes the response smaller and much faster
      prom2json
  ) || response='[]'

  local result; result=$(
    echo "$response" |
      jq -e \
        --arg metric "$metric_name" \
        '.[] | select(.name == $metric).metrics | values' # we have to filter by name again because prom2json returns all metrics prefixed with the given name
  ) && echo "$result" || echo '[]'
}

# Extracts status from sequencer metric data. Returns a JSON object with
# svNames as keys and 0 (acknowledgment within threshold) or 1 (otherwise) as
# values.
get_status_from_sequencer_metric_data() {
  local metric_json=$1
  local category_name=$2
  local threshold=$3

  local result; result=$(
    echo "$metric_json" |
      jq -e \
        --arg category_name "$category_name" \
        --argjson threshold "$threshold" \
        '
          .
          | map(
              (.labels.member | split("::")) as [$category, $name, $fingerprint] |
              select($category == $category_name) |
              now - (.value | tonumber) / pow(10;6) as $delay |

              {
                ($name): (if $delay < $threshold then 0 else 1 end)
              }
            )
          | add
          | values
        '
  ) && echo "$result" || echo '{}'
}

# Usage: json_array_to_bash_array somearr <<< '["val1", "val2"]'
#
# Takes a JSON array of strings from stdin and populates a bash array with the
# same values. The first argument is the name of the bash array to populate.
json_array_to_bash_array() {
  local bash_array_name=$1
  local -n bash_array_ref=$bash_array_name

  {
    # shellcheck disable=SC2034
    # https://github.com/koalaman/shellcheck/issues/817
    readarray -td '' bash_array_ref
    wait "$!" # catch jq exit code
  } < <(
    jq -j \
      '
        if (type == "array" and all(.[]; type == "string")) then
          .[] | (., "\u0000")
        else
          error("Input must be a JSON array of strings.")
        end
      '
  )
}

# Usage: run_parallel '{"label1": ["command1", "arg1"], "label2": ["command2", "arg2"]}'
#
# Takes a JSON object with labels as keys and command with args as values, runs
# the commands in parallel and returns a JSON object with the same labels as
# keys and the command outputs as values.
run_parallel() {
  local label_command_map_json=$1

  if ! jq -e '
      type == "object" and all(.[]; type == "array" and all(.[]; type == "string"))
    ' <<< "$label_command_map_json" > /dev/null
  then
    echo "Error: JSON must be an object with arrays of strings." >&2
    return 1
  fi

  local labels; json_array_to_bash_array labels < <(
    printf "%s" "$label_command_map_json" | jq 'keys'
  )

  local result; result=$(
    local -i proc_count=0
    local proc_max=$PARALLELISM
    local lockfile; lockfile=$(mktemp)

    for label in "${labels[@]}"; do
      local command_with_args; json_array_to_bash_array command_with_args < <(
        printf "%s" "$label_command_map_json" | jq --arg label "$label" '.[$label]'
      )

      # Limit the number of concurrent processes
      if (( proc_count >= proc_max )); then
        wait -n
        proc_count=$(( proc_count - 1 ))
      fi

      (
        local output; output=$(
          "${command_with_args[@]}" 2>/dev/null
        ) || true

        # Use an exclusive lock to make sure we don't mix up the outputs
        exec {LOCK_FD}<>"$lockfile"
        flock "$LOCK_FD"

        printf "%s" "$output" | jq -sR --arg label "$label" '{($label): .}'
      ) &

      proc_count=$(( proc_count + 1 ))
    done

    # Wait for all remaining processes to finish
    wait
    rm "$lockfile"
  )

  printf "%s" "$result" | jq -es 'add | values' || echo '{}'
}

# Tries to reach the scan and checks the age of the last open and issuing
# rounds. Returns a JSON object with svNames as keys and 0 (reachable and
# rounds within threshold), 1 (reachable but rounds not within threshold) or 2
# (not reachable) as values.
scan_get_status_rounds() {
  local scan_url=$SCAN_URL
  local scans_info_url="$scan_url/api/scan/v0/scans"

  local scan_info; scan_info=$("${CURL_CMD[@]}" "$scans_info_url" || echo '{}')

  local scan_cmds_rounds; scan_cmds_rounds=$(
    local result; result=$(
      echo "$scan_info" |
        jq -e \
          --argjson cmd "$CURL_CMD_JSON" \
          '
            .scans[]?.scans
            | map(
                {
                  (.svName):
                    $cmd +
                    ["--compressed"] +
                    ["--json", ({"cached_open_mining_round_contract_ids": [], "cached_issuing_round_contract_ids": []} | tojson)] +
                    [.publicUrl + "/api/scan/v0/open-and-issuing-mining-rounds"]
                }
              )
            | add
            | values
          '
    ) && echo "$result" || echo '{}'
  )

  local scan_data_rounds; scan_data_rounds=$(
    run_parallel "$scan_cmds_rounds" |
      json_object_values_fromjson || echo '{}'
  )

  local scan_status_rounds; scan_status_rounds=$(
    result=$(
      printf "%s" "$scan_data_rounds" |
        jq -e \
          --argjson threshold "$SCAN_THRESHOLD_ROUNDS" \
          '
            def get_delay(field; $now):
                [ field[]?.contract.created_at ]
                | sort[-1]
                | (try(.[0:19] + "Z" | ($now - fromdate) | round) // null)
            ;

            .[] |= (
              now as $now |
              get_delay(.open_mining_rounds; $now) as $open_delay |
              get_delay(.issuing_mining_rounds; $now) as $issuing_delay |
              [$open_delay, $issuing_delay] as $delays |

              if ($delays | all | not) then
                2
              elif ($delays | max > $threshold) then
                1
              else
                0
              end
            ) | values
          '
    ) && echo "$result" || echo '{}'
  )

  echo "$scan_status_rounds"
}

# Tries to fetch a recent event from scan. Returns 0 if successful, 1
# otherwise.
scan_try_fetch_event() {
  local url=$1

  if
    local migration_id; migration_id=$(
      "${CURL_CMD[@]}" "$url/api/scan/v0/migrations/last" |
      jq -er '.migration_id'
    ) &&

    local after; after=$(TZ=UTC0 printf '%(%FT%TZ)T' "$((EPOCHSECONDS - SCAN_THRESHOLD_EVENTS))") &&

    local events; events=$(
      "${CURL_CMD[@]}" \
          --compressed \
          --json '{"page_size": 1, "after": {"after_migration_id": '"$migration_id"', "after_record_time": "'"$after"'"}}' \
          "$url/api/scan/v0/events"
    ) &&

    echo "$events" | jq -e '.events | length > 0' > /dev/null
  then
    echo 0
  else
    echo 1
  fi
}

scan_get_status_events() {
  local scan_url=$SCAN_URL
  local scans_info_url="$scan_url/api/scan/v0/scans"

  local scan_info; scan_info=$("${CURL_CMD[@]}" "$scans_info_url" || echo '{}')

  local scan_urls; scan_urls=$(
    local result; result=$(
      echo "$scan_info" |
        jq -e '.scans[]?.scans | map({ (.svName): .publicUrl }) | add | values'
    ) && echo "$result" || echo '{}'
  )

  local scan_cmds_events; scan_cmds_events=$(
    local result; result=$(
      jq -ne \
        --argjson scan_urls "$scan_urls" \
        '
          $scan_urls | .[]
          |= ["scan_try_fetch_event", .]
          | values
        '
    ) && echo "$result" || echo '{}'
  )

  run_parallel "$scan_cmds_events" |
    json_object_values_fromjson || echo '{}'
}

# Tries to reach the sequencers and checks their health status. Returns a JSON
# object with svNames as keys and 0 (reachable and serving) or 2 (otherwise) as
# values.
sequencer_get_status_reachability() {
  local scan_url=$SCAN_URL
  local sequencers_info_url="$scan_url/api/scan/v0/dso-sequencers"

  local sequencers_info; sequencers_info=$(
    "${CURL_CMD[@]}" "$sequencers_info_url" || echo '{}'
  )

  local sequencers_info_for_serial; sequencers_info_for_serial=$(
    echo "$sequencers_info" |
      jq --argjson serial "$SERIAL_ID" '[.domainSequencers[]?.sequencers[] | select(.synchronizerSerial == $serial)]'
  )

  local sequencers_cmds; sequencers_cmds=$(
    local result; result=$(
      echo "$sequencers_info_for_serial" |
        jq -e \
          --argjson cmd "$GRPC_HEALTH_CMD_JSON" \
          '
            .
            | map({ (.svName): $cmd + [.url] })
            | add
            | values
          '
    ) && echo "$result" || echo '{}'
  )

  local sequencer_status; sequencer_status=$(
    run_parallel "$sequencers_cmds" |
      json_object_values_fromjson || echo '{}'
  )

  echo "$sequencer_status"
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

# Bitwise combination of status maps. Each map is a JSON object with string keys and
# integer values. The values are combined using a bitwise OR operation.
#   combine_status '{k1: v1, k2: v2}' '{k1: v3, k3: v4}' -> '{k1: v1 | v3, k2: v2, k3: v4}'
#
# Examples:
#   echo '{"a": 1, "b": 1}' '{"a": 2, "c": 2}' | combine_status -> '{"a": 3, "b": 1, "c": 2}'
#   echo '{"a": null}' '{"a": 1}'              | combine_status -> '{"a": 1}'
#   echo 'null' '{"a": 1}'                     | combine_status -> '{"a": 1}'
#   echo '{"a": 1}' 'null'                     | combine_status -> '{"a": 1}'
#   echo '{"a": null}' 'null'                  | combine_status -> '{"a": null}'
#   echo 'null' 'null'                         | combine_status -> 'null'
#   echo '{}' '{}'                             | combine_status -> '{}'
combine_status() {
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
  if ! prom2json --version &>/dev/null; then
    echo "ERROR: prom2json is not installed. Exiting." >&2
    return 1
  fi

  update_serial_id

  if [[ -z "${SEQUENCER_METRICS_URL:-}" ]]; then
    SEQUENCER_METRICS_URL=$(generate_sequencer_metrics_url)
  fi

  # Get SV and Scan status
  local sv_status; sv_status=$(sv_get_status)
  local scan_status_rounds; scan_status_rounds=$(scan_get_status_rounds)
  local scan_status_events; scan_status_events=$(scan_get_status_events)
  local scan_status; scan_status=$(echo "$scan_status_rounds" "$scan_status_events" | combine_status)

  # Get acknowledgment metrics from Sequencer
  local sequencer_metric_name; sequencer_metric_name=daml_sequencer_block_acknowledgments_micros
  local sequencer_metric_data; sequencer_metric_data=$(get_metric_data "$SEQUENCER_METRICS_URL" "$sequencer_metric_name")

  # Get Mediator status
  local mediator_status; mediator_status=$(get_status_from_sequencer_metric_data "$sequencer_metric_data" MED "$MEDIATOR_THRESHOLD")

  # Get Sequencer status
  local sequencer_status_lag; sequencer_status_lag=$(get_status_from_sequencer_metric_data "$sequencer_metric_data" SEQ "$SEQUENCER_THRESHOLD")
  local sequencer_status_reachability; sequencer_status_reachability=$(sequencer_get_status_reachability)
  local sequencer_status; sequencer_status=$(echo "$sequencer_status_lag" "$sequencer_status_reachability" | combine_status)

  jq -Sn \
    --argjson sv "$sv_status" \
    --argjson sv_threshold "$SV_THRESHOLD" \
    --argjson mediator "$mediator_status" \
    --argjson mediator_threshold "$MEDIATOR_THRESHOLD" \
    --argjson scan "$scan_status" \
    --argjson scan_threshold_rounds "$SCAN_THRESHOLD_ROUNDS" \
    --argjson scan_threshold_events "$SCAN_THRESHOLD_EVENTS" \
    --argjson sequencer "$sequencer_status" \
    --argjson sequencer_threshold "$SEQUENCER_THRESHOLD" \
    '
      {
        status: {
          sv:        {nodes: $sv,        description: "Last status report within \($sv_threshold) seconds"},
          mediator:  {nodes: $mediator,  description: "Last acknowledgment within \($mediator_threshold) seconds"},
          scan:      {nodes: $scan,      description: "Reachable, last open and issuing rounds are within \($scan_threshold_rounds) seconds and recent events are within \($scan_threshold_events) seconds"},
          sequencer: {nodes: $sequencer, description: "Reachable, last acknowledgment within \($sequencer_threshold) seconds"},
        },
        generatedAt: (now | todate),
      }
    '
}

main "$@"
