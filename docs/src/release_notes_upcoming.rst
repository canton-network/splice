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

      - Validator

          - The ``migration.id`` value was removed from the validator (validator app) helm chart and is no longer
            supported for docker-compose deployments. The validator now resolves the synchronizer migration id
            automatically at start-up from its database. The value must be removed from both the helm chart and
            the docker-compose configuration.

      .. Important::

          The migration id must still be kept for participant database naming for backwards compatibility (``persistance.databaseName`` helm value,
          ``CANTON_PARTICIPANT_POSTGRES_DB`` docker compose env variable) to ensure the participant uses the currently configured database.

  - Scan

    - The following deprecated endpoints have been removed from the public API:

        - ``/v0/activities``

  - Bug fixes

    - Validator

        - Fixed a bug where validators using the ``bft-custom`` scan client configuration
          would incorrectly attempt to establish scan connections with all scan nodes during
          the validator startup. The scan client now strictly confines all scan connections to
          configured, trusted SV endpoints.

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
