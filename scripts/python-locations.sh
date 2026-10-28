#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Sourced by check-python-types.sh and run-python-tests.sh so that both cover
# exactly the same Python code.
#
# Git pathspecs (relative to the repo root) of the Python code that is type
# checked and unit tested. Not covered: canton/ (vendored) and
# scripts/scan-txlog (too many strict-mode errors for now).
# scripts/scan-txlog should be added here once it type checks (issue to be filed).
python_pathspecs=(
  ':(glob)scripts/*.py'
  'scripts/external-signing/*.py'
  'scripts/performance/*.py'
  'scripts/scan-party-monitor/*.py'
  'build-tools/*.py'
  '.github/actions/scripts/*.py'
  'cluster/*.py'
  'docs/src/*.py'
  '.claude/skills/sarif-unused-symbols/scripts/*.py'
)

# Usage: python_files [SUBPATH]
# Prints the covered Python files (repo-root-relative), one per line. If SUBPATH
# (a file or directory, relative to $PWD) is given, only files at or under it.
# Fails if nothing matches.
python_files() {
  local root sub= rel files
  root=$(git rev-parse --show-toplevel)
  if [[ -n ${1-} ]]; then
    sub=$(realpath -m -- "$1")
    [[ $sub == "$root" || $sub == "$root"/* ]] || {
      echo "error: $1 is outside the repository" >&2
      return 1
    }
    rel=${sub#"$root"}
    rel=${rel#/}
    sub=$rel
  fi
  files=$(cd "$root" && git ls-files -- "${python_pathspecs[@]}" | grep -v '^canton/' | grep -v '^scripts/scan-txlog/' | sort)
  if [[ -n $sub ]]; then
    files=$(grep -E "^${sub//./\\.}(/|\$)" <<<"$files" || true)
  fi
  if [[ -z $files ]]; then
    echo "error: no covered Python files found${1:+ under $1}" >&2
    return 1
  fi
  echo "$files"
}
