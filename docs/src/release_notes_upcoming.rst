..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

      - Deployment

          - Helm

              - Added support for `secretOverrides` for Helm charts,
                allowing node operators to inject raw configuration strings for external secret managers like HashiCorp Vault.
                To use this, you must have the corresponding mutating webhook, injector, or tool hook installed in your cluster
                to dynamically resolve raw string references at runtime.

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


      - SV app

        - Now reports a warning if a mismatch is detected in ``Confirmation``
          for the following actions, as these are submitted by automations and no disagreement is expected:
          ``CRARC_MiningRound_Archive``, ``CRARC_MiningRound_StartIssuing`` and
          ``CRARC_StartProcessingRewardsV2``

    - Token Standard V2 (CIP-112)

      - Notable callouts for Amulet changes:
          - add support for single-step transfers via the V2 transfer factory interface for
            cases where both sender and receiver authorization is available
          - add a ``meta : Optional Metadata`` field to the ``AmuletRules.TransferOutput`` type and
            the ``TransferPreapproval_SendV2`` choice
          - properly classify the burn of ANS in the V2 token standard transaction history
          - change ``V1.AllocationFactory_Allocate`` to enforce that ```settlement.allocateBefore``
            is strictly before ``settlement.settleBefore``
          - introduce ``AmuletConfig.tokenStandardMaxTTL`` config parameter
            with a default value of 90 days
          - limit the maximum life-time of transfer instructions to ``tokenStandardMaxTTL``
            to prevent excessively long-lived transfer instructions
          - allow the DSO to expire both V1 and V2 amulet allocations older than
            ``tokenStandardMaxTTL``, even if the settlement deadline has not yet passed
          - support creating Amulet allocations for burn


      - Add preview of the V2 token standard APIs and implement them for Amulet

      - Add support for creating Allocations V2 of Amulet in the Splice Amulet Wallet UI.
        This is meant for users that create the allocations for an allocation request
        using the registry specific UIs for each asset. The Amulet Wallet UI
        therefore does not archive the V2 AllocationRequest when creating the
        Amulet Allocation for it, so that the allocation request is visible in the other
        registry UIs as well.

        For creating all allocations in a single transaction `as documented in CIP-112 <https://github.com/canton-foundation/cips/blob/main/cip-0112/cip-0112.md#423-traders-accept-allocation-requests-and-create-allocations>`__, we recommend using
        a token standard v2 wallet UI that uniformly supports all V1 and V2 assets.

      - The dar ``splice-api-token-transfer-events-v2`` and its dependencies (namely, Token Standard V2 dars) are now uploaded by default on all validator nodes.

      .. TODO(#4707): add callouts for wallets, explorers, SVs, validator operators, app operators as needed
      .. TODO(#4707): add Daml versions of token standard to release notes
