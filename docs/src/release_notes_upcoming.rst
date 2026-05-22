..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Observability

       - The release bundle now ships a separate Grafana dashboard folder ``validator-grafana-dashboards/`` for Validator operators.

    - ``canton.scan-apps.scan-app.activity-ingestion-user-version`` configuration setting has been added to control the activity record ingestion version for the purpose of recovering from unexpected operational issues.
      Incrementing this value causes the Scan app to record a new app activity record completeness lower bound. Reward accounting excludes rounds before this boundary, even though their activity records are retained. Thus bumping the user version has the same effect as reinitializing the app activity record computation from the time of the bump onwards.
      See the :ref:`SV Operations docs <sv-reingest-scan-stores>` for more details.

    - Scan app
        - The ``app_activity_record_store`` table has been modified to improve DB performance.
          The corresponding DB migration truncates the existing data in this table which has been ingested since the ``0.5.18`` release, which is OK as we are still in the preview phase of CIP-104.
          The downstream reward-accounting tables are also cleared as part of this change.

    - SV app

        - Support a list of ``additionalLegacy`` synchronizers for the case where more than one legacy synchronizer must be kept alive at a given point.

    - Validator app

      - Added support for ``bft-custom`` scan and sequencer configurations in Docker Compose based validator deployment.

      - Stop recording update history. This data was never exposed
        beyond undocumented SQL tables so there should be no effect
        other than the database growing at a slower rate.

        If you need to access data for any of the parties on your node
        use the ledger API.

    - SV deployment

        - The sequencer and mediator helm charts are now setting the same ``fsGroup``, ``runAsUser``, and ``runAsGroup``
          in the security context of the pods as the participant, validator app, and sv app charts.
