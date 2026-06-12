..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming


  - Deployment

      - Helm Charts

          - All Helm charts now support overriding full image names.
            It is possible to override the default image names using new Helm values.
            This change helps deployments that require specific naming conventions for images.

      - Docker Images

          - All Splice web UI Docker images have been updated to use the latest nginx-unprivileged base image,
            and switched to the version based on alpine-slim, to improve security and reduce image size.

    - Daml

      - Add a ``transferPreapprovalBaseDuration`` configuration parameter which defines the duration of a ``TransferPreapproval`` that can be requested or renewed for free
        as the traffic costs already cover the costs sufficiently. This parameter defaults to 90 days. This allows creating a preapproval just using the free traffic rate
        which allows bootstrapping a new validator by creating a preapproval and then purchasing CC from an exchange.

        See [CIP 119](https://github.com/canton-foundation/cips/blob/main/cip-0119/cip-0119.md) for more details.

      - Set ``sponsor = validator`` in ``ValidatorLicense`` contracts
        for new validators and ones that report liveness through
        ``ValidatorLicense_ReportActive``. This change was made to
        reduce some confusion around the sponsor having a special role
        for a validator after the initial onboarding.

      - These changes require a Daml upgrade to the following versions:

          ================== =======
          name               version
          ================== =======
          amulet             0.1.20
          amuletNameService  0.1.21
          dsoGovernance      0.1.26
          validatorLifecycle 0.1.7
          wallet             0.1.21
          walletPayments     0.1.20
          ================== =======
