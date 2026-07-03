..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV app

        - Network banner now always shows. The network name is derived from the scan node's public URL:
          MainNet/TestNet/DevNet/ScratchNet from the cluster subdomain, LocalNet for localhost, and a
          capitalized fallback otherwise (Unknown Network when no scan URL is available).
