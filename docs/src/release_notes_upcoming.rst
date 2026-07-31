..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - Scan app

        - The ``enable-app-activity-record-and-traffic-ingestion`` and
          ``serve-app-activity-records-and-traffic`` configuration options have been removed. App
          activity records and sequencer traffic are now always ingested, and app activity is
          always computed and served on the corresponding HTTP endpoints.
