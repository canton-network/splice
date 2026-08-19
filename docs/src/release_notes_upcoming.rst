..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - PostgreSQL 18

        - Splice now officially supports PostgreSQL 18.
          ⚠️ Note that that PostgreSQL 14, which was the default until now, will reach End of Life on November 12, 2026.
          You should upgrade before that date.

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

    - Daml

        - .. warning::

             **Action required for app devs:** apps with Daml code that statically depends on
             ``splice-amulet`` should recompile against the new version. When the SVs set
             ``minDevelopmentFundMintingDelay`` or ``developmentFundManagerBlacklist``, those
             values block downgrades to package versions that do not enforce them. From that
             point, code that still links against the old ``AmuletConfig`` stops working.

        - Add an optional ``mintAfter`` field to ``DevelopmentFundCoupon``. Add
          ``minDevelopmentFundMintingDelay`` and ``developmentFundManagerBlacklist`` to
          ``AmuletConfig``.
          The SVs need to set a minimum delay between coupon allocation and minting
          by the beneficiary. They can also block minting of coupons from blacklisted
          fund managers.

          This change addresses suggestion QS2 from Quantstamp in the
          `Canton Coin 2026 audit <https://certificate.quantstamp.com/full/canton-coin-2026-audit/7719ab33-0012-4bb6-bf6c-ce3c0335a93d/index.html#suggestions-qs2>`_.

          This release does not change existing behavior. Both config fields default to unset.
          The SVs enforce the minting delay when they set ``minDevelopmentFundMintingDelay``
          to a non-zero value. They block minting of the coupons of a development fund manager
          when they add that manager to ``developmentFundManagerBlacklist``. While
          ``minDevelopmentFundMintingDelay`` stays unset, callers can allocate coupons
          without ``mintAfter`` and mint them immediately, as before.

          Callers of ``AmuletRules_AllocateDevelopmentFundCoupon`` must change their call sites
          before the SVs set a non-zero delay. After that, the choice rejects allocations
          that omit ``mintAfter``. Coupons allocated before that vote have no ``mintAfter``.
          A beneficiary can mint those coupons with no delay.