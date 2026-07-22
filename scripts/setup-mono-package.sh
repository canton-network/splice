#!/usr/bin/env bash
# Populate daml-ide-mono/daml/ with per-file symlinks into every workspace
# Daml package's source tree. The synthesised daml-ide-mono/daml.yaml is
# checked in and static - this script only regenerates the source tree
# so that VS Code can work on the union as a single package.
#
# Re-run this any time you add a new .daml file to the workspace or add
# a new workspace package. Symlinks are relative, so `mv`-ing the repo
# leaves them working.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
dest="$repo/daml-ide-mono/daml"

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
      ln -sfn "$src/$rel" "$link"
    done
done
