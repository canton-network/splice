..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  .. note::

    Next-release notes

  - Deployment

    - Helm

      - Added security contexts for all Helm-based deployments intended for production.
        This improves the security of Kubernetes based deployments.

  - Scan

    - Add a metric for the size of the most recent ACS snapshot

  - Token Standard APIs

    - Add the ``accountInputFieldsToShow`` property in the token metadata
      API (``token-metadata-v1.yaml``).
      This property allows instruments that support only account-ids or only
      account providers to inform wallets of this fact.
      This change is backwards compatible.

  - Daml

    - Adds support for specifying weight on the ``FeaturedAppRight`` contract as described in
      `CIP-0104 amendment <https://github.com/canton-foundation/cips/pull/238>`__.
    - Fix a bug in ``AmuletAllocation``, which prohibited settling V1 amulet allocations when
      using them with the Token Standard V2 feature of setting multiple
      executors via metadata.

    - These changes require a Daml upgrade to the following versions:

        ================== =======
        name               version
        ================== =======
        amulet             0.1.22
        amuletNameService  0.1.23
        dsoGovernance      0.1.28
        validatorLifecycle 0.1.8
        wallet             0.1.23
        walletPayments     0.1.22
        ================== =======
