..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Scan UI

      - Bring back the governance page that was removed in release 0.5.18.

    - Wallet UI

      - Fix a corner case in the wallet Allocations UI where invalid values would be passed to ``/v0/allocations`` when creating allocations from allocation requests.
        This could manifest as a browser error when clicking ``Accept`` on an allocation request.
