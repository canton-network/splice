..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

      - PostgreSQL Data Checksums

          - `PostgreSQL data checksums <https://www.postgresql.org/docs/14/checksums.html>`_ are now
            **enabled by default** for all PostgreSQL databases created by Splice. This applies to the
            in-cluster Postgres Helm chart (``splice-postgres``) and the Docker-Compose based deployments
            (SV, validator and LocalNet). Data checksums help detect on-disk data corruption early.

            .. warning::

               Data checksums can only be enabled when a database cluster is first initialized
               (``initdb``). **Enabling them by default
               only affects freshly initialized databases.** Existing deployments are *not* automatically
               migrated and will continue to run without data checksums until they are explicitly enabled.

               Operators of existing deployments should enable checksums on
               their existing databases out-of-band, for example by stopping PostgreSQL and running
               ``pg_checksums --enable`` against the data directory (see the
               `pg_checksums documentation <https://www.postgresql.org/docs/14/app-pgchecksums.html>`_).

          - Splice nodes now perform a best-effort check at startup and log a ``WARN`` if PostgreSQL
            data checksums are not enabled on their backing database. This check never fails startup.

      - Database instance locking

          - Splice apps now take a PostgreSQL instance lock on startup so
            that only one instance runs against a given database at a time, guarding against data
            corruption from an accidentally duplicated app. Enabled by default
            (``instanceLockEnabled = true``); set it to ``false`` only if you deliberately point
            multiple apps at one shared database.

      - Validator, sv and scan app

          - Support passing client-id and secret through Http Basic Authentication instead of in the request body. For backwards compatibility this is disabled by default.
            To enable it set an environment variable ``ADDITIONAL_CONFIG_HTTP_BASIC_AUTH=canton.validator-apps.sv.participant-client.ledger-api.auth-config.http-basic-auth = true``.
