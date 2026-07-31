..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV app

        - The governance UI no longer stops an SV from casting or changing its vote once a
          proposal's target effective time has passed. Votes are now accepted for as long as the
          vote request is open, matching what the ledger allows. This makes it possible to reject a
          proposal whose action fails to execute and which would otherwise remain in flight
          indefinitely.
