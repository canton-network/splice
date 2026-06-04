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

# Read thresholds from config, with defaults if not set
config=$(get_resolved_config)
seq_min_eps=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.sequencerMinEventsPerSecond // 1000")
part_min_ratio=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.participantMinCatchupRatio // 10")
med_min_ratio=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.mediatorMinCatchupRatio // 3")
seq_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.sequencerBlockDelaySeconds // 30")
part_delay_ok=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.participantDelaySeconds // 30")
med_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.mediatorDelaySeconds // 30")

PROM="https://prometheus.${GCP_CLUSTER_BASENAME}.network.canton.global"

outcome="timed_out"
poll_interval=60
start=$(date +%s)
start_time=$(date -u -d @"$start" '+%Y-%m-%dT%H:%M:%SZ')
timeout_hours="8"
timeout_secs=$(( timeout_hours * 3600 ))

# Helper to query Prometheus for a single value
# Fetch data from 2 minutes ago
# Default to value higher than threshold but less than expected max
function query_prom() {
  local default=${2:-"180"}
  local ts
  ts=$(date -d '2 minutes ago' +%s) 
  curl -ksf "${PROM}/api/v1/query" \
    --data-urlencode "query=${1}" \
    --data-urlencode "time=${ts}" \
 | jq -r ".data.result[0].value[1] // \"${default}\"" 
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
  query_prom "sum by(namespace, job) (rate(daml_sequencer_block_events_total{namespace=\"${namespace}\", job=~\"global-domain-.*-sequencer\"}[1m]))" "0"
}
function query_part_rate() {
  query_prom "sum by (namespace) (rate(daml_sequencer_client_handler_sequencer_events{namespace=\"${namespace}\",component=\"participant\"}[1m]))" "0"
}
function query_med_rate() {
  query_prom "sum by (namespace) (rate(daml_sequencer_client_handler_sequencer_events{namespace=\"${namespace}\",component=\"mediator\",job=~\"global-domain-.*-mediator\"}[1m]))" "0"
}

# Capture initial delays for catchup ratio computation
initial_seq_delay=$(query_seq_delay)
initial_part_delay=$(query_part_delay)
initial_med_delay=$(query_med_delay)
# Accumulators inside the loop
seq_rate_max=0
part_rate_max=0
med_rate_max=0

_info "Monitoring catchup for ${namespace}"
_info "Thresholds: seq>=${seq_min_eps} eps, participant>=${part_min_ratio}x, mediator>=${med_min_ratio}x"
_info "Caught-up when: seq<=${seq_delay_ok}s, participant<=${part_delay_ok}s, mediator<=${med_delay_ok}s"
_info "Test timeout: ${timeout_hours}h"
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

# Main monitoring loop
while true; do
  elapsed=$(( $(date +%s) - start ))
  if [ "$elapsed" -ge "$timeout_secs" ]; then
    _error_msg "Catchup timed out after ${timeout_hours}h"
    break
  fi

  # Track delays to determine if caught up
  seq_delay=$(query_seq_delay)
  part_delay=$(query_part_delay)
  med_delay=$(query_med_delay)

  seq_caught=$(echo "$seq_delay  <= $seq_delay_ok" | bc -l)
  part_caught=$(echo "$part_delay <= $part_delay_ok" | bc -l)
  med_caught=$(echo "$med_delay  <= $med_delay_ok" | bc -l)

  if [ "$seq_caught" = "1" ] && [ "$part_caught" = "1" ] && [ "$med_caught" = "1" ]; then
    _info "All components caught up"
    outcome="success"
    break
  fi

  # Track peak rates during catchup
  seq_rate=$(query_seq_rate)
  part_rate=$(query_part_rate)
  med_rate=$(query_med_rate)

  seq_rate_max=$(echo "if ($seq_rate > $seq_rate_max) $seq_rate else $seq_rate_max" | bc -l)
  part_rate_max=$(echo "if ($part_rate > $part_rate_max) $part_rate else $part_rate_max" | bc -l)
  med_rate_max=$(echo "if ($med_rate > $med_rate_max) $med_rate else $med_rate_max" | bc -l)

  _info "Delays — seq: ${seq_delay}s, participant: ${part_delay}s, mediator: ${med_delay}s (elapsed: ${elapsed}s)"
  _info "Peak rates — seq: ${seq_rate_max} eps, participant: ${part_rate_max} eps, mediator: ${med_rate_max} eps"

  sleep "$poll_interval"
done

elapsed=$(( $(date +%s) - start ))
elapsed_mins=$(( elapsed / 60 ))
end_time=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

_info "Waiting 60s for steady-state rate measurement..."
sleep 60
part_rate_end=$(query_part_rate)
med_rate_end=$(query_med_rate)
_info "Steady-state rates — participant: ${part_rate_end} eps, mediator: ${med_rate_end} eps"

# Compute final ratios and pass/fail based on thresholds
part_ratio=$(echo "scale=1; ($part_rate_max) / $part_rate_end" | bc)
med_ratio=$(echo "scale=1; ($med_rate_max) / $med_rate_end" | bc)

seq_ok=$(echo  "$seq_rate_max  >= $seq_min_eps"| bc -l)
part_ok=$(echo "$part_ratio >= $part_min_ratio" | bc -l)
med_ok=$(echo  "$med_ratio  >= $med_min_ratio"  | bc -l)

if [ "$outcome" = "success" ]; then
  icon="✅"
  exit_code=0
else
  icon="❌"
  exit_code=1
fi

message="${icon} *SV Catchup Test — \`${namespace}\` on \`${GCP_CLUSTER_BASENAME}\`*
Outcome: ${outcome} | Duration: ${elapsed_mins}m | Started: ${start_time} | Ended: ${end_time}

*Per-component peak rates over catchup window:*
• Sequencer: \`$(printf "%.0f" "$seq_rate_max")\` events/s  (expected throughput ≥ ${seq_min_eps})  $([ "$seq_ok" = "1" ] && echo "✅" || echo "❌")
• Participant: \`$(printf "%.1f" "$part_rate")\`x  (expected catchup speed ≥ ${part_min_ratio}x baseline)  $([ "$part_ok" = "1" ] && echo "✅" || echo "❌")
• Mediator: \`$(printf "%.1f" "$med_rate")\`x  (expected catchup speed ≥ ${med_min_ratio}x baseline)  $([ "$med_ok" = "1" ] && echo "✅" || echo "❌")"

_info "$message"

"${DA_REPO_ROOT}/.circleci/scripts/slack/post-slack-message.sh" \
  "$message" "$slack_channel"

exit $exit_code
