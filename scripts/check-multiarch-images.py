#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Fail if any digest-pinned container image is not a multi-arch digest."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from dockerfile_parse import DockerfileParser

# Matches concrete image references that pin a digest, e.g. ubuntu:24.04@sha256:440dcf...
# This intentionally does not match references that use variable digests
# Note: it also does not support registry ports in the image name.
IMAGE_REF_RE = re.compile(
    r"([a-zA-Z0-9][a-zA-Z0-9._/-]*):([^@\s]+)@sha256:([a-f0-9]{64})"
)

# Some Dockerfiles reuse the ARG name `image_sha256` with a different value for each image
IMAGE_SHA256_MAP = {
    "cluster/images/canton/Dockerfile": "CANTON_BASE_IMAGE_SHA256",
    "cluster/images/canton-mediator/Dockerfile": "CANTON_MEDIATOR_IMAGE_SHA256",
    "cluster/images/canton-participant/Dockerfile": "CANTON_PARTICIPANT_IMAGE_SHA256",
    "cluster/images/canton-sequencer/Dockerfile": "CANTON_SEQUENCER_IMAGE_SHA256",
}


def _setup_env() -> None:
    """Export lowercase aliases for Dockerfile ARG references"""
    os.environ.setdefault("canton_version", os.environ.get("CANTON_VERSION", ""))
    os.environ.setdefault(
        "cometbft_version", os.environ.get("COMETBFT_RELEASE_VERSION", "")
    )
    os.environ.setdefault("cometbft_sha", os.environ.get("COMETBFT_IMAGE_SHA256", ""))


def _image_sha256_for(file: str) -> str | None:
    key = IMAGE_SHA256_MAP.get(file)
    if not key:
        return None
    return os.environ.get(key)


def _inspect(ref: str) -> bool:
    """Return True if the digest resolves to a multi-arch manifest list/index.

    Uses `docker buildx imagetools inspect`. For a multi-arch digest
    `.image.architecture` is null; for a single-arch digest it is set.
    """
    try:
        raw = subprocess.run(
            ["docker", "buildx", "imagetools", "inspect", ref, "--format", "{{json .}}"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except subprocess.CalledProcessError as e:
        print(f"ERROR: unable to inspect {ref}: {e.stderr.strip() or e}", file=sys.stderr)
        return False

    try:
        info = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"ERROR: invalid JSON from docker buildx for {ref}: {e}", file=sys.stderr)
        return False

    return info.get("image", {}).get("architecture") is None


def _extract_refs_from_text(text: str) -> set[str]:
    """Extract full image:tag@sha256:<digest> references from arbitrary text."""
    return {
        f"{image}:{tag}@sha256:{digest}"
        for image, tag, digest in IMAGE_REF_RE.findall(text)
    }


def _refs_from_dockerfile(path: Path) -> set[str]:
    """Parse a Dockerfile and resolve any variable references in FROM images."""
    # Set image_sha256 for this specific Dockerfile before resolving variables.
    image_sha256 = _image_sha256_for(str(path))
    if image_sha256:
        os.environ["image_sha256"] = image_sha256
    elif "image_sha256" in os.environ:
        del os.environ["image_sha256"]

    refs: set[str] = set()
    parser = DockerfileParser(str(path))
    for instruction in parser.structure:
        if instruction["instruction"] != "FROM":
            continue
        value = instruction["value"]
        # Drop stage aliases: "image:tag AS stage"
        value = re.sub(r"\s+AS\s+\S+$", "", value, flags=re.IGNORECASE)
        # Resolve $var / ${var} references using environment variables.
        resolved = os.path.expandvars(value)
        refs.update(_extract_refs_from_text(resolved))
    return refs


def _refs_from_plain_file(path: Path) -> set[str]:
    """Scan a non-Dockerfile for digest-pinned image references."""
    refs: set[str] = set()
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            if line.lstrip().startswith("#"):
                continue
            refs.update(_extract_refs_from_text(line))
    return refs


def _tracked_dockerfiles() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "--", "**/Dockerfile"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.splitlines()


def _tracked_files_with_digest_refs() -> list[str]:
    result = subprocess.run(
        ["git", "grep", "-l", "-E", r"@sha256:[a-f0-9]{64}", "--", ":!**/Dockerfile"],
        check=False,
        capture_output=True,
        text=True,
    )
    return result.stdout.splitlines()


def main() -> int:
    _setup_env()

    checked: set[str] = set()
    failures = 0

    # Dockerfiles may use variable digests (e.g. ARG $cometbft_sha), so parse all of them.
    for file in _tracked_dockerfiles():
        path = Path(file)
        if not path.is_file():
            continue
        refs = _refs_from_dockerfile(path)
        for ref in refs:
            if ref in checked:
                continue
            checked.add(ref)
            if _inspect(ref):
                print(f"OK: {ref} (from {file}) is multi-arch")
            else:
                print(f"ERROR: {ref} (from {file}) is not pinned to a multi-arch digest")
                failures += 1

    # Other files only need to be inspected if they contain a concrete digest reference.
    for file in _tracked_files_with_digest_refs():
        path = Path(file)
        if not path.is_file():
            continue
        refs = _refs_from_plain_file(path)
        for ref in refs:
            if ref in checked:
                continue
            checked.add(ref)

            print(f"Inspecting {ref} (from {file})")
            if _inspect(ref):
                print(f"  OK: {ref} is multi-arch")
            else:
                print(f"ERROR: {ref} (from {file}) is not pinned to a multi-arch digest")
                failures += 1

    if failures > 0:
        print(f"FAIL: {failures} image(s) are not pinned to multi-arch digests")
        return 1

    print("OK: all pinned images are multi-arch")
    return 0


if __name__ == "__main__":
    sys.exit(main())
