#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# Offboards a configurable SV, used for catchup testing.
#
# Required env vars:
#   SV_NAMESPACE   - the k8s namespace of the SV to offboard (e.g. "sv-9")
#   SV_PUBLIC_NAME - the DSO name of the SV to offboard  (e.g. "Digital-Asset-Eng-9")
#
# curl uses --retry-all-errors because some 4xx returned by canton are transient errors that must be retried

SV_NAMESPACE=${SV_NAMESPACE:?SV_NAMESPACE must be set}
SV_PUBLIC_NAME=${SV_PUBLIC_NAME:?SV_PUBLIC_NAME must be set}

function get_resolved_config() {
  "${SPLICE_ROOT}/cluster/scripts/get-resolved-config.sh"
}

# SV1 creates the vote request
sv1_token=$(cncluster get_token sv-1 sv)

current_dso_config=$(curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors \
  -X GET "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/dso")
echo "DSO: $current_dso_config"

requester=$(echo "$current_dso_config" | jq '.sv_party_id')
offboarded=$(echo "$current_dso_config" | jq -r \
  --arg name "$SV_PUBLIC_NAME" \
  '.dso_rules.contract.payload.svs | map(select(.[1].name == $name))[0][0]')

if [ -z "$offboarded" ] || [ "$offboarded" == "null" ]; then
  echo "ERROR: SV '$SV_PUBLIC_NAME' not found in DSO config. Is it already offboarded?"
  exit 1
fi

echo "Offboarding SV '$SV_PUBLIC_NAME' (party: $offboarded) from namespace '$SV_NAMESPACE'"

expiration_microseconds='60000000'

data='
{
    "requester": '$requester',
    "action": {
        "tag": "ARC_DsoRules",
        "value": {
            "dsoAction": {
                "tag": "SRARC_OffboardSv",
                "value": {
                    "sv": "'$offboarded'"
                }
            }
        }
    },
    "url": "http://example.com",
    "description": "Offboarding '"$SV_PUBLIC_NAME"' for catchup test",
    "expiration": {
        "microseconds": "'$expiration_microseconds'"
    }
}
'

echo "Sending vote request: $data"

curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors \
  -X POST "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/admin/sv/voterequest/create" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $sv1_token" \
  --data-raw "$data"

vote_requests=$(curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors \
  -X GET "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/admin/sv/voterequests" \
  -H "Authorization: Bearer $sv1_token")
echo "Found vote requests: $vote_requests"

vote_request_cid=$(echo "$vote_requests" | jq -r '.dso_rules_vote_requests[0].contract_id')

vote_data='{
  "vote_request_contract_id":"'"$vote_request_cid"'",
  "is_accepted":true,
  "reason_url":"No URL",
  "reason_description":"Accepted via vote-for-offboard-sv script"
}'
echo "Casting votes on $vote_request_cid with: $vote_data"

# All other SVs vote in favor — excluding the SV being offboarded

other_svs=()

# standard (eng) SVs
DSO_SIZE=${DSO_SIZE:-3}
for ((i=2; i<=DSO_SIZE; i++)); do
  sv="sv-$i"
  # skip the SV being offboarded
  if [ "$sv" == "$SV_NAMESPACE" ]; then
    echo "Skipping vote from $sv (being offboarded)"
    continue
  fi
  other_svs+=("$sv")
done

# extra SVs from config (e.g. sv-da-1, sv)
extra_svs=$(get_resolved_config | yq '.svs | keys | .[] | select(test("^(default|sv-[0-9]+|sv)$") | not)')
for sv in $extra_svs; do
  if [ "$sv" == "$SV_NAMESPACE" ]; then
    echo "Skipping vote from $sv (being offboarded)"
    continue
  fi
  other_svs+=("$sv")
done

for sv in "${other_svs[@]}"; do
  token=$(cncluster get_token "$sv" sv)
  echo "Casting vote on $sv"
  subdomain=$(get_resolved_config | yq ".svs.$sv.subdomain // \"$sv-eng\"")

  curl -s --fail-with-body --show-error --retry 10 --retry-delay 10 --retry-all-errors \
    -X POST "https://sv.$subdomain.$GCP_CLUSTER_HOSTNAME/api/sv/v0/admin/sv/votes" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $token" \
    --data-raw "$vote_data"
done

# Wait for SV to be offboarded

MAX_RETRIES=300
retry_count=0
until [ $retry_count -gt $MAX_RETRIES ]; do
  echo "Checking whether DSO info has been updated"
  retry_count=$((retry_count+1))

  new_dso_config=$(curl -s -X GET "https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/dso")
  echo "DSO info: $new_dso_config"
  offboarded_status=$(echo "$new_dso_config" | jq -r \
    --arg name "$SV_PUBLIC_NAME" \
    '.dso_rules.contract.payload.svs | map(select(.[1].name == $name)) | length')

  echo "Remaining entries for '$SV_PUBLIC_NAME' in DSO: $offboarded_status"

  if [ "$offboarded_status" == "0" ]; then
    echo "Success: '$SV_PUBLIC_NAME' has been offboarded"
    break
  fi

  echo "'$SV_PUBLIC_NAME' has not been offboarded yet. Current SVs in dso_config:"
  echo "$new_dso_config" | jq -r '.dso_rules.contract.payload.svs'

  if [ $retry_count -gt $MAX_RETRIES ]; then
    echo "ERROR: '$SV_PUBLIC_NAME' was not offboarded after $MAX_RETRIES retries"
    exit 1
  fi

  sleep 1
done
