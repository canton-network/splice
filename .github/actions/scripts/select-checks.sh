#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

mode="${1:?usage: $0 static|build}"
event="${EVENT_NAME:-}"
base_sha="${BASE_SHA:-}"

static_groups=(python daml gha grafana base_images npm_names)
full_sbt_cmd="lintStatic"

emit_all() {
  case "$mode" in
    static)
      for g in "${static_groups[@]}"; do echo "$g=true"; done
      echo "sbt_cmd=$full_sbt_cmd"
      ;;
    build)
      echo "test_config=true"
      echo "npm_lint= apps-frontends/npmLint pulumi/npmLint load-tester/npmLint party-allocator/npmLint"
      ;;
  esac
  exit 0
}

[ "$event" = "pull_request" ] || { echo "Not a pull request ($event): running all checks" >&2; emit_all; }

if git rev-parse --verify -q origin/main > /dev/null && base=$(git merge-base origin/main HEAD 2> /dev/null); then
  :
elif [ -n "$base_sha" ] && git fetch -q --no-tags --depth=1 --filter=blob:none origin "$base_sha" 2> /dev/null; then
  base="$base_sha"
else
  echo "No base commit found: running all checks" >&2
  emit_all
fi

changes=$(git diff --name-status --no-renames "$base" HEAD)
changed=$(echo "$changes" | awk 'NF {print $NF}')
echo "Changed files relative to $base:" >&2
echo "$changed" | sed 's/^/  /' >&2

matches() { echo "$changed" | grep -qE "$1"; }

if matches '^(nix/|\.github/actions/scripts/select-checks\.sh$|\.github/workflows/build\.(static_tests|compile_checks)\.yml$|build\.sbt$|project/)'; then
  echo "Build, tooling or check selection changed: running all checks" >&2
  emit_all
fi

flag() { if matches "$1"; then echo "$2=true"; else echo "$2=false"; fi; }

case "$mode" in
  static)
    flag '(\.py$|^scripts/python-checks)' python
    flag '(\.daml$|(^|/)daml\.yaml$|^scripts/check-daml-)' daml
    flag '^(\.github/|gha-scripts/|scripts/(actionlint|zizmor)\.sh$)' gha
    flag '^(cluster/pulumi/observability/grafana-dashboards/|scripts/check-grafana-dashboards\.sh$)' grafana
    flag '((^|/)Dockerfile$|^scripts/check-base-images\.sh$)' base_images
    flag '((^|/)package\.json$|^scripts/check-npm-package-names\.py$)' npm_names

    shell_changed=false
    if matches '(\.sh$|(^|/)\.envrc|^\.pre-commit-config\.yaml$)'; then
      shell_changed=true
    else
      while IFS= read -r f; do
        if [ -f "$f" ] && [[ "$(basename "$f")" != *.* ]] && head -n1 "$f" | grep -qE '^#!.*\b(ba|z|da|k)?sh\b'; then
          shell_changed=true
          break
        fi
      done <<< "$changed"
    fi

    parts=()
    if matches '(\.(scala|sbt)$|^\.scalafmt\.conf$)'; then parts+=("lintStaticFormat"); fi
    if [ "$shell_changed" = true ]; then parts+=("runShellcheck"); fi
    if matches '(^|/)package\.json$'; then parts+=("syncpackCheck"); fi
    if matches '^daml/'; then parts+=("illegalDamlReferencesCheck"); fi
    if matches '\.(scala|daml|rst|ts|tsx|js|py|sh)$' || [ "$shell_changed" = true ]; then parts+=("headerCheck"); fi
    sbt_cmd=""
    if [ ${#parts[@]} -gt 0 ]; then sbt_cmd=$(printf '; %s ' "${parts[@]}"); sbt_cmd="${sbt_cmd% }"; fi
    echo "sbt_cmd=$sbt_cmd"
    ;;
  build)
    if echo "$changes" | awk '$1 ~ /^[ADR]/ {print $NF}' | grep -qE '/src/test/' || matches '^test-.*\.log$'; then
      echo "test_config=true"
    else
      echo "test_config=false"
    fi
    npm_lint=""
    if matches '^[^/]+\.(json|js|cjs|mjs)$'; then
      npm_lint=" apps-frontends/npmLint pulumi/npmLint load-tester/npmLint party-allocator/npmLint"
    else
      if matches '^apps/.*\.(ts|tsx|js|jsx|cjs|mjs|json|yaml|yml|css|scss|html)$|^apps/[^/]+$|^daml/|^token-standard/'; then npm_lint+=" apps-frontends/npmLint"; fi
      if matches '^cluster/'; then npm_lint+=" pulumi/npmLint"; fi
      if matches '^load-tester/'; then npm_lint+=" load-tester/npmLint"; fi
      if matches '^party-allocator/|^daml/'; then npm_lint+=" party-allocator/npmLint"; fi
    fi
    echo "npm_lint=$npm_lint"
    ;;
  *)
    echo "unknown mode: $mode" >&2
    exit 1
    ;;
esac
