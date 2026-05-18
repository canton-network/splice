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

PROM="http://prometheus-prometheus.observability.svc.cluster.local:9090"

timeout_secs=$(( timeout_hours * 3600 ))
poll_interval=60
start=$(date +%s)
outcome="timed_out"

_info "Monitoring catchup for ${namespace}"
_info "Thresholds: seq>=${seq_min_eps} eps, participant>=${part_min_ratio}x, mediator>=${med_min_ratio}x"
_info "Caught-up when: seq<=${seq_delay_ok}s, participant<=${part_delay_ok}s, mediator<=${med_delay_ok}s"
_info "Timeout: ${timeout_hours}h"

function query_prom() {
  curl -sf "${PROM}/api/v1/query" \
    --data-urlencode "query=${1}" \
 | jq -r '.data.result[0].value[1] // "0"'
}

while true; do
  elapsed=$(( $(date +%s) - start ))
  if [ "$elapsed" -ge "$timeout_secs" ]; then
    _error_msg "Catchup timed out after ${timeout_hours}h"
    break
  fi

  seq_delay=$(query_prom "canton_sequencer_block_delay_seconds{namespace=\"${namespace}\"}")
  part_delay=$(query_prom "canton_participant_ledger_api_delay_seconds{namespace=\"${namespace}\"}")
  med_delay=$(query_prom "canton_mediator_delay_seconds{namespace=\"${namespace}\"}")

  _info "Delays — seq: ${seq_delay}s, participant: ${part_delay}s, mediator: ${med_delay}s (elapsed: ${elapsed}s)"

  seq_caught=$(echo "$seq_delay  <= $seq_delay_ok" | bc)
  part_caught=$(echo "$part_delay <= $part_delay_ok" | bc)
  med_caught=$(echo "$med_delay  <= $med_delay_ok" | bc)

  if [ "$seq_caught" = "1" ] && [ "$part_caught" = "1" ] && [ "$med_caught" = "1" ]; then
    _info "All components caught up"
    outcome="success"
    break
  fi

  sleep "$poll_interval"
done

elapsed=$(( $(date +%s) - start ))
elapsed_mins=$(( elapsed / 60 ))
window="${elapsed_mins}m"

seq_rate=$(query_prom "rate(canton_sequencer_events_processed_total{namespace=\"${namespace}\"}[${window}])")
part_rate=$(query_prom "rate(canton_participant_ledger_events_total{namespace=\"${namespace}\"}[${window}])")
med_rate=$(query_prom "rate(canton_mediator_requests_processed_total{namespace=\"${namespace}\"}[${window}])")

seq_ok=$(echo  "$seq_rate  >= $seq_min_eps"| bc)
part_ok=$(echo "$part_rate >= $part_min_ratio" | bc)
med_ok=$(echo  "$med_rate  >= $med_min_ratio"  | bc)

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
