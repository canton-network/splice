..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - Deployment

        - All Splice Helm charts now set ``automountServiceAccountToken: false`` on the pods they
          deploy. Splice components do not use the Kubernetes API, so pods no longer receive an
          API-server credential by default; this reduces the impact of a compromised pod in
          clusters where permissions are bound to the namespace's ``default`` service account.
          If your deployment relies on the mounted token, for example through a custom service
          account set via ``serviceAccountName``, you can restore the previous behavior by setting
          the new ``automountServiceAccountToken`` Helm value to ``true``.

    - SV App

        - The public ``/v0/dso`` endpoint is deprecated and will be removed in 0.9.0
          (see also the release notes for 0.5.5 for the original deprecation notice).
          Use the public ``/v0/dso`` endpoint in the scan app if you need to fetch DSO info
          without SV operator credentials.
          A new ``/v1/dso`` endpoint has been added that returns the same response as ``/v0/dso``
          but requires authorization as SV operator.

        - Joining SVs now fetch DSO info during onboarding from a scan instance
          (typically the sponsor's) instead of the sponsor SV app's deprecated public
          ``/v0/dso`` endpoint. The scan is configured via the new ``.joinWithKeyOnboarding.sponsorScanUrl`` Helm value.
          SVs who set the ``.joinWithKeyOnboarding`` key config must set it before upgrading.

    - Scan App

        - *Breaking* Scan will no longer return featured app rights as part of the choice context for CC transfers and allocations
          once ``no-featured-app-choice-context`` has been set in ``svOperationsSwitchOverTimes`` and the switchover time has been reached.
          This change was made to avoid apps accidentally not complying with the marker guidance set by the Canton foundation which requires precise control over the markers issued. If you
          really do intend to feature a CC transfer, you can query for the contract through ``/api/scan/v0/featured-apps/{provider_party_id}`` on Scan
          and add it to the choice context under the ``featured-app-right`` choice context key.

          Validators *must* upgrade to 0.8.0 before the SVs vote on
          setting the ``svOperationsSwitchOverTimes`` field or risk
          breakage as the ``DsoRules`` contract cannot be downgraded
          once the field is set.


    - CometBFT

        - Increased default resources of watchdog and made it only query for metrics it needs.

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

    - Wallet UI

        - The development fund allocation form now has a ``Mint After`` field, which sets the
          earliest time at which the beneficiary can mint the coupon. While
          the ``minDevelopmentFundMintingDelay`` stays unset in the ``AmuletConfig``, the field is 
          optional and the coupons are allocated without a mint-after 
          constraint.

        - The development fund coupon list now shows a ``Mint After`` column.

    - Validator

        - *breaking*: The deprecated ``TransferCommand`` functionality
          enabled by ``canton.validator-apps.validator_backend.enable-deprecated-transfer-command-support=true``
          has been fully removed. Migrate to token standard transfers
          and remove the flag.

        - The wallet endpoint ``/v0/wallet/development-fund-coupons/allocate`` accepts an
          optional ``mintAfter`` field, in epoch microseconds. Callers must set it once the SVs
          configure a non-zero ``minDevelopmentFundMintingDelay``, otherwise the allocations
          will be rejected.

    - Helm

        - The node pods of the operator charts now accept a ``priorityClassName``, so operators can
          protect a node from eviction under resource pressure. It is unset by default, which leaves
          scheduling behaviour unchanged.

    - SV UI

        - The ``AmuletRules_SetConfig`` proposal form can now set
          ``minDevelopmentFundMintingDelay`` and ``developmentFundManagerBlacklist``.
