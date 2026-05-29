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

timeout_hours=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.timeoutHours // 6")
seq_min_eps=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.sequencerMinEventsPerSecond // 1500")
part_min_ratio=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.participantMinCatchupRatio // 10")
med_min_ratio=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.mediatorMinCatchupRatio // 3")
seq_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.sequencerBlockDelaySeconds // 5")
part_delay_ok=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.participantDelaySeconds // 30")
med_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.caughtUpThresholds.mediatorDelaySeconds // 30")

PROM="https://prometheus.${GCP_CLUSTER_BASENAME}.network.canton.global"

timeout_secs=$(( timeout_hours * 3600 ))
poll_interval=60
start=$(date +%s)
outcome="timed_out"

_info "Monitoring catchup for ${namespace}"
_info "Thresholds: seq>=${seq_min_eps} eps, participant>=${part_min_ratio}x, mediator>=${med_min_ratio}x"
_info "Caught-up when: seq<=${seq_delay_ok}s, participant<=${part_delay_ok}s, mediator<=${med_delay_ok}s"
_info "Timeout: ${timeout_hours}h"

function query_prom() {
  curl -ksf "${PROM}/api/v1/query" \
    --data-urlencode "query=${1}" \
 | jq -r '.data.result[0].value[1] // "999999"'
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

# Capture initial delays for catchup ratio computation
initial_seq_delay=$(query_seq_delay)
initial_part_delay=$(query_part_delay)
initial_med_delay=$(query_med_delay)

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

  _info "Delays — seq: ${seq_delay}s, participant: ${part_delay}s, mediator: ${med_delay}s (elapsed: ${elapsed}s)"

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

# Re-query final delays for rate computation
seq_delay=$(query_seq_delay)
part_delay=$(query_part_delay)
med_delay=$(query_med_delay)

# Sequencer: average events/second over the catchup window
window="${elapsed_mins}m"
if [ "$elapsed_mins" -gt 0 ]; then
  seq_rate=$(query_prom "sum(rate(daml_sequencer_block_events_total{namespace=\"${namespace}\", job=~\"global-domain-.*-sequencer\"}[${window}]))")
else
  seq_rate="0"
fi

# Participant/Mediator: catchup ratio = (initial_delay - final_delay + elapsed) / elapsed
# e.g., delay went from 3600s to 0 in 360s → ratio = (3600 + 360) / 360 ≈ 11x
if [ "$elapsed" -gt 0 ]; then
  part_rate=$(echo "scale=1; ($initial_part_delay - $part_delay + $elapsed) / $elapsed" | bc)
  med_rate=$(echo "scale=1; ($initial_med_delay - $med_delay + $elapsed) / $elapsed" | bc)
else
  part_rate="0"
  med_rate="0"
fi

seq_ok=$(echo  "$seq_rate  >= $seq_min_eps"| bc -l)
part_ok=$(echo "$part_rate >= $part_min_ratio" | bc -l)
med_ok=$(echo  "$med_rate  >= $med_min_ratio"  | bc -l)

if [ "$outcome" = "success" ] && [ "$seq_ok" = "1" ] && [ "$part_ok" = "1" ] && [ "$med_ok" = "1" ]; then
  icon="✅"
  exit_code=0
else
  icon="❌"
  exit_code=1
fi

message="${icon} *SV Catchup Test — \`${namespace}\` on \`${GCP_CLUSTER_BASENAME}\`*
Outcome: ${outcome} | Duration: ${elapsed_mins}m

*Per-component average rates over catchup window:*
• Sequencer: \`$(printf "%.0f" "$seq_rate")\` events/s  (expected ≥ ${seq_min_eps})  $([ "$seq_ok" = "1" ] && echo "✅" || echo "❌")
• Participant: \`$(printf "%.1f" "$part_rate")\`x  (expected ≥ ${part_min_ratio}x)  $([ "$part_ok" = "1" ] && echo "✅" || echo "❌")
• Mediator: \`$(printf "%.1f" "$med_rate")\`x  (expected ≥ ${med_min_ratio}x)  $([ "$med_ok" = "1" ] && echo "✅" || echo "❌")"

_info "$message"

"${DA_REPO_ROOT}/.circleci/scripts/slack/post-slack-message.sh" \
  "$message" "$slack_channel"

exit $exit_code
