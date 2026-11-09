#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

usage() {
  cat <<USAGE
Usage: $(basename "$0") [-h|--help] [PATH]

Type check the repository's Python code with mypy --strict.

Without PATH, checks all covered Python code in the repository, regardless of
the current directory. With PATH (a file or directory, relative to the current
directory), only covered files at or under PATH are checked, e.g. from cluster/:
  $(basename "$0") .

The covered locations are listed in scripts/python-locations.sh.
USAGE
}

case ${1-} in
  -h | --help)
    usage
    exit 0
    ;;
  -*)
    usage >&2
    exit 2
    ;;
esac
if [[ $# -gt 1 ]]; then
  usage >&2
  exit 2
fi

root=$(git rev-parse --show-toplevel)
# shellcheck source=scripts/python-locations.sh
source "$root/scripts/python-locations.sh"

files=$(python_files "${1-}")

# One mypy run per directory: scripts in different directories reuse module
# names (and some have hyphens in their names), so they can't share one run.
failed=0
while IFS= read -r dir; do
  mapfile -t dir_files < <(grep -E "^${dir//./\\.}/[^/]+\$" <<<"$files")
  echo "mypy: $dir"
  # --config-file= makes mypy ignore any config file (mypy.ini, setup.cfg,
  # pyproject.toml) in the tree. Such a file can load plugins, which execute
  # arbitrary code, so a PR from a fork could otherwise run code in CI by
  # modifying it.
  (cd "$root" && MYPYPATH=$dir mypy --config-file= --python-version 3.14 --strict --explicit-package-bases "${dir_files[@]}") || failed=1
done < <(sed -E 's|/[^/]+$||' <<<"$files" | sort -u)

exit "$failed"
