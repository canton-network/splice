..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Scan app

        - Remove deprecated ``/transactions`` endpoint.

    - Validator app

        - The deprecated ``TransferCommand`` functionality consisting
          of the endpoints
          ``/v0/admin/external-party/transfer-preapproval/prepare-send``,
          ``/v0/admin/external-party/transfer-preapproval/submit-send``
          and the automation to execute transfer commands is now
          disabled by default. If you were still using those switch to
          token standard transfers which also support 24h submission
          delays since `cip 107
          <https://github.com/canton-foundation/cips/blob/63761df732afa139d2977ca1f4908eef19e58d41/cip-0107/cip-0107.md?plain=1#L6>`_.
          If you need some time to migrate, you can temporarily
          reenable it by setting
          ``canton.validator-apps.validator_backend.enable-deprecated-transfer-command-support=true``. The
          functionality is expected to be fully removed in 0.8.0 so
          this only provides a bit more time to migrate but you must
          complete the migration.

    - Deployment

        - The sequencer and mediator can now be configured with independent ``additionalJvmOptions`` via the new ``sequencer.additionalJvmOptions`` and ``mediator.additionalJvmOptions`` values in the ``splice-global-domain`` helm chart.

    - SV app

      - Add support for updating weight via ``UpdateFeaturedAppRight`` governance voting UI.
