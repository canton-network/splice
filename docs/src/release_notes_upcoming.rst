..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Scan app

.. .. release-notes:: Upcoming

        - The ``app_activity_record_store`` table has been modified to avoid unexpected DB performance issues.
          This required clearing the existing data in this table which has been ingested since the ``0.5.18`` release.
          This impacts the data being served via the experimental field ``app_activity_records`` on the ``/v0/events`` and ``/v0/events/{update_id}`` endpoints.
          Specifically the ``app_activity_records`` field will not contain the
          data which has been provided for the events which happened between the ``0.5.18`` and this release.
          Note that the ``app_activity_records`` data already provided for events during this period is correct
          and the network explorers who have ingested this data should keep a copy of it.

     - SV app

       - Bump the minimum DAR versions to the ones from splice 0.5.7 which introduced the development fund manager as
         downgrades to earlier versions already fail. SV app automation will unvet those on the SV nodes.

         The concrete versions are:

         ================== =======
         name               version
         ================== =======
         amulet             0.1.15
         amuletNameService  0.1.16
         dsoGovernance      0.1.21
         validatorLifecycle 0.1.6
         wallet             0.1.15
         walletPayments     0.1.15
         ================== =======

    - SV deployment

        - Updated ``participantAddress``` in `scan-values.yaml` and ``sv-validator-values.yaml`` to use the participant adress with a migration suffix.
          Ensure you override this with the correct helm install name for the participant ore reinstall the participant without a migration suffix ().

    - Token Standard V2 (CIP-112)

      - Notable callouts for Amulet changes:
          - add a ``meta : Optional Metadata`` field to the ``AmuletRules.TransferOutput`` type and
            the ``TransferPreapproval_SendV2`` choice
          - properly classify the burn of ANS in the V2 token standard transaction history

      - Add preview of the V2 token standard APIs and implement them for Amulet

      - Add support for creating Allocations V2 of Amulet in the Splice Amulet Wallet UI.
        This is meant for users that create the allocations for an allocation request
        using the registry specific UIs for each asset. The Amulet Wallet UI
        therefore does not archive the V2 AllocationRequest when creating the
        Amulet Allocation for it, so that the allocation request is visible in the other
        registry UIs as well.

        For creating all allocations in a single transaction `as documented in CIP-112 <https://github.com/canton-foundation/cips/blob/main/cip-0112/cip-0112.md#423-traders-accept-allocation-requests-and-create-allocations>`__, we recommend using
        a token standard v2 wallet UI that uniformly supports all V1 and V2 assets.

      .. TODO(#4707): add callouts for wallets, explorers, SVs, validator operators, app operators as needed
      .. TODO(#4707): add Daml versions of token standard to release notes
