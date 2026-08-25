..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

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

    - Validator

        - *breaking*: The deprecated ``TransferCommand`` functionality
          enabled by ``canton.validator-apps.validator_backend.enable-deprecated-transfer-command-support=true``
          has been fully removed. Migrate to token standard transfers
          and remove the flag.
