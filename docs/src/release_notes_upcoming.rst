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

  - Deployment

    - Helm

      - Added security contexts for all Helm-based deployments intended for production.
        This improves the security of Kubernetes based deployments.

  - Scan

    - Add a metric for the size of the most recent ACS snapshot

  - Daml

    - Adds support for specifying weight on the ``FeaturedAppRight`` contract as described in
      `CIP-0104 amendment <https://github.com/canton-foundation/cips/pull/238>`__.

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
