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
part_min_eps=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.participantMinEventsPerSecond // 1000")
med_min_eps=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.mediatorMinEventsPerSecond // 1000")
seq_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.sequencerBlockDelaySeconds // 30")
part_delay_ok=$(echo "$config"| yq ".svs.${namespace}.testing.catchup.thresholds.participantDelaySeconds // 30")
med_delay_ok=$(echo "$config" | yq ".svs.${namespace}.testing.catchup.thresholds.mediatorDelaySeconds // 30")

if [[ "$GCP_CLUSTER_BASENAME" == *scratch* ]]; then
  PROM="https://prometheus.${GCP_CLUSTER_BASENAME}.network.canton.global"
else
  PROM="https://prometheus.${GCP_CLUSTER_BASENAME}.global.canton.network.digitalasset.com"
fi

outcome="timed_out"
poll_interval=60
start=$(date +%s)
start_time=$(date -u -d @"$start" '+%Y-%m-%dT%H:%M:%S.%3NZ')
timeout_hours="8"
timeout_secs=$(( timeout_hours * 3600 ))
stall_start=$(date +%s)
stall_timeout_secs=3600

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
  query_prom "min by (namespace, job) (daml_sequencer_block_delay{namespace=\"${namespace}\", component=\"sequencer\", job=~\"global-domain-.*-sequencer\"}) / 1000" "${seq_delay_ok}"
}
function query_part_delay() {
  query_prom "max by (namespace) (timestamp(daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"participant\"}) - (daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"participant\"} / 1e6))" "${part_delay_ok}"
}
function query_med_delay() {
  query_prom "max by (namespace, job) (timestamp(daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"mediator\",job=~\"global-domain-.*-mediator\"}) - (daml_sequencer_client_handler_last_sequencing_time_micros{namespace=\"${namespace}\",component=\"mediator\",job=~\"global-domain-.*-mediator\"} / 1e6))" "${med_delay_ok}"
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

# Accumulators inside the loop
seq_rate_sum=0
part_rate_sum=0
med_rate_sum=0
rate_sample_count=0

_info "Monitoring catchup for ${namespace}"
_info "Thresholds: seq>=${seq_min_eps} eps, participant>=${part_min_eps} eps, mediator>=${med_min_eps} eps"
_info "Caught-up when: seq<=${seq_delay_ok}s, participant<=${part_delay_ok}s, mediator<=${med_delay_ok}s"
_info "Test timeout: ${timeout_hours}h | Stall timeout: 1h of zero progress"

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

  seq_caught=$(echo "$seq_delay  < $seq_delay_ok" | bc -l)
  part_caught=$(echo "$part_delay < $part_delay_ok" | bc -l)
  med_caught=$(echo "$med_delay  < $med_delay_ok" | bc -l)

  _info "Delays — seq: ${seq_delay}s, participant: ${part_delay}s, mediator: ${med_delay}s (elapsed: ${elapsed}s)"

  if [ "$seq_caught" = "1" ] && [ "$part_caught" = "1" ] && [ "$med_caught" = "1" ]; then
    _info "All components caught up"
    outcome="success"
    break
  fi

  # Track peak rates during catchup
  seq_rate=$(query_seq_rate)
  part_rate=$(query_part_rate)
  med_rate=$(query_med_rate)

  seq_rate_sum=$(echo "$seq_rate_sum + $seq_rate" | bc -l)
  part_rate_sum=$(echo "$part_rate_sum + $part_rate" | bc -l)
  med_rate_sum=$(echo "$med_rate_sum + $med_rate" | bc -l)
  rate_sample_count=$((rate_sample_count + 1))

  _info "Mean rates so far> seq: $(echo "scale=1; $seq_rate_sum / $rate_sample_count" | bc) eps, participant: $(echo "scale=1; $part_rate_sum / $rate_sample_count" | bc) eps, mediator: $(echo "scale=1; $med_rate_sum / $rate_sample_count" | bc) eps"

  # Check that the catchup is making progress, otherwise abort after 1h of zero progress
  all_zero=$(echo "$seq_rate == 0 && $part_rate == 0 && $med_rate == 0" | bc -l)
  if [ "$all_zero" = "1" ]; then
    stall_elapsed=$(( $(date +%s) - stall_start ))
    _info "Zero progress for ${stall_elapsed}s / ${stall_timeout_secs}s before giving up"
    if [ "$stall_elapsed" -ge "$stall_timeout_secs" ]; then
      _error_msg "No progress for over 1 hour, aborting catchup test"
      outcome="stalled"
      break
    fi
  else
    stall_start=$(date +%s)
  fi

  sleep "$poll_interval"
done

elapsed=$(( $(date +%s) - start ))
elapsed_mins=$(( elapsed / 60 ))
end_time=$(date -u '+%Y-%m-%dT%H:%M:%S.%3NZ')

if [ "$rate_sample_count" -gt 0 ]; then
  seq_rate_mean=$(echo "$seq_rate_sum / $rate_sample_count" | bc -l)
  part_rate_mean=$(echo "$part_rate_sum / $rate_sample_count" | bc -l)
  med_rate_mean=$(echo "$med_rate_sum / $rate_sample_count" | bc -l)
else
  seq_rate_mean=0
  part_rate_mean=0
  med_rate_mean=0
fi

seq_ok=$(echo  "$seq_rate_mean  >= $seq_min_eps"| bc -l)
part_ok=$(echo "$part_rate_mean >= $part_min_eps" | bc -l)
med_ok=$(echo  "$med_rate_mean  >= $med_min_eps"  | bc -l)

if [ "$outcome" = "success" ]; then
  icon="✅"
  exit_code=0
elif [ "$outcome" = "stalled" ]; then
  icon="🚨"
  exit_code=1
else
  icon="❌"
  exit_code=1
fi

grafana_base="https://grafana.${GCP_CLUSTER_BASENAME}.global.canton.network.digitalasset.com"
grafana_domain_link="${grafana_base}/d/ca9df344-c699-4efe-83c2-5fb2639d96d9/global-domain-catchup?orgId=1&timezone=UTC&var-DS=prometheus&var-namespace=${namespace}&var-migration=All&viewPanel=panel-11&from=${start_time}&to=${end_time}"
grafana_participant_link="${grafana_base}/d/edkzo5ukgeqyoc/participant?orgId=1&timezone=UTC&var-namespace=${namespace}&var-job=All&var-participant=All&viewPanel=panel-13&from=${start_time}&to=${end_time}"

message="${icon} *SV Catchup Test — \`${namespace}\` on \`${GCP_CLUSTER_BASENAME}\`*
Outcome: ${outcome}$([ "$outcome" = "stalled" ] && echo " — no events processed for 1h, aborting test, node may be stuck") | Duration: ${elapsed_mins}m | Started: ${start_time} | Ended: ${end_time}

*Per-component mean rates over catchup window (${rate_sample_count} samples):*
• Sequencer: \`$(printf "%.1f" "$seq_rate_mean")\` events/s  (expected ≥ ${seq_min_eps})  $([ "$seq_ok" = "1" ] && echo "✅" || echo "❌")
• Participant: \`$(printf "%.1f" "$part_rate_mean")\` events/s  (expected ≥ ${part_min_eps})  $([ "$part_ok" = "1" ] && echo "✅" || echo "❌")
• Mediator: \`$(printf "%.1f" "$med_rate_mean")\` events/s  (expected ≥ ${med_min_eps})  $([ "$med_ok" = "1" ] && echo "✅" || echo "❌")

*Dashboards:*
• <${grafana_domain_link}|Sequencer>
• <${grafana_participant_link}|Participant and Mediator>"

_info "$message"

"${DA_REPO_ROOT}/.circleci/scripts/slack/post-slack-message.sh" \
  "$message" "$slack_channel"

exit $exit_code
