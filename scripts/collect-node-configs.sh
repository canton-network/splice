#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Collect the fully resolved HOCON configs of all Canton and Splice nodes in a
# Kubernetes namespace.
#
# Every Canton node (participant, sequencer, mediator) and every Splice app
# (sv-app, validator-app, scan-app, ...) logs its complete, resolved
# configuration exactly once, right after startup. Secrets in that dump are
# already redacted by the apps themselves. This script fetches those log lines
# with `kubectl logs`, extracts the config from them and writes one `.conf`
# file per node.
#
# If a node has been running for a long time, the config line may have rotated
# out of the Kubernetes log buffer. In that case the script offers to restart
# the node's Deployment (once) so that the config gets logged again. This
# causes a short downtime of that node. The restart is done via
# `kubectl rollout restart`; since all Splice Helm charts use the `Recreate`
# strategy, the old pod is fully stopped before the new one starts, so there
# are never two instances of a node running at the same time.
#
# Requirements: bash, kubectl (configured for the target cluster), jq, grep,
# sed, and `zip` if --zip is used.
#
# Usage:
#   collect-node-configs.sh NAMESPACE [--out DIR] [--zip] [--yes]
#
#   --out DIR   Directory to write the config files to.
#               Default: ./node-configs-NAMESPACE-<UTC timestamp>
#   --zip       Additionally create DIR.zip with all collected configs.
#   --yes       Do not ask before restarting a node whose logs no longer
#               contain the config; restart it right away.

set -euo pipefail

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

usage() {
  sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

if [[ $# -lt 1 ]]; then usage; fi
namespace="$1"
shift

out_dir=""
make_zip=false
assume_yes=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out_dir="$2"; shift 2 ;;
    --zip) make_zip=true; shift ;;
    --yes) assume_yes=true; shift ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

if [[ -z "$out_dir" ]]; then
  out_dir="./node-configs-${namespace}-$(date -u +%Y%m%dT%H%M%SZ)"
fi

for tool in kubectl jq grep sed; do
  command -v "$tool" >/dev/null || { echo "Required tool not found: $tool" >&2; exit 1; }
done
if $make_zip; then
  command -v zip >/dev/null || { echo "Required tool not found: zip (needed for --zip)" >&2; exit 1; }
fi

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# The container images (without registry and tag) of the nodes we collect
# configs from. Everything else in the namespace (postgres, cometbft, web UIs,
# ...) is skipped.
node_images=(
  canton-participant
  canton-sequencer
  canton-cometbft-sequencer
  canton-mediator
  sv-app
  validator-app
  scan-app
  splitwell-app
)

# The markers with which the two kinds of nodes start their config log message.
canton_marker="Starting up with resolved config"
splice_marker="SpliceEnvironment with config = {"

# extract_config DEPLOYMENT CONTAINER
#
# Prints the resolved config found in the logs of the given container of the
# deployment's pod, or nothing (and returns non-zero) if the logs do not
# contain it.
#
# Log lines are JSON objects with the config embedded in the "message" field
# (with "\n" escapes). We grep for the marker first so that jq only ever sees
# the relevant lines, take the most recent one (in case the container
# restarted within the retained log window), unescape it with jq, and finally
# strip the marker line itself (and, for Splice apps, the closing "}" that
# wraps the config) so that only the HOCON config remains.
extract_config() {
  local deployment="$1" container="$2"
  local line
  line="$(kubectl logs -n "$namespace" "deployment/$deployment" -c "$container" --tail=-1 \
    | grep -F -e "\"message\":\"${canton_marker}" -e "\"message\":\"${splice_marker}" \
    | tail -n 1 || true)"
  if [[ -z "$line" ]]; then
    return 1
  fi
  if [[ "$line" == *"\"message\":\"${splice_marker}"* ]]; then
    # Splice: drop the first line (marker) and the last line (closing brace).
    printf '%s\n' "$line" | jq -r '.message' | sed '1d;$d'
  else
    # Canton: drop the first line (marker).
    printf '%s\n' "$line" | jq -r '.message' | sed '1d'
  fi
}

# restart_and_extract DEPLOYMENT CONTAINER
#
# Restarts the deployment, waits for the new pod to log its config, and prints
# that config. Returns non-zero on timeout.
#
# Note that `kubectl rollout restart` records the restart time as an
# annotation on the deployment's pod template. Helm/Pulumi will overwrite that
# annotation again on the next deploy; it has no other effect.
restart_and_extract() {
  local deployment="$1" container="$2"
  local timeout_seconds=300 interval_seconds=5 waited=0
  local config

  echo "  Restarting deployment $deployment ..." >&2
  kubectl rollout restart -n "$namespace" "deployment/$deployment" >/dev/null

  while [[ $waited -lt $timeout_seconds ]]; do
    sleep "$interval_seconds"
    waited=$((waited + interval_seconds))
    # While the old pod is still shutting down, or before the new pod exists,
    # this either fails or finds no config; we just keep polling.
    if config="$(extract_config "$deployment" "$container" 2>/dev/null)"; then
      echo "  New pod logged its config after ${waited}s." >&2
      printf '%s\n' "$config"
      return 0
    fi
  done
  echo "  Timed out after ${timeout_seconds}s waiting for $deployment to log its config." >&2
  return 1
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

mkdir -p "$out_dir"
echo "Collecting node configs from namespace '$namespace' into '$out_dir'"

# List all deployments with their containers, as lines of
# "<deployment> <container> <image without registry and tag>".
all_containers="$(kubectl get deployments -n "$namespace" -o json | jq -r '
  .items[]
  | .metadata.name as $deployment
  | .spec.template.spec.containers[]
  | [$deployment, .name, (.image | split("/") | last | split("@")[0] | split(":")[0])]
  | @tsv
')"

# Keep only the node containers (see node_images above).
nodes=""
skipped=()
while IFS=$'\t' read -r deployment container image; do
  [[ -z "$deployment" ]] && continue
  if printf '%s\n' "${node_images[@]}" | grep -q -x -F "$image"; then
    nodes+="${deployment}	${container}"$'\n'
  else
    skipped+=("$deployment ($image)")
  fi
done <<<"$all_containers"

if [[ ${#skipped[@]} -gt 0 ]]; then
  echo "Skipping deployments that are not Canton or Splice nodes: ${skipped[*]}"
fi
if [[ -z "$nodes" ]]; then
  echo "No Canton or Splice node deployments found in namespace '$namespace'." >&2
  exit 1
fi

collected=()
failed=()

# The node list is read from file descriptor 3 so that stdin stays available
# for the interactive confirmation prompt below.
while IFS=$'\t' read -r -u 3 deployment container; do
  [[ -z "$deployment" ]] && continue
  echo "* $deployment (container $container)"
  target="$out_dir/$deployment.conf"

  if config="$(extract_config "$deployment" "$container")"; then
    printf '%s\n' "$config" > "$target"
    echo "  Saved to $target"
    collected+=("$deployment")
    continue
  fi

  echo "  Logs of $deployment no longer contain the startup config."
  if ! $assume_yes; then
    read -r -p "  Restart the deployment so that the config gets logged again? This causes a short downtime. [y/N] " answer
    if [[ "$answer" != [yY] ]]; then
      echo "  Skipping $deployment."
      failed+=("$deployment")
      continue
    fi
  fi

  if config="$(restart_and_extract "$deployment" "$container")"; then
    printf '%s\n' "$config" > "$target"
    echo "  Saved to $target"
    collected+=("$deployment")
  else
    failed+=("$deployment")
  fi
done 3<<<"$nodes"

echo
echo "Collected ${#collected[@]} config(s) in '$out_dir': ${collected[*]:-}"
if [[ ${#failed[@]} -gt 0 ]]; then
  echo "Failed to collect: ${failed[*]}" >&2
fi

if $make_zip; then
  zip_file="${out_dir%/}.zip"
  rm -f "$zip_file"
  (cd "$(dirname "$out_dir")" && zip -q -r "$(basename "$zip_file")" "$(basename "$out_dir")")
  echo "Archive written to $zip_file"
fi

if [[ ${#failed[@]} -gt 0 ]]; then
  exit 2
fi
