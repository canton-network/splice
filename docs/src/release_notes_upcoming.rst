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

        - Verdicts returned by the ``/v0/events`` and ``/v0/events/{update_id}`` endpoints now
          include an optional ``round_number`` field.The value of this field may be ``null``,
          or differ among SVs (``null`` value and a valid round), for a brief period till
          this release is adopted by all SVs.

    - SV App

        - The SV app now exposes a ``splice.sv_vote_requests.active`` metric counting the active
          vote requests by their state relative to the SV (``action_needed``, ``in_progress``,
          ``ready_to_close``), allowing SV operators to alert on vote proposals that require
          their vote.
