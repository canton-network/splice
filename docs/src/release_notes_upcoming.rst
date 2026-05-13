..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Wallet & CNS UIs

      - The wallet and CNS UIs now support optionally configuring the OAuth token scope, to support IAM providers that require doing so.

    - Scan UI

      - Bring back the governance page that was removed in release 0.5.18.

    - Wallet UI

      - Fix a corner case in the wallet Allocations UI where invalid values would be passed to ``/v0/allocations`` when creating allocations from allocation requests.
        This could manifest as a browser error when clicking ``Accept`` on an allocation request.

    - SV app

      - SV participants now use the public sequencer URL instead of
        the internal one to connect to their sequencer. This avoids
        some redundant reconnects around LSUs where the participant
        LSU automation would set the public URL while the SV app would
        set the internal one.

        The prior behavior can be set recovered by setting
        ``canton.sv-apps.sv.use-internal-sequencer-api = true``
        through an ``ADDITIONAL_CONFIG`` environment variable. LSUs
        will still work but be slightly slower due to extra
        reconnects.

    - SV deployment

        - Splice Info endpoint now includes ``/runtime/status.json`` which provides status of core components (sv, scan and mediator at this moment) refreshed
          every 60 seconds. ``splice-info`` helm chart now requires ``runtimeDetails.migrationId`` to be specified.

    - Scan

      - The app activity records computation has been modified to exclude the transactions submitted by SVs,
        as the SVs don't burn traffic and transactions submitted by them should not generate
        traffic-based app rewards as specified in CIP-0104.

        The data provided by the experimental ``app_activity_records`` field of ``GET /v0/events/{update-id}``
        and ``POST /v0/events`` endpoints prior to this release may have attributed app-activity to
        SV submitted transactions, and such app activity records should be considered incorrect.
        App activity records data provided for other transactions is valid.

        The ``app_activity_record_store`` table has been truncated to remove the records computed with the earlier logic,
        and both the endpoints will not provide app ``app_activity_records`` for events prior to this release.
