#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

usage() {
  cat <<USAGE
Usage: $(basename "$0") [-h|--help] [PATH]

Run the unit tests (test_*.py, via unittest) of the repository's Python code.

Without PATH, runs all tests in the repository, regardless of the current
directory. With PATH (a file or directory, relative to the current directory),
only tests in directories with covered files at or under PATH are run, e.g.
from cluster/:
  $(basename "$0") .

The covered locations are listed in scripts/python-locations.sh. This executes
the code under test, so it is not part of the static checks.
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

failed=0
ran=0
while IFS= read -r dir; do
  echo "unittest: $dir"
  ran=1
  (cd "$root" && python3 -m unittest discover -s "$dir") || failed=1
done < <(grep -E '/test_[^/]*\.py$' <<<"$files" | sed -E 's|/[^/]+$||' | sort -u)

if [[ $ran == 0 ]]; then
  echo "error: no tests found${1:+ under $1}" >&2
  exit 1
fi
exit "$failed"
