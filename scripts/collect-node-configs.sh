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
# If a pod has been running for a long time, the config line may have rotated
# out of the Kubernetes log buffer. In that case the script offers to delete
# the pod (once) so that its Deployment/StatefulSet recreates it and the config
# gets logged again. Deleting a pod causes a short downtime of that node.
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
#   --yes       Do not ask before deleting a pod whose logs no longer contain
#               the config; delete it right away.

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

# The markers with which the two kinds of nodes start their config log message.
canton_marker="Starting up with resolved config"
splice_marker="SpliceEnvironment with config = {"

# extract_config POD CONTAINER
#
# Prints the resolved config found in the logs of the given container, or
# nothing (and returns non-zero) if the logs do not contain it.
#
# Log lines are JSON objects with the config embedded in the "message" field
# (with "\n" escapes). We grep for the marker first so that jq only ever sees
# the relevant lines, take the most recent one (in case the container
# restarted within the retained log window), unescape it with jq, and finally
# strip the marker line itself (and, for Splice apps, the closing "}" that
# wraps the config) so that only the HOCON config remains.
extract_config() {
  local pod="$1" container="$2"
  local line
  line="$(kubectl logs -n "$namespace" "$pod" -c "$container" --tail=-1 \
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

# restart_and_extract POD CONTAINER APP
#
# Deletes the pod, waits for its replacement (found via the shared `app`
# label) to log its config, and prints that config. Returns non-zero on
# timeout.
restart_and_extract() {
  local pod="$1" container="$2" app="$3"
  local timeout_seconds=300 interval_seconds=5 waited=0
  local new_pod config

  echo "  Deleting pod $pod ..." >&2
  kubectl delete pod -n "$namespace" "$pod" --wait=false >/dev/null

  while [[ $waited -lt $timeout_seconds ]]; do
    sleep "$interval_seconds"
    waited=$((waited + interval_seconds))
    # Find the replacement pod: same app label, different name.
    new_pod="$(kubectl get pods -n "$namespace" -l "app=${app}" -o name 2>/dev/null \
      | sed 's#^pod/##' | grep -v -x -F "$pod" | head -n 1 || true)"
    if [[ -z "$new_pod" ]]; then
      continue
    fi
    if config="$(extract_config "$new_pod" "$container" 2>/dev/null)"; then
      echo "  Replacement pod $new_pod logged its config after ${waited}s." >&2
      printf '%s\n' "$config"
      return 0
    fi
  done
  echo "  Timed out after ${timeout_seconds}s waiting for a replacement of $pod to log its config." >&2
  return 1
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

mkdir -p "$out_dir"
echo "Collecting node configs from namespace '$namespace' into '$out_dir'"

# List all pods with their main container. We only care about containers
# running a Canton image (canton-participant, canton-sequencer, ...) or a
# Splice app image (sv-app, validator-app, scan-app, ...); everything else
# (postgres, cometbft, web UIs, ...) is skipped. For each such container we
# print: pod name, container name, the pod's `app` label, and whether the pod
# is managed by a controller (only then is it safe to delete it).
pods="$(kubectl get pods -n "$namespace" -o json | jq -r '
  .items[]
  | . as $pod
  | .spec.containers[]
  | (.image | split("/") | last | split("@")[0] | split(":")[0]) as $image
  | select($image | test("^canton-") or test("-app$"))
  | [
      $pod.metadata.name,
      .name,
      ($pod.metadata.labels.app // $pod.metadata.name),
      (($pod.metadata.ownerReferences // []) | length > 0)
    ]
  | @tsv
')"

if [[ -z "$pods" ]]; then
  echo "No Canton or Splice node pods found in namespace '$namespace'." >&2
  exit 1
fi

collected=()
failed=()

# The pod list is read from file descriptor 3 so that stdin stays available for
# the interactive confirmation prompt below.
while IFS=$'\t' read -r -u 3 pod container app has_owner; do
  echo "* $app (pod $pod, container $container)"
  target="$out_dir/$app.conf"

  if config="$(extract_config "$pod" "$container")"; then
    printf '%s\n' "$config" > "$target"
    echo "  Saved to $target"
    collected+=("$app")
    continue
  fi

  echo "  Logs of $pod no longer contain the startup config."
  if [[ "$has_owner" != "true" ]]; then
    echo "  Pod is not managed by a controller and would not be recreated; skipping." >&2
    failed+=("$app")
    continue
  fi

  if ! $assume_yes; then
    read -r -p "  Delete the pod so that it restarts and logs its config again? This causes a short downtime. [y/N] " answer
    if [[ "$answer" != [yY] ]]; then
      echo "  Skipping $app."
      failed+=("$app")
      continue
    fi
  fi

  if config="$(restart_and_extract "$pod" "$container" "$app")"; then
    printf '%s\n' "$config" > "$target"
    echo "  Saved to $target"
    collected+=("$app")
  else
    failed+=("$app")
  fi
done 3<<<"$pods"

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
