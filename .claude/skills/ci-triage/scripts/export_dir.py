# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Where the host-side export scripts write: log/ci-triage-export/ of the splice checkout that holds them.

Fixed, not an argument, and independent of the working directory, so that a command the user runs without reading
it closely cannot write elsewhere. The sandbox reads it as /run/sandbox/source/log/ci-triage-export/.
"""
import pathlib

# scripts -> ci-triage -> skills -> .claude -> checkout root
EXPORT_ROOT = pathlib.Path(__file__).resolve().parents[4] / "log" / "ci-triage-export"


def ref_dir(ref: int) -> pathlib.Path:
    """The directory for one cn-test-failures issue; ref is an int, so it cannot leave EXPORT_ROOT."""
    return EXPORT_ROOT / str(ref)
