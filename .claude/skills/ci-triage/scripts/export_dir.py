# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Where the host-side export scripts write: log/ci-triage-export/ of the splice checkout in $SPLICE_ROOT.

Fixed, not an argument, and independent of the working directory, so that a command the user runs without reading
it closely cannot write elsewhere. SPLICE_ROOT comes from the splice direnv (`direnv exec . <script>`). The sandbox
reads the directory as /run/sandbox/source/log/ci-triage-export/.
"""
import os
import pathlib


def export_root() -> pathlib.Path:
    root = os.environ.get("SPLICE_ROOT")
    if not root:
        raise SystemExit("SPLICE_ROOT is not set; run this from the splice checkout with `direnv exec . <script>`")
    return pathlib.Path(root) / "log" / "ci-triage-export"


def ref_dir(ref: int) -> pathlib.Path:
    """The directory for one cn-test-failures issue; ref is an int, so it cannot leave export_root()."""
    return export_root() / str(ref)
