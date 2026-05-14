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

    - ``canton.scan-apps.scan-app.activity-ingestion-user-version`` configuration setting
      has been added to control the activity record ingestion version.
      Incrementing this value causes the Scan app to record a new completeness
      boundary; reward accounting excludes rounds before it, while existing
      activity records are retained.
      See the :ref:`SV Operations docs <sv-reingest-scan-stores>` for more details.

        - The ``app_activity_record_store`` table has been modified to avoid unexpected DB performance issues.
          The corresponding DB migration truncates the existing data in this table which has been ingested since the ``0.5.18`` release, which is OK as we are still in the preview phase of CIP-104.
          This impacts the data being served via the experimental field ``app_activity_records`` on the ``/v0/events`` and ``/v0/events/{update_id}`` endpoints.
          Specifically the ``app_activity_records`` field will not contain the
          data which has been provided for the events which happened between the ``0.5.18`` and this release.
          Note that the ``app_activity_records`` data already provided for events during this period is correct
          and the network explorers who have ingested this data should keep a copy of it.
          The downstream reward-accounting tables are also cleared as part of this change.

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
