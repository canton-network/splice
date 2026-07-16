..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  .. note::

    Next-release notes

  - Validator

    - Unsupported package versions are now automatically unvetted by the validator package vetting trigger,
      aligning validator behavior with SVs.

      You can disable validator unvetting by setting:

      .. code-block:: yaml

        - name: ADDITIONAL_CONFIG_UNSUPPORTED_DARS_UNVETTING
          value: |
            canton.validator-apps.validator_backend.parameters.enabled-features.enable-validator-dars-unvetting = false

    - The ``splice-postgres`` Helm chart is deprecated and will not be supported after
      2026-11-12, the PostgreSQL 14 end-of-life date. Published chart versions remain
      available, but receive no further updates after that date, and no new chart versions
      will be published after 2026-10-12. Run Splice against a PostgreSQL instance you
      provision yourself; a managed service such as Amazon RDS or Google Cloud SQL is
      recommended. Follow the `migration guide <https://docs.canton.network/global-synchronizer/production-operations/validator-postgres-migration>`__ to move the data
      of an existing node before that date.

  - SV app

    - Add support for specifying weight in ``GrantFeaturedAppRight`` governance voting UI.

  - Observability

    - Remove the ``splice_store_acs_size`` gauge metric by
      ``splice_store_acs_size_increase`` and
      ``splice_store_acs_size_decrease`` counters to fix a performance
      issue in initializing the metric on startup. Note that these
      metrics can only be used to track changes but not absolute
      sizes. ``splice_history_acs_snapshots_snapshot_size`` provides
      an absolute size for SVs, however it counts rows not contracts so it
      counts contracts with multiple stakeholders multiple times.

  - Deployment

    - splice-info

      - ``/runtime/status.json`` now includes reachability for scan and sequencer (0 is good, 1 is lagging
        behind, 2 is unreachable, 3 is lagging behind and unreachable).
