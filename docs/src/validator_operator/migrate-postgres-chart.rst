..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _migrate-postgres-chart:

Moving off the ``splice-postgres`` chart
========================================

The ``splice-postgres`` Helm chart is deprecated and will be removed
(PostgreSQL 14 reaches end-of-life on 2026-11-12). Splice does not maintain a
PostgreSQL chart. Use any well-known PostgreSQL chart, a managed service, or
your existing database platform. Splice itself uses
`bitnami/postgresql <https://github.com/bitnami/charts/tree/main/bitnami/postgresql>`_
for its non-production deployments, and the example values files target it.
See `issue #1129 <https://github.com/hyperledger-labs/splice/issues/1129>`_.

.. note::

   This page is guidance, **not a validated end-to-end runbook**. There is no
   Splice-specific migration tooling. Always rehearse any data migration in a
   non-production environment first and rely on your own tested backups.

Choosing a replacement
----------------------

- ``bitnami/postgresql`` — see the example values in
  ``splice-node/examples/sv-helm/postgres-values-*.yaml`` and the chart's own
  `documentation <https://github.com/bitnami/charts/tree/main/bitnami/postgresql>`_.
- Any other PostgreSQL Helm chart, or a managed PostgreSQL (e.g. Cloud SQL).

Migrating existing data
-----------------------

Use standard PostgreSQL logical backup/restore — there is nothing
Splice-specific about the mechanics:

- `pg_dump <https://www.postgresql.org/docs/current/app-pgdump.html>`_ /
  `pg_restore <https://www.postgresql.org/docs/current/app-pgrestore.html>`_
- `Backup and Restore <https://www.postgresql.org/docs/current/backup.html>`_

Splice-specific considerations
------------------------------

These are the points generic PostgreSQL/chart docs won't cover:

- **Stop writes before dumping.** Scale every Canton app that uses the database
  to zero replicas before ``pg_dump``. Canton uses Flyway, and dumping mid-write
  can capture an inconsistent ``flyway_schema_history``, after which the app will
  refuse to start.
- **Match major versions.** Use a ``pg_dump``/``pg_restore`` client matching the
  target server's major version (PostgreSQL 17 for the bitnami examples).
- **Service hostname must not change.** Canton apps connect to a fixed host
  (``persistence.host``, e.g. ``apps-pg``). The replacement must expose the same
  Kubernetes Service name — with bitnami, set ``fullnameOverride`` to that name.
- **The app user needs ``CREATEDB``.** Canton init containers run
  ``CREATE DATABASE``. In ``splice-postgres`` ``cnadmin`` was a superuser; on a
  generic chart it is a normal user, so grant ``CREATEDB`` (the bitnami examples
  do this via an ``initdb`` script).
- **Secret shape.** Apps read the password from the key ``postgresPassword``. The
  bitnami examples set ``auth.enablePostgresUser: false`` so your existing
  single-key secret is reusable as-is.
- **Default database** is ``cantonnet``; additional per-component databases are
  created automatically at startup.

Value mapping (``splice-postgres`` → ``bitnami/postgresql``)
------------------------------------------------------------

=============================  ==========================================
splice-postgres                bitnami/postgresql
=============================  ==========================================
``db.volumeSize``              ``primary.persistence.size``
``db.volumeStorageClass``      ``primary.persistence.storageClass``
``db.maxConnections``          ``primary.extendedConfiguration``
``db.maxWalSize``              ``primary.extendedConfiguration``
``persistence.secretName``     ``auth.existingSecret``
``resources``                  ``primary.resources``
=============================  ==========================================
