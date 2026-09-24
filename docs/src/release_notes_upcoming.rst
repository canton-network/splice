..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

    - SV App

        - The deprecated (in 0.8.0) public ``/v0/dso`` endpoint has been removed.
          Use the public ``/v0/dso`` endpoint in the scan app if you need to fetch DSO info without SV operator credentials.

        - Joining SVs now fetch DSO info during onboarding from a scan instance
          (typically the sponsor's) instead of the sponsor SV app's deprecated public
          ``/v0/dso`` endpoint. The scan is configured via the new ``.joinWithKeyOnboarding.sponsorScanUrl`` Helm value.
          SVs who set the ``.joinWithKeyOnboarding`` key config must set it before upgrading.

        - The old governance UI, previously still reachable at ``/governance-old``, has been removed from the SV UI.
          ``/votes`` and ``/governance`` now both lead to the current governance UI.

    - Docker Compose

        - The validator deployment can now also deploy the Canton Wallet Gateway and the Portfolio UI with the new ``-g`` flag of ``start.sh``.
          This requires ``LEDGER_API_AUTH_AUDIENCE`` and ``VALIDATOR_AUTH_AUDIENCE`` in ``.env`` to be equal, and with ``-a`` an OAuth app for the Wallet Gateway UI set as ``WALLET_GATEWAY_UI_CLIENT_ID``,
          see `Wallet Gateway and Splice Portfolio <https://docs.canton.network/global-synchronizer/deployment/validator-docker-compose#wallet-gateway-and-splice-portfolio>`__.

          .. important:: From Splice 0.10.0 the Wallet Gateway will be deployed by default, so an ``.env`` with different audiences will no longer start. Enabling it with ``-g`` now prepares your deployment for that.

    - Helm

        - The deprecated `splice-domain` Helm chart has been removed.

    - Scan App

        - Added a new public ``/v0/events/latest-record-time`` endpoint that returns the latest
          record time for which ``/v0/events`` will be able to return events.

        - Added an automation to prune the DB tables having the temporary data used
          by the verdict ingestion service and the traffic-based app reward calculations.

          The default retention period is 1 week for this automation, after which the data will be removed from the DB.
          Scan apps might observe increased load for a short time (~30-60min) after the upgrade, as the automation that prunes intermediate app reward computation data catches up.

    - Validator App

        - The minting-delegation reward collection for external parties now also collects
          ``SvRewardCoupon`` rewards. An external party that is a beneficiary of SV rewards
          will have those coupons minted on its behalf by its delegate, alongside the other
          reward-coupon types.

    - SV UI

        - The ``AmuletRules_SetConfig`` proposal form can now set ``amuletSwitchOverTimes``.

        - The ``DsoRulesConfig`` proposal form can now set ``svOperationsSwitchOverTimes``.

    - Daml

        - Fix a bug in MintingDelegation that wrongly allowed the delegate to share their own coupons within a minting delegation.

