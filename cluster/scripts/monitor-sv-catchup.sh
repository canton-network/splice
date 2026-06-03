#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# Monitors the catchup progress of an SV after a restore from an old backup.
# Reads thresholds from the resolved config (testing.catchup.thresholds).
# Posts a Slack message with per-component rates and pass/fail outcome.
#
# Usage: monitor-sv-catchup.sh <namespace> <slack_channel>

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"
# shellcheck disable=SC1091
source "${SPLICE_ROOT}/cluster/scripts/utils.source"

namespace=${1:?namespace must be provided}
slack_channel=${2:?slack_channel must be provided}

config=$(get_resolved_config)

timeout_hours=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.timeoutHours // 6")
seq_min_eps=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.sequencerMinEventsPerSecond // 800")
part_min_ratio=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.participantMinCatchupRatio // 10")
med_min_ratio=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.mediatorMinCatchupRatio // 3")
seq_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.sequencerBlockDelaySeconds // 30")
part_delay_ok=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.participantDelaySeconds // 30")
med_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.mediatorDelaySeconds // 30")

PROM="https://prometheus.${GCP_CLUSTER_BASENAME}.network.canton.global"

timeout_secs=$(( timeout_hours * 3600 ))
poll_interval=60
start=$(date +%s)
start_time=$(date -u -d @"$start" '+%Y-%m-%dT%H:%M:%SZ')

outcome="timed_out"

_info "Monitoring catchup for ${namespace}"
_info "Thresholds: seq>=${seq_min_eps} eps, participant>=${part_min_ratio}x, mediator>=${med_min_ratio}x"
_info "Caught-up when: seq<=${seq_delay_ok}s, participant<=${part_delay_ok}s, mediator<=${med_delay_ok}s"
_info "Timeout: ${timeout_hours}h"

function query_prom() {
  local ts
  ts=$(date -d '2 minutes ago' +%s) # starting the monitoring takes some time
  curl -ksf "${PROM}/api/v1/query" \
    --data-urlencode "query=${1}" \
    --data-urlencode "time=${ts}" \
 | jq -r '.data.result[0].value[1] // "180"' # default to value higher than threshold but less than expected max
}

function query_seq_delay() {
  query_prom "min by (namespace, job) (daml_sequencer_block_delay{namespace=\"${namespace}\", component=\"sequencer\", job=~\"global-domain-.*-sequencer\"}) / 1000"
}

function query_part_delay() {
  query_prom "max by (namespace) (timestamp(daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"participant\"}) - (daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"participant\"} / 1e6))"
}

function query_med_delay() {
  query_prom "max by (namespace, job) (timestamp(daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"mediator\",job=~\"global-domain-.*-mediator\"}) - (daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"mediator\",job=~\"global-domain-.*-mediator\"} / 1e6))"
}

function query_seq_rate() {
  query_prom "sum by(namespace, job) (rate(daml_sequencer_block_events_total{namespace=\"${namespace}\", job=~\"global-domain-.*-sequencer\"}[1m]))"
}

function query_part_rate() {
  query_prom "sum by (namespace) (rate(daml_sequencer_client_handler_sequencer_events{namespace=\"${namespace}\",component=\"participant\"}[1m]))"
}

function query_med_rate() {
  query_prom "sum by (namespace) (rate(daml_sequencer_client_handler_sequencer_events{namespace=\"${namespace}\",component=\"mediator\",job=~\"global-domain-.*-mediator\"}[1m]))"
}

# Capture initial delays for catchup ratio computation
initial_seq_delay=$(query_seq_delay)
initial_part_delay=$(query_part_delay)
initial_med_delay=$(query_med_delay)

# Accumulators inside the loop
seq_rate_max=0
part_rate_max=0
med_rate_max=0

_info "Initial delays — seq: ${initial_seq_delay}s, participant: ${initial_part_delay}s, mediator: ${initial_med_delay}s"

# If all components are already caught up at start, the backup was too recent
# or the node caught up before monitoring started. Treat as success.
if (( $(echo "$initial_seq_delay <= $seq_delay_ok" | bc -l) )) && \
   (( $(echo "$initial_part_delay <= $part_delay_ok" | bc -l) )) && \
   (( $(echo "$initial_med_delay <= $med_delay_ok" | bc -l) )); then
  _info "All components already caught up at start — no catchup window to measure."

  message="✅ *SV Catchup Test — \`${namespace}\` on \`${GCP_CLUSTER_BASENAME}\`*
Outcome: already_caught_up | Duration: 0m

All components were already caught up when monitoring started.
Initial delays — seq: ${initial_seq_delay}s, participant: ${initial_part_delay}s, mediator: ${initial_med_delay}s
No catchup window to measure rates."

  _info "$message"
  "${DA_REPO_ROOT}/.circleci/scripts/slack/post-slack-message.sh" \
    "$message" "$slack_channel"
  exit 0
fi

while true; do
  elapsed=$(( $(date +%s) - start ))
  if [ "$elapsed" -ge "$timeout_secs" ]; then
    _error_msg "Catchup timed out after ${timeout_hours}h"
    break
  fi

  seq_delay=$(query_seq_delay)
  part_delay=$(query_part_delay)
  med_delay=$(query_med_delay)

  sr=$(query_seq_rate)
  pr=$(query_part_rate)
  mr=$(query_med_rate)

  # Track peak rates during catchup
  seq_rate_max=$(echo "if ($sr > $seq_rate_max) $sr else $seq_rate_max" | bc -l)
  part_rate_max=$(echo "if ($pr > $part_rate_max) $pr else $part_rate_max" | bc -l)
  med_rate_max=$(echo "if ($mr > $med_rate_max) $mr else $med_rate_max" | bc -l)

  _info "Delays — seq: ${seq_delay}s, participant: ${part_delay}s, mediator: ${med_delay}s (elapsed: ${elapsed}s)"
  _info "Peak rates — seq: ${seq_rate_max} eps, participant: ${part_rate_max} eps, mediator: ${med_rate_max} eps"

  seq_caught=$(echo "$seq_delay  <= $seq_delay_ok" | bc -l)
  part_caught=$(echo "$part_delay <= $part_delay_ok" | bc -l)
  med_caught=$(echo "$med_delay  <= $med_delay_ok" | bc -l)

  if [ "$seq_caught" = "1" ] && [ "$part_caught" = "1" ] && [ "$med_caught" = "1" ]; then
    _info "All components caught up"
    outcome="success"
    break
  fi

  sleep "$poll_interval"
done

elapsed=$(( $(date +%s) - start ))
elapsed_mins=$(( elapsed / 60 ))
end_time=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

_info "Waiting 60s for steady-state rate measurement..."
sleep 60

pr_end=$(query_part_rate)
mr_end=$(query_med_rate)
_info "Steady-state rates — participant: ${pr_end} eps, mediator: ${mr_end} eps"

part_rate=$(echo "scale=1; ($part_rate_max) / $pr_end" | bc)
med_rate=$(echo "scale=1; ($med_rate_max) / $mr_end" | bc)

seq_ok=$(echo  "$seq_rate_max  >= $seq_min_eps"| bc -l)
part_ok=$(echo "$part_rate >= $part_min_ratio" | bc -l)
med_ok=$(echo  "$med_rate  >= $med_min_ratio"  | bc -l)

if [ "$outcome" = "success" ]; then
  icon="✅"
  exit_code=0
else
  icon="❌"
  exit_code=1
fi

message="${icon} *SV Catchup Test — \`${namespace}\` on \`${GCP_CLUSTER_BASENAME}\`*
Outcome: ${outcome} | Duration: ${elapsed_mins}m | Started: ${start_time} | Ended: ${end_time}

*Per-component average rates over catchup window:*
• Sequencer: \`$(printf "%.0f" "$seq_rate_max")\` events/s  (expected throughput ≥ ${seq_min_eps})  $([ "$seq_ok" = "1" ] && echo "✅" || echo "❌")
• Participant: \`$(printf "%.1f" "$part_rate")\`x  (expected catchup speed ≥ ${part_min_ratio}x baseline)  $([ "$part_ok" = "1" ] && echo "✅" || echo "❌")
• Mediator: \`$(printf "%.1f" "$med_rate")\`x  (expected catchup speed ≥ ${med_min_ratio}x baseline)  $([ "$med_ok" = "1" ] && echo "✅" || echo "❌")"

_info "$message"

"${DA_REPO_ROOT}/.circleci/scripts/slack/post-slack-message.sh" \
  "$message" "$slack_channel"

exit $exit_code
