..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  - Deployment

      - SV

          - The ``migration.id`` value was removed from the SV helm charts (sv, validator, scan apps).
            These apps now resolve the synchronizer migration id automatically at start-up from their database.
            A freshly joining scan that does not yet have any migration id in its database bootstraps it from the
            scan of the SV sponsoring the onboarding, configured via the new optional ``sponsorScanUrl`` value in the
            scan helm chart.

      - Validator

          - The ``migration.id`` value was removed from the validator (validator app) helm chart and is no longer
            supported for docker-compose deployments. The validator now resolves the synchronizer migration id
            automatically at start-up from its database. The value must be removed from both the helm chart and
            the docker-compose configuration.

      .. Important::

          The migration id must still be kept for participant database naming for backwards compatibility (``persistance.databaseName`` helm value,
          ``CANTON_PARTICIPANT_POSTGRES_DB`` docker compose env variable) to ensure the participant uses the currently configured database.
