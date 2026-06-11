..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - Deployment

      - The ``migration.id`` value is no longer required by the SV (sv, validator, scan apps) and validator (validator app) helm charts and has been removed.
        These apps now resolve the synchronizer migration id automatically at start-up. For the scan helm chart the
        ``migration.id`` value is now optional and only needs to be set to bootstrap a scan that does not yet have any
        migration id in its database (e.g. the network-founding or a freshly joining scan).

      - SV

          - The ``migration.id`` value was removed from the SV helm charts (sv, validator, scan apps).
            These apps now resolve the synchronizer migration id automatically at start-up from their database.
            A freshly joining scan that does not yet have any migration id in its database bootstraps it from the
            scan of the SV sponsoring the onboarding, configured via the new optional ``sponsorScanUrl`` value in the
            scan helm chart.

          - Increase ``sv-app`` default memory requests from 3Gi to 6Gi to ensure the max heap size fits in the requests and to avoid OOM evictions.

      - Validator

          - The ``migration.id`` value was removed from the validator (validator app) helm chart. For docker-compose
            deployments the ``-m <migration_id>`` flag of the validator ``start.sh`` script is now optional (if you deployed your validator before this change you **must** keep this flag set to the existing value). The validator
            resolves the synchronizer migration id automatically at start-up from its database. The migration id is now only
            used to name the participant database for backwards compatibility: if provided, the database
            ``participant-<migration_id>`` is used (set this to the migration id you previously deployed with); if omitted,
            the database ``participant`` is used, which is recommended for new deployments.

      .. Important::

          The migration id must still be kept for participant database naming for backwards compatibility (``persistence.databaseName`` helm value,
          ``CANTON_PARTICIPANT_POSTGRES_DB`` docker compose env variable) to ensure the participant uses the currently configured database.

          For docker-compose validator deployments, pass the same ``-m <migration_id>`` you previously deployed with to keep
          using the existing ``participant-<migration_id>`` database, or omit it on new deployments to use the ``participant`` database.

  - Scan

    - The following deprecated endpoints have been removed from the public API:

        - ``/v0/activities``

  - Bug fixes

    - Validator

        - Fixed a bug where validators using the ``bft-custom`` scan client configuration
          would incorrectly attempt to establish scan connections with all scan nodes during
          the validator startup. The scan client now strictly confines all scan connections to
          configured, trusted SV endpoints.
