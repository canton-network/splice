#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Populate daml/daml-ide-mono/daml/ with per-file symlinks into every
# workspace Daml package's source tree. daml/daml-ide-mono/daml.yaml is
# checked in and static - this script only regenerates the source tree
# so that VS Code can work on the union as a single package.
#
# Re-run this any time a .daml file is added or removed anywhere in the
# workspace.

set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
dest="$repo/daml/daml-ide-mono/daml"

rm -rf "$dest"
mkdir -p "$dest"

for pkg in "$repo"/daml/*/daml.yaml \
           "$repo"/token-standard/*/daml.yaml \
           "$repo"/token-standard/examples/*/daml.yaml; do
  src=$(dirname "$pkg")/daml
  [ -d "$src" ] || continue
  ( cd "$src" && find . -name '*.daml' -printf '%P\n' ) |
    while IFS= read -r rel; do
      link="$dest/$rel"
      mkdir -p "$(dirname "$link")"
      target=$(realpath --relative-to="$(dirname "$link")" "$src/$rel")
      ln -sfn "$target" "$link"
    done
done
